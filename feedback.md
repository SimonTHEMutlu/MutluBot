# Independent Review of Issues 1–3

## Verdict

The changes are directionally good, preserve tested correctness, and improve measured performance. However, issues 1 and 3 should not be considered fully complete.

- **Issue 1 — capture ordering:** Partially successful, but not complete. It reduces the tested depth-14 node count, and the tests cover the intended ordering rules. However, it is not yet a true staged move picker, it disables losing-capture demotion throughout certain opening searches, it still treats many losing captures as good, and quiescence still uses MVV-LVA-only ordering.
- **Issue 2 — quiescence stalemate legality:** Good enough as a first implementation. It is allocation-free on the qsearch path, passes differential testing, and removes duplicated full pseudo-legal generation. Further optimization is possible, but this issue may be closed if the intended scope was the immediate redundant legality work.
- **Issue 3 — incremental evaluation and pawn caching:** The incremental material/PST/phase work is correct and worth keeping. The pawn hash portion is not implemented. `pawnZobristKey` is maintained but has no production consumer, so the original issue is only partially complete.

No existing source file was modified during this review. The only project file created by the reviewer is this report.

## Evidence collected

### Correctness and regression tests

The current source was compiled in an isolated temporary directory. The following tests passed:

- `PositionStorageTest`
- `StaticExchangeTest`
- `EvaluatorTest`
- `IncrementalEvaluationTest`
- `LegalMoveExistenceTest`
- `SearchMovePickerTest`
- `SearchLmrTest`
- `SearchCorrectnessTest`
- `SearchInstrumentationTest`
- `SearchTargetedFeaturesTest`
- `SearchProphylaxisProbeTest`
- `SearchRegressionCorpusTest`
- `SearchHistoryRegressionCorpusTest`
- `KingSafetyRegressionCorpusTest`
- `ClockAllocationTest`
- `UciPonderProtocolTest`

Important test totals:

- Incremental evaluation/state checks: **32,103 passed**
- Randomized legal-move-existence positions: **4,868 passed**
- Search regression corpus: **3 passed**
- Search-history corpus: **4 passed**

### Evaluation parity

The current candidate and the issue-1-only revision produced the same evaluation corpus fingerprint:

```text
positions=21345 fingerprint=efee4e365a49278
```

This establishes semantic parity between the evaluator immediately before and after the incremental-evaluation work for the positions visited by this corpus.

The test currently prints this value but does not assert it. That should be corrected as described below.

### Performance

All comparison searches used a 256 MB transposition table with `ProphylaxisProbe` disabled.

#### Original reviewed engine versus current candidate

| Version | Depth | Nodes | Time | NPS |
|---|---:|---:|---:|---:|
| Original reviewed working tree | 14 | 38,442,962 | 24,290 ms | 1,582,666 |
| Current issues 1–3 candidate | 14 | 35,039,589 | 19,350 ms | 1,810,831 |

The combined changes reduced nodes by approximately **8.9%** and elapsed time by approximately **20.3%** in this start-position test.

#### Issues 2–3 isolated from issue 1

The issue-1-only revision and current revision searched exactly the same nodes and returned the same score and move. This is expected: issues 2–3 are intended to optimize computation without changing search semantics.

| Version | Depth | Nodes | Time | NPS | Score / move |
|---|---:|---:|---:|---:|---|
| Issue 1 only | 14 | 35,039,589 | 20,554 ms | 1,704,757 | +10 / `e2e4` |
| Current issues 1–3 | 14 | 35,039,589 | 19,350 ms | 1,810,831 | +10 / `e2e4` |

At depth 12, three fresh-process runs averaged approximately:

- Issue 1 only: **3,415 ms**
- Current issues 1–3: **3,256 ms**

That is approximately a **4.7%** speed improvement. The depth-14 comparison showed approximately **5.9%**. The optimization is real but modest, which is consistent with incremental material/PST/phase being implemented while the expensive pawn and king terms remain uncached.

## Issue 1: capture ordering

## What was done well

The implementation has several sound properties:

