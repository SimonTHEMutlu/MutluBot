# MutluBOT2 Search Performance Review

## Summary

MutluBOT2 is not necessarily stuck when the GUI continues to show depth 16. The engine reports a depth only after the entire iterative-deepening iteration has completed. It can therefore spend many minutes working on depth 17 while the GUI continues to display the last completed depth.

The observed speed of roughly 1.3-2.5 million nodes per second is believable for the current engine. Although MutluBOT2 uses a hand-crafted evaluation (HCE), its evaluator is not lightweight: it repeatedly scans all pieces, analyzes pawn structure, calculates king shelter, and generates attack zones. The engine is also single-threaded.

The main obstacle to reaching greater depths is the size of the search tree. Raw node speed matters, but better move ordering, pruning, and transposition-table effectiveness will produce larger depth gains.

## Measurements

These tests were performed on an AMD Ryzen 7 5700U with 8 cores and 16 logical processors.

| Configuration | Depth 14 nodes | Time | Reported NPS |
|---|---:|---:|---:|
| Hash 64 MB, ProphylaxisProbe enabled | 48.55 million | 37.2 seconds | 1.30 million |
| Hash 64 MB, ProphylaxisProbe disabled | 52.38 million | 30.3 seconds | 1.73 million |
| Hash 256 MB, ProphylaxisProbe disabled | 38.44 million | 24.3 seconds | 1.58 million |

Increasing the hash table from 64 MB to 256 MB reduced the depth-14 node count by approximately 27% and the elapsed time by approximately 20% in this test.

An instrumented depth-12 search produced:

- 9,340,209 total nodes
- 4,527,212 main-search nodes
- 4,812,997 quiescence nodes
- 4,549,147 full evaluator calls
- 1,105,060 SEE calls
- Approximately 52% of all reported nodes were quiescence nodes

Start-position perft reached approximately 16.9 million leaf nodes per second. Perft and search NPS are not directly comparable, but this result shows that the complete search and evaluation are much more expensive than basic move generation and make/unmake alone.

## 1. Capture ordering searches bad captures too early

All captures currently receive a score above quiet moves through MVV-LVA ordering. A losing queen-for-pawn capture can therefore be searched before strong quiet moves. All captures are also exempt from late-move reductions.

The current SEE-based correction is very narrow: it is applied only in certain equal-MVV-LVA ties and does not generally move losing captures behind quiet moves.

### Recommended change

Implement a staged move picker in this order:

1. Transposition-table move
2. Winning and equal captures, ordered using SEE
3. Promotions
4. Killer and countermoves
5. Quiet moves ordered using history
6. Losing captures

Bad captures should be eligible for reduction or pruning where tactically safe. This is likely one of the highest-impact changes for reducing the search tree.

## 2. Quiescence performs expensive stalemate checks

Every non-check quiescence node currently attempts to establish whether the position is stalemate before performing stand-pat evaluation. It checks for a legal king move, then a legal pawn move, then may generate and test every pseudo-legal move.

These additional make/unmake and attack-detection operations are not separately represented in the node counter. With approximately 4.8 million quiescence nodes at depth 12, even a small amount of extra work per node is expensive.

### Recommended change

Replace the three-stage test with one allocation-free `hasAnyLegalMove` implementation. Ideally, legal moves should be generated directly using checker, pin, and legal-target masks rather than by making and unmaking every pseudo-legal move.

Preserve correct stalemate handling, but avoid multiple separate legality passes at every quiet quiescence node.

## 3. Evaluation is recalculated from scratch

The evaluator repeatedly recalculates:

- Material
- Middlegame and endgame piece-square values
- Game phase
- Passed pawns
- Pawn structure
- Rook files
- King shelter
- King-danger attack zones for both sides

There were approximately 4.55 million full evaluator calls in the depth-12 benchmark.

### Recommended change

Maintain the following incrementally in `Board.makeMove` and `Board.unmakeMove`:

- Material score
- Middlegame PST score
- Endgame PST score
- Game phase
- Pawn Zobrist key

Add a pawn hash table for pawn structure and pawn-dependent king-shelter terms. These values change much less frequently than the complete position.

The full king-danger calculation should also be gated or simplified in undeveloped opening positions. Current working-tree changes removed the earlier development gate, causing attack-zone generation to run even when the pieces remain mostly undeveloped.

