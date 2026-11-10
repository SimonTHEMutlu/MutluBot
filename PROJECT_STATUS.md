# Engine handoff

This is the engine source checkpoint prepared for the author's independent
development on November 10, 2026. No further development or background game
campaign is scheduled by this handoff.

## Included

- Existing UCI lifecycle, pondering, clock-budget and legal-move/PV safeguards.
- Incremental material/PST/phase bookkeeping, a bounded pawn cache with dynamic
  occupancy/king-dependent terms kept outside the cached structure, packed
  position storage, legal-move existence checks and validated LMR infrastructure.
- Guarded reverse futility pruning, enabled by default with an easy UCI switch.
- Canonical en-passant repetition keys and halfmove-aware TT score validity.
- Focused correctness tests, behavioral/performance sentinels and benchmarks.

`ProphylaxisProbe` defaults to false. RFP defaults to true; its margin remains
100 cp per ply. The engine is single-threaded. Hash is a UCI option; set it
explicitly for comparisons rather than relying on a GUI's prior settings.

The rule-50 TT fix was retained by explicit user choice despite a measured
local Kiwipete slowdown. See [validation notes](docs/VALIDATION.md). Do not
interpret this source checkpoint as an Elo certification.

## Not included or deferred

- The mate-distance-pruning prototype remains an isolated experiment, not an
  active engine feature.
- Basic dead-material recognition was explicitly deferred by the author.
- Lichess adapter changes, account configuration, opening books and launch
  scripts are private testing infrastructure, not engine source.
- Raw local experiment snapshots, compiled outputs and native packages are not
  part of the repository. Concise accepted-result summaries are included instead.

`feedback.md` and `issues.md` preserve historical engineering reviews. Some
recommendations in them predate the included improvements; use executable
source/tests and this status file to establish what is actually implemented.

## Running the source

Build with `build.bat` (Windows) or `build.sh`, then run
`java -jar dist/chess-engine.jar`. Compilation targets Java 8 bytecode; the
recorded checks use JDK 25, which is not a Java 8 runtime compatibility test.

The source handoff does **not** update an existing local JAR or native executable.
Rebuild before expecting a GUI to use these source changes. Generated binaries
are deliberately excluded from Git. See [AGENTS.md](AGENTS.md) for a portable
validation command and correctness invariants.