- TT moves remain first.
- Promotions remain protected from reduction.
- Clearly losing ordinary captures are moved behind quiets.
- Clearly losing late captures may receive LMR, while early moves, shallow moves, checks, TT moves, promotions, and en passant remain protected.
- SEE results are encoded in the move score and reused by the LMR decision.
- The tests verify good, equal, mildly losing, strongly losing, promotion, en-passant, TT, killer, and history cases.
- The complete move set is retained without duplicates.

These changes are useful and appear to be responsible for a meaningful portion of the reduced node count.

## Finding 1.1 — opening bishop preference disables capture demotion globally

Severity: **high**

In `Search.selectNextMove`, the following branch overrides SEE classification:

```java
if (rootBishopPreferenceActive) {
    scores[bestIdx] = GOOD_CAPTURE_SCORE + mvvLva(board, move);
}
```

`rootBishopPreferenceActive` is a property of the root position, but `selectNextMove` is called at every descendant node. When the root satisfies the one-knight-each opening pattern, every non-promotion capture throughout the entire search is classified as good, including a queen-for-pawn capture with a deeply negative SEE.

This silently disables the main benefit of issue 1 during exactly the opening positions in which the bishop preference is active. The existing move-picker tests construct a new `Search` directly, leaving this flag false, so they do not cover the production behavior.

### Required correction

Remove `rootBishopPreferenceActive` from capture classification. Root move preference and descendant move ordering are separate concerns.

The classification should always be based on SEE:

```java
if (see >= goodCaptureThreshold) {
    // good/equal capture stage
} else {
    // losing capture stage
}
```

If the bishop preference tests change after this correction, fix the root-preference implementation or its test assumptions. Do not preserve a root preference by disabling improved capture ordering in the entire tree.

### Required regression test

Create a root position that activates `rootBishopPreferenceActive`, then reach or directly construct a descendant containing a clearly losing capture. Verify that:

- The losing capture is after all ordinary quiet moves.
- Its cached SEE is still available to LMR.
- The bishop root candidate retains its intended temporary bonus.

## Finding 1.2 — this is not yet a genuinely lazy staged move picker

Severity: **medium-high**

Unclassified captures have a score of 1,800,000, while classified good captures have a score of 1,600,000. `selectNextMove` repeatedly selects the highest unclassified capture, calculates SEE, rescales it, and loops. Therefore, before it returns the first classified good capture, it normally classifies every ordinary capture in the move list.

At depth 12, instrumentation reported:

```text
mainOrderSeeCalls=786282
mainSeeDemotions=234749
```

The previous engine performed almost no main-search SEE work. Some additional SEE work is justified by the node savings, but the current implementation's comment that “only the current highest candidate receives SEE” is misleading: only one receives SEE per loop iteration, but all unclassified captures are normally processed before the first move is returned.

The implementation also continues to scan the suffix repeatedly, retaining much of the quadratic selection-sort behavior.

### Recommended design

Implement an explicit phase-based move picker with state such as:

```text
TT_MOVE
GENERATE_CAPTURES
GOOD_CAPTURES
KILLERS
QUIETS
BAD_CAPTURES
DONE
```

Recommended behavior:

1. Return a legal/pseudo-legal TT move once.
2. Generate captures and promotions.
3. Partition them into good/equal and losing lists.
4. Return good captures by score.
5. Return killers, excluding duplicates.
6. Return quiet moves by history.
7. Return losing captures last, ordered by SEE.

For cheaper classification, add a threshold form of SEE such as:

```java
boolean seeAtLeast(Board board, int move, int threshold)
```

This can stop as soon as the threshold result is proven instead of calculating an exact exchange score for every candidate. Calculate an exact SEE only when it is genuinely useful for ordering or diagnostics.

## Finding 1.3 — the “good” stage includes materially losing captures

Severity: **medium**

The good-capture condition is currently:

```java
see > -300
```

Thus a capture losing 299 centipawns is ordered before every quiet move and protected from LMR. This does not match the requested “winning/equal captures, then quiets, then losing captures” design.

The `-300` threshold may have been chosen as a conservative strength safeguard, but it should not be called good/equal capture ordering. It leaves a large tactical band effectively unchanged.

### Recommended correction

Separate the two concepts:

- **Move-ordering stage threshold:** normally `SEE >= 0`, or a very small tuned margin.
- **LMR/pruning safety threshold:** may be more conservative and dependent on depth, move rank, PV status, or tactical context.