## 4. Late-move reductions need adaptive information and a cheaper hot path

**Status: still open, but the original diagnosis requires refinement.**

The active engine already has a functioning depth/rank LMR schedule. It protects
checks, check evasions, promotions, the TT move, both killers, advanced passed-pawn
pushes, opening bishop-preference moves, and near-root pawn offers. Strongly losing
ordinary captures with `SEE <= -300` can be reduced. Reduced searches that raise
alpha receive nominal-depth verification.

Two replacement schedules were evaluated in
[ISSUE4_LMR_EVIDENCE.md](../../ISSUE4_LMR_EVIDENCE.md):

- A conservative logarithmic table reduced many moves less than the active formula.
  It raised NPS in four main positions, but node counts increased much faster, so
  fixed-depth elapsed time increased by 21% to 91% in those positions.
- A second table preserved the current formula as a floor and changed essentially
  one depth/rank cell. Its node-count changes were mostly below 0.3% and its timing
  results were inconclusive.

Rejecting those two prototypes was reasonable. This evidence does **not** establish
that LMR cannot be improved: the first candidate was frequently less aggressive
than the baseline, and the second was too similar to constitute a meaningful test.
Neither candidate implemented the important adaptive signals in the original
recommendation.

### Timing and evidence limitations

The evidence file reports baseline and Prototype 1 median search times and reports
all three Prototype 2 timing samples. It does not report the three raw Prototype 1
runs or execution times for the listed correctness tests. More importantly, the
performance samples are mostly shorter than two seconds, there are only three
runs, and no equal-time, tactical-suite, or self-play comparison was performed.

NPS must not be treated as the primary success metric when a search change alters
the tree. Different LMR policies change the mixture of expensive main-search nodes
and cheaper shallow or quiescence nodes. For a fixed-depth comparison:

```text
candidate time / baseline time = node-count ratio / NPS ratio
```

An increase in nodes is acceptable only if the NPS increase compensates for it,
but even equal elapsed time does not demonstrate equal playing strength. The final
acceptance criteria must be equal-time tactical performance and paired games.

### Recommended implementation sequence

#### 4.1 Remove unnecessary LMR eligibility work without changing the tree

The main move loop currently calculates `givesCheck` and
`isAdvancedPassedPawnPush` for every legal move after the first, before determining
whether depth, move rank, or cheap move properties make that move eligible for LMR.
The near-root pawn-offer wrapper is likewise entered before the ordinary guards.

Refactor the selection in this order:

1. Calculate the existing base depth/rank reduction.
2. Reject ineligible depth/rank combinations, promotions, TT moves, killers, and
   protected captures using cheap tests.
3. Only for a remaining LMR candidate, calculate whether it gives check.
4. Only test advanced-passer status for a pawn push.
5. Only test the near-root pawn-offer rule at `searchPly <= 1` for a quiet pawn move.

This step must produce exactly the same nodes, score, move, and PV as the current
engine. Its purpose is to raise NPS and make later adaptive LMR affordable.

#### 4.2 Use a three-stage PVS/LMR re-search

The current code follows a reduced alpha-raising scout immediately with a
nominal-depth search using the entire remaining window. Use this sequence instead:

1. Reduced-depth null-window search.
2. If it raises alpha, nominal-depth null-window verification.
3. Only if that verification succeeds at a PV node, nominal-depth full-window
   search.

Non-PV nodes normally need no third search because their window is already null.
Preserve the special shifted-alpha behavior for root bishop-preference moves and
do not mark a root result exact until the required full-window search completes.

##### 4.2 validation verdict and optional corrected retry

The first 4.2 candidate was correctly rejected for promotion to an accepted or
released milestone. Its archived validation produced a mixed result: across 42
alternating fixed-depth pairs it reduced geometric-mean elapsed time by 4.74% and
nodes by 5.45%, while NPS fell by about 0.75%. Start, rook, and minor were faster,
but closed and Italian were respectively about 29.4% and 43.8% slower and lost one
completed ply in every equal-time pair. One enforced history regression required
an explicit waiver, a pending Game 8 defensive move changed consistently, the
verified tactical suite was tied, and paired self-play/SPRT was not run.

