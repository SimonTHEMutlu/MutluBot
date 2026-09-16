# Search regression corpus

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