For example, a capture with SEE `-80` could be placed after quiets but receive a smaller reduction than a capture with SEE `-800`. Do not use one hard-coded `-300` threshold for both ordering and reduction safety without testing alternatives.

At minimum, benchmark and game-test thresholds such as `0`, `-50`, `-100`, and `-300`.

## Finding 1.4 — quiescence capture ordering is unchanged

Severity: **medium-high**

The main search uses `selectNextMove`, but normal quiescence still calls `scoreCaptures`, which uses MVV-LVA only. The qsearch then performs its old selective SEE pruning after a move has already been selected.

Quiescence remains approximately half of the total tree. In the current depth-12 run:

```text
mainNodes=2744076
qNodes=2874371
qSeePruneCalls=663094
```

Leaving qsearch ordering unchanged misses an important part of issue 1.

### Recommended correction

Use SEE-aware qsearch stages:

1. Promotions and clearly winning/equal captures.
2. Other tactically necessary captures.
3. Prune or defer negative-SEE captures when not in check and when they do not give check.

When in check, continue searching all legal evasions; do not apply ordinary qsearch capture pruning to evasions.

Add qsearch tests for:

- Equal-value but poisoned captures.
- X-ray recaptures.
- Pinned recapturing pieces.
- En passant discovered lines.
- Capture promotions and underpromotions.
- A negative-SEE capture that gives check and must remain searchable.

## Finding 1.5 — strength validation is insufficient

Severity: **medium**

Fixed-depth node reduction is encouraging but does not establish that reducing strongly losing captures is tactically safe in this implementation. The existing regression tests are small, and no equal-time match result or tactical-suite comparison was recorded.

### Acceptance requirements

Before declaring issue 1 complete:

- Run the existing regression suites.
- Run a tactical suite at equal nodes and equal time.
- Run self-play against the previous version using identical books, hash, threads, and time controls.
- Prefer SPRT or, at minimum, enough games to distinguish signal from noise.
- Record total nodes, time, first-move cutoff rate, SEE calls, demotions, reduced bad captures, and full-depth re-searches.

The feature should be kept only if it improves equal-time strength or provides a clear speed gain without tactical regression.

## Issue 2: quiescence stalemate legality

## What was done well

The new `hasAnyLegalMove(Board, MoveList)` path:

- Reuses caller-owned storage.
- Avoids allocating a new `MoveList` in the qsearch hot path.
- Avoids regenerating king and pawn moves inside a later full pseudo-legal pass.
- Replaces king make/check/unmake probes with occupancy-adjusted attack masks.
- Handles non-king pieces, castling, promotions, and en passant.
- Preserves board state in the tested positions.
- Agrees with full legal move generation across 4,868 randomized legal positions.

This is a reasonable, correct first optimization and can be accepted for the immediate issue.

## Finding 2.1 — the implementation remains a sequential multi-pass probe

Severity: **low-medium**

Although the qsearch now calls one method, that method still performs these phases:

1. King targets.
2. Generated pawn candidates with make/check/unmake.
3. Knight, bishop, rook, and queen targets with make/check/unmake.
4. Castling generation and another legality check.

This is better than the previous king + pawn + complete pseudo-legal generation sequence, but it is not direct legal-move generation from checkers and pins.

### Future improvement

If profiling still identifies this routine as a major cost, calculate once per position:

- Checkers attacking the king.
- Pinned friendly pieces and their allowed rays.
- Legal king destinations using occupancy after the move.
- Single-check evasion mask.
- Double-check restriction to king moves.

Then determine whether any legal move exists using masks without make/unmake for ordinary moves. En passant still needs special discovered-check handling.

Do not undertake this complexity without profiling; the current version is acceptable and well tested.

## Finding 2.2 — the allocating wrapper remains in production use

Severity: **low**

`hasLegalMove(Board)` still creates a new `MoveList`. It is used by prophylaxis code. This is outside the normal qsearch hot path but creates avoidable allocations when `ProphylaxisProbe` is enabled.

### Recommended correction

Pass an existing scratch list to prophylaxis code or give that subsystem dedicated reusable move storage. Keep the zero-allocation rule consistent across search-related callers.

