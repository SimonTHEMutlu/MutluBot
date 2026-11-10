# Validation and accepted tradeoffs

These are concise summaries of the local validation archive, not claims of
universal speed gains or a certified Elo rating. Raw JVM outputs, immutable
snapshots, clocks and process manifests remain in the author's local
`MutluBOT2/issue-validation` archive; they are not dependencies for building or
running the checked-in assertion suites.

## RFP

The frozen 100 cp-per-ply, TT-safe RFP implementation was accepted as a
**game-test-positive practical improvement**. It remains guarded and can be
disabled with `setoption name ReverseFutilityPruning value false`.

The amended 200-game regression screen scored 96 wins, 29 draws and 75 losses:
110.5/200, or 55.25%. Its exploratory opening-cluster 95% Student-t interval was
50.76%-59.74%; a conservative Hoeffding interval was 36.04%-74.46%. Timeout
scoring was amended after a baseline flag, so this is not a preregistered
strength certification and does not prove +37 Elo. Three baseline flags included
two draws against a bare king; no engine crash or illegal move occurred in the
amended screen. Two Scid spectator wins are excluded from the 200-game result.

The targeted seven-pair fixed-depth confirmation was incomplete. In the exposed
synthetic active-king rook ending, the frozen RFP candidate searched 70,493,140
nodes at depth21 versus 38,862,724 without RFP, both at +1 cp. This substantial
local regression is preserved in
[`search-performance-regressions.tsv`](../test/resources/search-performance-regressions.tsv)
and exercised by `SearchRfpActiveKingRegressionTest`. Its default depth8 test
checks hard invariants and counter parity; `--deep` reports depth21 behavior.
It does not require one historical move or a fixed runtime.

Do not retune RFP just to repair that fixture. A future independent confirmation
must use fresh openings and freeze clock/adjudication rules before play.

## Repetition and rule-50 TT fixes

October 30 validation retained two correctness fixes:

1. EP contributes to repetition identity only when a legal EP capture exists.
   Raw FEN serialization remains unchanged. The review's complete knight-cycle
   sequence now exits as a threefold root draw with one node instead of missing
   the first occurrence and searching 50 nodes.
2. TT scores and exact-PV continuation require matching halfmove-clock metadata.
   The controlled same-generation clock0-to-clock99 case now agrees with the
   fresh-table draw instead of returning the old +535 cp score. Move hints can
   still be used across a clock mismatch. Entries remain 16 bytes; generation
   uses 14 bits and clears before wrapping.

All 25 assertion programs and both depth-5 perfts passed on the retained source.
The 21,345-position evaluation fingerprint remains `efee4e365a49278`, using an
independent reconstruction of historical raw-EP identity so the evaluator's
approved baseline is not silently replaced. No evaluation weights changed.
Independent python-chess checks agreed on 250 EP-bearing positions (22 legal
EP opportunities), and seven real UCI history/clock cases passed.

The retained first combined version was compared in nine fresh alternating
fixed-depth pairs, Hash256, probeOFF, RFP100 ON, detailed timing counters OFF:

| Position | Depth | Geometric time C/B | Nodes C/B | NPS C/B |
| --- | ---: | ---: | ---: | ---: |
| Start | 14 | 0.91198 | 0.94716 | 1.03854 |
| Kiwipete | 13 | 1.07374 | 1.05867 | 0.98593 |
| Active-king rook | 20 | 0.99662 | 1.00428 | 1.00770 |

The combined geometric time ratio was 0.99191, but this does not hide the
**7.37% Kiwipete slowdown**. The author explicitly chose to retain TT correctness
despite that local cost. Two later implementation variants passed correctness
but performed worse and were not retained. No new game-strength claim follows.

Dead-material recognition was explicitly deferred and removed. The separate
mate-distance-pruning prototype passed initial mate tests but was not promoted.

## Fixture interpretation

Structural FEN/history/PV legality, board restoration, terminal results and
independently proved tactical answers are correctness gates. Historical preferred
moves without a complete independent proof are behavioral sentinels. Corpus
validation is not proof that every pending move preference is currently chosen.
The optional `--legacy-choice-gate` restores old preference assertions but does
not turn them into tactical truth.

## Source handoff checks

The November 10 handoff passed all 25 assertion programs and both depth-5
perfts in a fresh isolated class directory. The engine Java/TSV inputs match
the exact accepted October 30 source manifest; no new engine logic is
introduced by the release. Generated packages were not rebuilt or committed.

The first handoff check run was interrupted by a server restart and is retained
locally as incomplete evidence. A fresh run is used for the final release result.
See [AGENTS.md](../AGENTS.md) for portable verification commands.
