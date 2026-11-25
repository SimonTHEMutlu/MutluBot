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

- This branch develops our own NNUE; never replace it with an outside trained
  network. Preserve HCE and frozen pilot weights as comparison baselines.
- NNUE activation/file changes must stop/join search, propagate to the search
  board, clear stale TT bounds and report the active network hash. Failed loads
  are transactional. Keep v1 score compatibility and version new architectures.
- Flush pending NNUE feature deltas before inspecting accumulator arrays.
  No per-node NNUE allocation; verify full-refresh/move/undo parity for all
  special moves, deferred updates and network/FEN resets.
- V3 king-bucket map is fixed: oriented ranks 1-2 have a-c/d-e/f-h buckets,
  ranks 3-8 share the advanced-king bucket. Rebuild only crossed perspectives;
  never apply pending deltas twice after a rebuild. Bucket transfer starts from
  our own shared weights plus zero residuals and requires optimizer reset.
- Training and acceptance datasets are separate. Reserved confirmation opening
  roots/results must not become training examples. Playing strength requires
  frozen paired games and declared confidence, not training loss or NPS alone.

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
The default network/parity pair below is the published own Pilot 2 export.
For another own-network experiment, replace both paths with a matching pair.

```powershell
javac --release 8 -d verify-out src\engine\*.java test\engine\*.java
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
$nnueTestNetwork = (Resolve-Path 'networks\pilot2.nnue').Path
$nnueTestParity = (Resolve-Path 'networks\pilot2-parity.tsv').Path
if (!(Test-Path -LiteralPath $nnueTestNetwork) -or !(Test-Path -LiteralPath $nnueTestParity)) {
    throw 'Set the network and parity paths to a matching own-network export'
}
Get-ChildItem test\engine -Filter '*Test.java' | ForEach-Object {
    $testArguments = @()
    if ($_.BaseName -eq 'NnueTest') { $testArguments = @($nnueTestNetwork, $nnueTestParity) }
    if ($_.BaseName -eq 'NnueSearchTest') { $testArguments = @($nnueTestNetwork) }
    java -ea -cp verify-out ("engine." + $_.BaseName) @testArguments
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