This evidence rejects that particular candidate as a release change; it does not
prove that staged PVS/LMR verification is inherently unsuitable. The archived
candidate also entered the third full-window search whenever nominal-depth
verification raised alpha at a PV node. It did not first check whether the
verification had already reached beta. A score at or above beta is already a
valid cutoff and should not cause another search.

A corrected candidate should require all of the following before its third stage:

```java
if (verificationRaisedAlpha
        && score < beta
        && pvNode
        && !stopRequested) {
    // nominal-depth full-window search
}
```

The focused PVS tests must add a PV verification case that reaches beta and prove
that the full-window counter does not increase. Instrumentation should separately
count these avoided `verificationScore >= beta` searches.

If one final 4.2 experiment is attempted, compare three isolated configurations:

1. Accepted 4.1 behavior: a reduced alpha raise goes directly to the existing
   nominal-depth search.
2. Corrected three-stage behavior: nominal-depth null-window verification followed
   by a full-window search only when `alpha < score < beta` at a PV node.
3. Hybrid behavior: use the extra null-window verification only when the original
   reduction is at least two plies; retain the accepted direct re-search for a
   one-ply reduction.

The hybrid is important because most observed reductions were one ply. Adding an
extra verification search to every one-ply alpha raise may cost more than the
occasional avoided full-window search saves.

For every benchmark position, report:

- reduced alpha raises;
- nominal-depth null-window verifications;
- verifications that fail low;
- verifications that reach or exceed beta;
- verifications strictly between alpha and beta;
- resulting PV full-window searches; and
- node deltas for each stage, clearly marked as inclusive or disjoint.

Run the same long, alternating fixed-depth and equal-time matrix used by the first
validation. Reject a corrected or hybrid candidate before self-play if the closed
and Italian positions still consistently lose a completed ply, if an enforced
regression fails without an independently justified correction, or if the result
depends on a move-choice waiver. Only a candidate that clears those screens should
advance to varied-opening, color-reversed paired self-play or SPRT.

#### 4.3 Make quiet history bounded and bidirectional

The current history table receives only an unbounded positive `depth * depth`
bonus for a quiet cutoff. Before using history to tune LMR:

- use saturating/gravity updates;
- reward the quiet move that causes a beta cutoff;
- penalize earlier quiet moves searched before that cutoff;
- keep the values bounded and periodically age them if needed.

Then test a small modifier in isolation: reduce a very good-history move one ply
less and a bad-history move one ply more. Clamp the final reduction so at least the
required nominal depth remains available for verification.

#### 4.4 Add explicit PV/non-PV information

Record node type before TT bounds alter the search window. Begin conservatively:

- PV nodes use the base reduction or one ply less;
- sufficiently late moves at non-PV null-window nodes may receive one extra ply;
- the first searched move is never reduced;
- every reduced alpha raise receives nominal-depth verification.

Add only one modifier per experiment so its effect can be attributed reliably.
Cut-node expectation and continuation history can follow after PV and ordinary
history behavior are stable.

#### 4.5 Delay the improving-state modifier

The engine does not already have a cheap static evaluation at every internal node.
Do not call the full evaluator throughout the tree solely to obtain an improving
flag. Add this signal only after static evaluations are naturally available or
cached, for example alongside TT evaluation data. Otherwise the added evaluation
cost may reduce NPS more than adaptive LMR saves.

#### 4.6 Treat a table as a tuning mechanism, not the optimization itself

The current implementation is a scalar integer formula; there is no existing
two-dimensional LMR `int[][]`. If a table is useful for tuning, use a flat
`byte[]` with capped depth and move-rank indices. The table should initially
reproduce the current schedule exactly; performance and strength changes should
come from deliberate schedule or modifier changes rather than representation
differences. The current formula is now represented by a flat 44-by-26 byte
table (depth indices 0-43, rank indices 0-25). These caps are exact: at depth
43, even rank 6 gives `floor((43-3)*(6-5)/20) = 2`, so greater depths are
saturated for every rank; at rank 25, depth 4 already reaches the depth-clamped
maximum of 2, while every depth of at least 5 reaches the schedule maximum of 3.

### Required instrumentation

In addition to the existing counters, record:

