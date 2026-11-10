# Search regression corpus

## Permanent RFP performance sentinel

`search-performance-regressions.tsv` preserves the synthetic active-king rook
ending from Issue 5.1. `SearchRfpActiveKingRegressionTest` checks legal best moves
and PVs, board restoration, completed depth, and instrumentation ON/OFF
tree parity with RFP both enabled and disabled. Its default depth is 8; use
`--deep` for the historical depth-21 comparison (Hash=256, ProphylaxisProbe=false).

The frozen depth-21 baseline searched 38,862,724 nodes and chose `g6g7`; RFP
searched 70,493,140 and chose `d7f7`, both at +1 cp. These are diagnostic reference
observations, not a proof of the correct move or hard timing/node thresholds.
Keep the fixture permanently and investigate changes without tuning solely to it.
The RFP margin remains 100 cp per ply. The feature is accepted as game-test
positive, not Elo-certified, and can be disabled with the UCI option
`ReverseFutilityPruning=false`.

## Existing move-choice corpora

October 15 independent-review gate classification supersedes historical wording
below that calls a move choice required or forbidden. Every existing move-choice
row in all three TSVs is a **behavioral sentinel** pending a replayable independent
proof of its accepted set. Structural FEN/history/PV legality remains a hard
invariant. Historical Stockfish ranking is useful evidence for investigation,
but does not prove that alternatives are losing or that the accepted set is
complete. A difference needs analysis, not a user waiver.

`SearchHistoryRegressionCorpusTest` searches active rows and reports differences;
`--enforce-pending` additionally searches pending rows in either history or
king-safety tests. `--legacy-choice-gate` explicitly restores the old preference
assertions for compatibility. It does not turn them into correctness proofs.
The immutable historical rows and their observations remain intact.

The complete historical classification, consumed holdout, independently proved
mate gates and paired-game runner remain in the author's separate local
`MutluBOT2/issue-validation/independent-review/` archive. They are not checked-in
build dependencies. See the portable [validation summary](../../docs/VALIDATION.md)
for accepted results and evidence limits; do not reuse a consumed holdout as new
acceptance evidence.

`search-regressions.tsv` stores positions whose good behavior should survive future evaluator and search changes. The initial entries are positive conversion decisions from Game 10 of `MutluBotBetaPermanenntThinkingBulletMatchEndgameImprovement.pgn`.

The corpus separates acceptance from exact game reproduction. In `g10_move25`, either `b4c5` or the essentially equal `d4c5` passes, while `played_move=b4c5` lets reports say whether MutluBot reproduced its tournament choice. A result of `d4d5` must be flagged because the audit found that advance premature in this position. Moves 28 and 30 each require their single listed move.

The first-seen, stable, and fixed-depth fields are historical observations from fresh JVM searches with a 64 MB hash. "Stable" means the first completed iteration from which an accepted move remained the PV's first move through the fixed-depth run. Times are machine-dependent diagnostics, not hard pass thresholds. Move choice, forbidden-move avoidance, score direction, node changes, and large timing changes should all be reported separately.

Validate the data, FEN round trips, root criteria, and every stored PV from the repository root:

```bat
cmd /c build.bat
javac --release 8 -cp out -d out test\engine\SearchRegressionCorpusTest.java
java -cp out engine.SearchRegressionCorpusTest
```

For a future evaluator or search revision, run every FEN in a fresh JVM at depth 12 and also capture iterative UCI `info` lines. Report:

- final move, score, nodes, time, and PV;
- first-seen depth/time/nodes for an accepted move;
- first stable depth/time/nodes;
- whether the exact `played_move` was reproduced;
- any forbidden move, which is a regression even if its displayed score looks favorable.

Pending, with no cases recorded yet: the user has observed MutluBotBeta voluntarily entering threefold repetition while displaying an evaluation of at least +100 centipawns for itself. Add concrete repetition regressions only after completed PGNs are supplied and the exact repeated histories can be reconstructed.
