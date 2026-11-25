# Engine handoff

The `mutlubotNNUE` branch adds our own NNUE evaluator and publishes Pilot 2.
The original HCE evaluator remains available through `UseNNUE=false`.
This branch's source does not replace existing installed JARs automatically.

## Included

- Own dual-perspective integer NNUE, Pilot 2 weights and exact integer parity
  fixtures. Explicit UCI activation reports the network hash, survives search
  board copies/new games/Hash changes and clears obsolete TT bounds.
- Deferred feature-delta updates, versioned CReLU/SCReLU/four-king-bucket
  architectures and CPU training/export/resume tools. Experimental alternative
  networks have not demonstrated an improvement over Pilot 2.
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
  part of the repository. The small published Pilot 2 inference weights are
  included; large datasets and training checkpoints are not.

Pilot 2 scored 24 wins, 2 draws and 6 losses in a 32-game development screen
against matched-source HCE at 5+0.05. An identical-weight repeat on the same
opening roots scored 23-5-4; these are not independent opening samples or an
Elo certification. Full two-opponent confirmation remains pending.

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