## Finding 2.3 — add adversarial differential coverage

Severity: **low**

The randomized tests are good, but legal random games rarely reach some difficult legality patterns.

Add generated or curated positions covering:

- Double check.
- Absolute pins on every ray direction.
- En passant exposing a rook or bishop attack.
- En passant as the only legal evasion.
- Castling rights with missing or displaced rooks, if malformed FENs must be tolerated.
- King captures that remove the only blocker from a rook, bishop, or queen ray.
- Promotions as the only legal move.

Compare `hasAnyLegalMove` against `generateLegal` and assert complete board restoration.

## Issue 3: incremental evaluation and pawn caching

## What was done well

The following incremental state is implemented correctly in the tested corpus:

- Material score.
- Middlegame PST score.
- Endgame PST score.
- Unclamped phase units.
- Pawn-only Zobrist key.

The update paths cover:

- Quiet moves.
- Captures.
- Castling rook and king movement.
- En passant.
- Quiet promotions.
- Capture promotions.
- Null moves.
- Long make/unmake sequences.

Keeping raw phase units unclamped is correct because promoted material can exceed the normal opening phase and must later unmake exactly.

The current evaluator produces the same corpus fingerprint as the issue-1-only revision, and the search returns identical nodes, score, and best move. The incremental base should be retained.

## Finding 3.1 — there is no pawn hash table

Severity: **high**

`Board.pawnZobristKey` is updated, but no evaluator or search component probes a pawn cache. Production searches still recalculate:

- Passed-pawn identification and advancement bonuses.
- Pawn king-proximity terms.
- Isolated/backward pawn structure.
- Pawn-dependent king shelter.
- Rook open/semi-open file terms.

The pawn key is therefore currently dead production state: every pawn move pays to update it, but no evaluation call benefits from it.

Issue 3 must remain open until a cache uses the key or the scope is explicitly renamed to “incremental material/PST/phase only.”

## Finding 3.2 — the current pawn structure cannot safely be cached by pawn key alone

Severity: **high**

The test suite correctly demonstrates this subtle problem:

```java
require(blocker.pawnZobristKey == pawnKey,
        "nonpawn blocker preserves pawn key");
require(Evaluator.pawnStructureTerms(blocker) != blockedTerms,
        "blocker removal changes backward pawn term");
```

`isBackwardPawn` examines `b.allOccupancy` to determine whether a neighboring pawn has a clear support path. Moving a knight can therefore change `pawnStructureTerms` without changing `pawnZobristKey`.

A cache keyed only by `pawnZobristKey` would return incorrect scores for the current pawn-structure function.

### Required design decision

Choose one of these approaches:

#### Preferred: separate pawn-only and dynamic terms

Cache only features determined entirely by pawn locations:

- Isolated pawns.
- Doubled pawns, if added.
- Pawn islands and chains.
- Candidate/passers based on enemy pawn masks.
- Base passed-pawn bonuses.
- Pawn attack maps.
- White and black passer bitboards.

Calculate dynamic terms outside the pawn cache:

- King proximity to passed pawns.
- Backward-pawn support paths affected by non-pawn blockers.
- Rook open-file bonuses.
- Queen-presence-dependent shelter terms.
- King-square-dependent shelter terms.

This gives high cache reuse and clear correctness boundaries.

#### Alternative: expand the cache key

Include every dependency in the key, such as relevant blocker occupancy, king squares, and queen presence. This is harder to maintain and lowers the hit rate. Using the full position Zobrist key would be correct but would no longer be a useful pawn hash.

The preferred separation is easier to verify and more conventional.

## Finding 3.3 — suggested pawn-table representation

Avoid allocating entry objects. A small direct-mapped or two-way table can use primitive arrays:

```java
final class PawnTable {
    long[] keys;
    long[] packedScores;       // signed MG and EG terms
    long[] whitePassers;
    long[] blackPassers;
    long[] whitePawnAttacks;
    long[] blackPawnAttacks;
}
```

At evaluation time:

1. Probe using `board.pawnZobristKey`.
2. On a hit, reuse the packed pawn-only score and pawn feature masks.
3. On a miss, calculate the pawn-only data and store it.
4. Add king proximity, blocker-dependent, rook, and king-shelter terms separately.

