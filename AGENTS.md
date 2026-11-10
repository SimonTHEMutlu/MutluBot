# MutluBot development guide

## Scope

This repository contains the dependency-free Java UCI engine. Treat this
checkout as authoritative; do not substitute code from older local engines.
The separate Lichess adapter is private testing infrastructure, not part of
this repository. Never publish its configuration, tokens, or launch scripts.

Check the current branch, remote and dirty state before editing. Preserve
unrelated changes. Do not commit or push without explicit user instruction.
Do not commit generated `out`, `dist`, `native`, `.class` or `.jar` files.

## Accepted behavior and invariants

- RFP is enabled by default, guarded at shallow non-PV nodes, with a 100 cp/ply
  margin. `ReverseFutilityPruning=false` disables it and clears old TT bounds.
  It is game-test positive, not Elo-certified. Do not retune it for one fixture.
- Preserve raw FEN en-passant serialization. Repetition identity includes EP
  only when a legal capture exists, including pin and check legality.
- TT score/bound reuse and exact PV continuation require matching halfmove
  context plus the existing generation/history guards. A mismatched entry may
  still provide a legal move-ordering hint. Do not remove clock validity for speed.
- Root threefold claims and interior search cycles have different thresholds.
  Use full move histories, not bare FENs, for path-dependent regression tests.
- Preserve mate/stalemate distinction, check evasions in quiescence, mate-score
  ply normalization, legal bestmove/PV output, and board/undo restoration.
- Dead-material recognition is explicitly deferred. The separate
  mate-distance-pruning prototype was not promoted into this source.
- Behavioral move preferences are diagnostics unless an independently complete
  tactical proof establishes the accepted move set. Never call a changed
  historical move a correctness failure just because it differs.

## Build and validation

`build.bat` produces `dist/chess-engine.jar`; it replaces generated `out` and
`dist`. Native packaging is separate and can otherwise bundle a stale JAR.
Do not rebuild or replace a running user's package without task authorization.

For isolated validation, from the repository root in PowerShell:

```powershell
javac --release 8 -d verify-out src\engine\*.java test\engine\*.java
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
Get-ChildItem test\engine -Filter '*Test.java' | ForEach-Object {
    java -ea -cp verify-out ("engine." + $_.BaseName)
    if ($LASTEXITCODE -ne 0) { throw "Failed: $($_.BaseName)" }
}
java -ea -cp verify-out engine.EvaluationParityCorpus
java -cp verify-out engine.Perft 5
java -cp verify-out engine.Perft 5 'r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1'
```

Expected perft counts: 4,865,609 and 193,690,690. Run all assertion programs
after search/evaluation/TT/history changes, not just fixture validators. Keep
`CanonicalRepetitionTest`, `Rule50TranspositionTest`, `SearchRfpTest`,
`UciRfpProtocolTest` and `SearchRfpActiveKingRegressionTest` in the gate.

For performance, freeze baseline and candidate inputs, use identical settings,
fresh processes and alternating serialized pairs, and disable detailed counters
for timing. Report time, nodes, NPS, move, score, PV, depth and seldepth separately.
Fewer nodes or higher reported depth alone do not establish strength. Record
negative positions rather than hiding them in an aggregate. See
[validation notes](docs/VALIDATION.md) for accepted tradeoffs and evidence limits.

## Optional specialist ownership

When the user requests agents, keep evaluator, search, testing, analysis and
release scopes separate. Testers/analysts validate without editing production
source. Release agents stage only approved files and never alter engine logic.
Preferred models, when available: GPT Terra with medium reasoning for testing
and game analysis; GPT Luna with high reasoning for evaluator/search/release.
Report any model substitution. The coordinator owns plans and concise docs.