- moves that reach base LMR eligibility;
- skips by reason;
- reductions grouped by reduction size and preferably depth/rank bucket;
- reduced scouts that raise alpha;
- nominal-depth null-window verifications that raise alpha;
- full-window PV re-searches;
- nodes spent in each reduced or verification stage.

The reported baseline full-depth re-search rate is only about 0.1% to 1.7% across
the six positions. That may leave room for stronger reductions, but a low re-search
rate cannot prove tactical safety because an excessive reduction can incorrectly
fail low and receive no verification.

### Acceptance requirements

1. Preserve perft and all correctness/regression tests.
2. Run instrumentation-disabled searches lasting at least 10-30 seconds per
   position rather than relying on sub-two-second samples.
3. Use at least seven fresh, alternating paired runs and publish every raw result.
4. Report nodes, elapsed time, NPS, completed depth, selective depth, score, move,
   and PV. Aggregate ratios with a geometric mean.
5. Add equal-time comparisons and record the last fully completed iteration.
6. Run a fixed-time tactical suite and compare solved count and time/nodes to solve.
7. Validate retained candidates with paired self-play or SPRT. Passing unit tests
   establishes correctness, not playing strength.

## 5. Important selective-search techniques are missing

The engine lacks several standard techniques that substantially reduce tree size.

### Recommended additions

Add and test these one at a time:

- Reverse futility pruning at shallow non-PV nodes
- Shallow futility pruning for late quiet moves
- Late-move pruning
- Razoring near the horizon
- Dynamic null-move reduction based on depth and evaluation margin
- Mate-distance pruning
- Internal iterative reduction when no TT move is available
- History maluses for quiet moves searched before a cutoff
- Continuation history
- Capture history
- Countermove ordering

Each feature should be verified with tactical/regression tests and Elo or SPRT games. Aggressive pruning can raise depth while reducing playing strength if it is not tuned carefully.

## 6. The transposition table is direct-mapped

The transposition table stores only one entry at each index. A new colliding position can replace a valuable deep entry even when another nearby slot could have been used.

The default Lichess configuration uses only 64 MB of hash. At depth 14, the engine may examine tens of millions of nodes, so collisions and replacement quality matter.

### Recommended change

- Change the table to clusters containing two to four entries.
- Select replacement victims using depth, bound quality, and generation age.
- Preserve deep exact entries where possible.
- Add UCI `hashfull` reporting so occupancy can be measured.
- Change the normal configuration from 64 MB to 256 or 512 MB on the current computer.

The 256 MB test completed depth 14 approximately six seconds faster than the comparable 64 MB test.

## 7. ProphylaxisProbe adds nonstandard root work

`ProphylaxisProbe` is enabled by default and in the Lichess configuration. It performs:

- Threat-metric calculations
- Artificial-pass analysis
- Bounded static leaf evaluations
- Additional legal-move searches
- Occasional full-depth verification searches of root alternatives

Some of this work does not increment the normal node counter, which makes reported NPS an incomplete measurement. The feature also changes root selection and therefore changes the shape of later search iterations.

### Recommended change

- Disable `ProphylaxisProbe` when running normal performance benchmarks.
- Compare it at equal time rather than equal depth.
- Keep it enabled for games only if controlled testing demonstrates an Elo improvement.
- If retained, add separate UCI/debug counters for its evaluations, verification nodes, and elapsed time.

## 8. The engine is single-threaded

The current UCI engine launches one search worker. The computer has eight physical cores and sixteen logical processors, so most available CPU capacity is unused.

### Recommended change

After improving the single-threaded search, add a UCI `Threads` option and implement Lazy SMP.

Each worker should have its own:

- Board and undo stack
- Move buffers
- Killer table
- History tables
- Search state and repetition stack

Workers may share a transposition table, but the current separate key/value publication is not designed for safe concurrent access. Redesign the TT for reliable concurrent publication before sharing it among workers.

Lazy SMP can substantially increase total NPS, although scaling will not be perfectly linear.

## 9. Sliding-piece attacks use ray scans

Bishop and rook attacks are produced by consulting four rays, finding the first blocker on each ray, and masking the remaining squares. This is correct, but it is called frequently by move generation, check detection, SEE, and king-safety evaluation.

### Recommended change

Eventually replace the ray implementation with one of:

- Magic bitboards
- PEXT-style occupancy indexing
- Precomputed occupancy-indexed attack tables