For future Lazy SMP, prefer one pawn table per search worker initially. A shared table requires a publication strategy similar to a thread-safe transposition table.

Add instrumentation:

- Pawn probes.
- Pawn hits.
- Pawn misses.
- Hit rate.
- Replacement count.
- Time or evaluator calls saved in benchmark mode.

## Finding 3.4 — the parity corpus must assert its baseline

Severity: **medium**

`EvaluationParityCorpus` currently prints its fingerprint but succeeds regardless of the result. This means a future evaluation regression will not fail automated testing.

### Required correction

Record the immediate pre-incremental expected values and assert them:

```text
positions=21345
fingerprint=efee4e365a49278
```

The test should throw an assertion error if either changes. If an intentional evaluation change alters the fingerprint, require the developer to update the expected value with a clear explanation.

Keep `IncrementalEvaluationTest`; it validates cached-state consistency independently. The two tests cover different failure modes:

- Incremental test: cached board state agrees with a fresh reconstruction.
- Fingerprint test: the evaluator's externally visible results agree with the approved baseline.

## Finding 3.5 — benchmark attribution needs improvement

Severity: **medium**

`Issue3Benchmark` runs the complete working tree. Its result cannot by itself attribute an improvement to material/PST increments, qsearch legality, pawn hashing, or capture ordering.

### Recommended benchmark protocol

For each optimization:

1. Create a clean baseline revision.
2. Change only one feature.
3. Use fresh JVM processes.
4. Use the same hash, FENs, depth, probe setting, and JVM.
5. Alternate baseline and candidate runs to reduce thermal/order bias.
6. Use several positions, not only the start position.
7. Report median time and nodes, not only one run.

Suggested position set:

- Start position.
- Kiwipete.
- Closed middlegame with many pawns.
- Open tactical middlegame.
- Rook ending.
- Queenless minor-piece ending.

For pure evaluation changes, fixed nodes or identical fixed-depth node counts are ideal. Confirm that score, move, PV, and node count remain identical before comparing elapsed time.

## Required work before closing all three issues

### Must fix

1. Remove the `rootBishopPreferenceActive` bypass from descendant capture classification.
2. Add a regression test proving losing captures are still demoted when the root bishop preference is active.
3. Implement an actual pawn cache or explicitly leave issue 3 open and rename the completed scope.
4. Separate cacheable pawn-only terms from terms depending on non-pawn occupancy, king squares, rooks, or queen presence.
5. Make `EvaluationParityCorpus` assert `positions=21345` and fingerprint `efee4e365a49278`.

### Strongly recommended

6. Replace the current repeated suffix scans with an explicit phase-based move picker.
7. Use `SEE >= 0` or a separately tuned small margin for the good-capture stage; do not automatically treat every capture above `-300` as good.
8. Add SEE-aware ordering to normal quiescence search.
9. Add threshold SEE to avoid calculating exact exchange values unnecessarily.
10. Run equal-time tactical and self-play validation before accepting bad-capture LMR as a strength improvement.

### Optional follow-up

11. Replace the remaining pawn/non-king legality make/unmake probes with direct checker/pin masks if profiling justifies the complexity.
12. Remove the allocation in the prophylaxis caller of `hasLegalMove` by providing reusable scratch storage.

## Completion criteria

Issues 1–3 can be considered complete when all of the following are true:

- All current correctness and regression tests pass.
- Capture demotion works regardless of root bishop preference.
- Main search and qsearch have documented SEE-aware stage behavior.
- The chosen SEE thresholds are supported by benchmark and strength results.
- Incremental state survives all move types and long make/unmake sequences.
- Evaluation parity is asserted automatically.
- A production pawn cache is present and its dependency boundaries are correct.
- Pawn-cache hit rate is measured on representative searches.
- Fixed-depth comparisons cover multiple positions and show no unintended node/score/PV changes for pure speed optimizations.
- Equal-time games or tactical tests show no strength regression.

The existing work is a solid foundation. The immediate priority is to remove the global bishop-preference bypass and finish the pawn-cache design correctly rather than treating the maintained pawn key as a completed cache.