This should be done after the larger search-tree and evaluation problems, because reducing the number of searched nodes is likely to provide a greater benefit.

## 10. Move ordering uses repeated selection scans

The main search and quiescence search repeatedly scan the remaining move array to select the next highest-scored move. This can approach quadratic work in the number of generated moves.

### Recommended change

Use a staged move picker that generates or selects only the next required class of moves. Avoid fully ordering moves that will never be searched because an earlier move produces a cutoff.

## 11. History information is limited

The history table only receives a positive `depth * depth` bonus for quiet cutoff moves. It has no malus for previously searched quiet moves, no continuation context, and no aging or gravity formula. It is also cleared at the start of every top-level search.

### Recommended change

- Use bounded gravity-style history updates.
- Penalize quiet moves searched before the cutoff move.
- Index continuation history by preceding move or piece/to-square context.
- Add separate capture history.
- Consider retaining useful history between moves while aging it safely.

Better history ordering improves first-move cutoff rates and makes PVS and LMR more effective.

## 12. Aspiration-window recovery is coarse

The engine begins with a fixed 25-centipawn window. After a fail-low or fail-high, it expands that side directly to infinity.

### Recommended change

Use gradual exponential widening, for example 16, 32, 64, and 128 centipawns before falling back to a full window. Track retry counts and elapsed time to confirm that the narrower searches save work overall.

## 13. Search progress is invisible during an unfinished iteration

The engine only prints its primary UCI `info depth` line after a completed iteration. This makes a long depth-17 search appear frozen at depth 16.

### Recommended change

During a long root iteration, print periodic UCI information approximately once per second:

- Current completed depth
- `seldepth`
- Nodes
- NPS
- Elapsed time
- `currmove`
- `currmovenumber`
- `hashfull`

This will not make the engine stronger, but it will clearly distinguish slow progress from a stalled search.

## 14. HCE does not automatically imply high NPS

A hand-crafted evaluator can be very small or very expensive. MutluBOT2's evaluator contains pawn-structure logic, king shelter, passed-pawn terms, rook-file terms, and attack-zone generation. It is substantially more work than a material-and-PST-only evaluator.

NPS also cannot be compared reliably between unrelated engines because engines count nodes differently. MutluBOT2 does not count every legality probe, threat-probe evaluation, move-ordering operation, or attack calculation as a node.

The meaningful performance measurements are:

- Time to a fixed depth
- Nodes to a fixed depth
- Elo at equal time
- Tactical-suite accuracy at equal nodes or equal time

## 15. More NPS does not translate linearly into depth

Depth costs grow exponentially. In the tested searches, a new iteration sometimes required approximately 2.5-4 times as much work as the previous iteration. Doubling NPS may therefore gain less than one full ply.

Reaching two additional plies can require roughly 6-16 times as much effective search capacity. That capacity can come from:

- Searching fewer nodes through pruning and ordering
- Increasing TT effectiveness
- Evaluating each node faster
- Using multiple CPU cores
- Pondering successfully on the expected reply

The first two generally provide the greatest strength gain per unit of work.

## 16. The native executable is stale

The active `dist/chess-engine.jar` was built on October 8, 2026. The jar bundled inside `native/ChessEngine` was built on September 29, 2026 and is a different binary.

The Lichess configuration uses the current `dist/chess-engine.jar`, but SCID or another GUI may be using the older native executable.

### Recommended change

Rebuild the native application after source changes before using it for comparisons. Always record the executable or jar hash used for a benchmark.

## Suggested implementation order

1. Establish repeatable fixed-depth and fixed-time benchmarks.
2. Set Hash to 256 MB and benchmark with ProphylaxisProbe disabled.
3. Implement staged capture ordering with full SEE classification.
4. Replace the quiescence stalemate legality probes.
5. Add incremental material/PST/phase evaluation and a pawn hash.
6. Improve LMR and history updates.
7. Add reverse futility, futility, and late-move pruning one at a time.
8. Change the TT to clustered storage and add `hashfull`.
9. Optimize sliding attacks.
10. Add Lazy SMP and the UCI `Threads` option.

After each search change, run correctness tests, perft where applicable, fixed-depth benchmarks, and controlled games. Preserve changes only when they improve strength at equal time, not merely reported depth or NPS.
