# MutluBot

MutluBot is a small, from-scratch UCI chess engine written in Java, built around 64-bit
bitboards. It's meant as a clean foundation to build a stronger engine on
top of — every component (move generation, evaluation, search) is in its
own file and deliberately kept simple so you can read it end to end.

It's verified correct: the move generator's node counts match the standard
[chessprogramming.org perft test suite](https://www.chessprogramming.org/Perft_Results)
exactly, including deep positions that exercise castling, en passant, and
promotions (see "Testing" below).

## What's here

```
src/engine/
  Bitboards.java         Bitboard constants + precomputed attack tables (knight, king,
                          pawn, and ray tables used for sliding-piece attacks)
  Piece.java              Piece/color constants
  Move.java                Move encoding (moves are packed into a single int)
  Board.java                Board state: bitboards, FEN parsing/export, make/unmake move,
                          Zobrist hashing, check detection
  MoveGenerator.java     Pseudo-legal move generation + legal-move filtering
  MoveList.java             Lightweight growable move list (avoids boxing/ArrayList overhead)
  Zobrist.java              Random keys for Zobrist hashing
  Evaluator.java             Static evaluation: material + piece-square tables
  TranspositionTable.java  Hash table caching search results
  Search.java                Iterative deepening alpha-beta search with TT, killers,
                          history heuristic, null-move pruning, quiescence search
  Perft.java                Perft (move generator correctness/speed testing) utility
  UCIEngine.java           The UCI protocol loop — this is the executable entry point

build.sh / build.bat     Build scripts (Linux/Mac and Windows)
```

## How the pieces fit together

- **Bitboards.java** has no dependency on anything else — it's pure precomputed
  tables and bit-twiddling helpers (`popcount`, `lsb`/`msb`, sliding attack
  generation via the classic "ray + first blocker" technique).
- **Board** owns the actual game state and is the only class that mutates
  bitboards. `makeMove`/`unmakeMove` are the hot path for search — they're
  written to be correct first, fast second.
- **MoveGenerator** only reads a `Board`; it never mutates it directly (legal-move
  filtering works by calling `board.makeMove()`/`unmakeMove()` internally and
  checking `isInCheck()`).
- **Search** ties `MoveGenerator`, `Evaluator`, and `TranspositionTable` together.
  It reports progress through a small `InfoListener` callback interface so
  `UCIEngine` can turn each completed depth into a UCI `info` line.
- **UCIEngine** is the only class that talks to stdin/stdout. Everything else is
  UI-agnostic, so you could drive `Board`/`Search` from a GUI, a test harness,
  or a different protocol without touching them.

## Building

Requires a JDK (11+). From the project folder:

```bash
./build.sh        # Linux/Mac
build.bat         # Windows
```

This produces `dist/chess-engine.jar`. There's no external dependency —
everything is plain `java.*`.

## Running it manually

```bash
java -jar dist/chess-engine.jar
```

It just sits there waiting for UCI commands on stdin. Try typing:

```
uci
position startpos
go depth 6
```

You should see a series of `info depth ...` lines followed by `bestmove ...`.
Other useful commands while poking at it directly:

- `d` — prints the current board and FEN (not part of UCI, just a debug helper)
- `perft 5` — runs a perft test from the current position (also a debug helper)

## Testing

`Perft.java` has a `main` you can run directly:

```bash
java -cp out engine.Perft 5
# Perft(5) = 4865609  (... ms, ... nps)
```

Pass a FEN as a second argument to test other positions, e.g. the "Kiwipete"
stress-test position:

```bash
java -cp out engine.Perft 5 "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"
# Perft(5) = 193690690
```

If you change anything in `Board` or `MoveGenerator`, re-run perft on a few of
the standard test positions (search "chessprogramming.org Perft Results" for
the full list) before trusting the search results — it's the fastest way to
catch a move generation bug.

The static-exchange tests cover winning and losing captures, pins, king
recaptures, en passant, and promotion:

```bash
javac --release 8 -cp out -d out test/engine/StaticExchangeTest.java
java -cp out engine.StaticExchangeTest
```

## Setting it up in SCID vs PC

**If the engine "crashes" or does nothing the moment SCID tries to run it,
check your Java version first.** The prebuilt jar in this package is now
compiled to target Java 8 bytecode specifically so it runs on old and new
JREs alike. If you ever rebuild it yourself and see errors like
`UnsupportedClassVersionError`, it means the jar was built for a newer Java
version than the one actually installed — run `java -version` and make sure
it's Java 8 or newer, and make sure `build.sh`/`build.bat` are using
`javac --release 8` (that's already the default in the scripts here).

**If Java itself is fine and it still fails specifically inside SCID (but
works when you run it manually in a terminal):** that points to a separate,
known issue — SCID vs PC is built on Tcl/Tk, and Tcl's process-launching
code doesn't reliably pipe stdin/stdout to `.bat`/`.cmd` files on Windows —
it falls back to temp-file redirection for batch scripts, which breaks UCI,
since UCI needs live, real-time, bidirectional communication. The fix is to
give SCID a genuine native `.exe` instead. See "Recommended: build a real
.exe" below.

### Recommended: build a real .exe with jpackage

Any JDK 14+ ships with `jpackage`, which can bundle the jar (plus its own
private Java runtime — the target machine won't even need Java installed
separately) into a real, native, double-clickable Windows executable. This
sidesteps the `.bat` piping problem entirely because SCID launches it as a
normal process, no shell involved.

1. Make sure you have a JDK 14+ (check with `java -version`).
2. Run `build-native-exe.bat` (double-click it, or run it from a terminal).
   It builds the jar if needed, then runs jpackage for you.
3. It produces `native\ChessEngine\ChessEngine.exe`. Point SCID vs PC at
   that file (**Tools → Analysis Engines... → New**, browse to it, protocol
   UCI).

That's it — no arguments field, no wrapper script, no shell quirks. If you
ever change the source, just re-run `build-native-exe.bat`.

If `jpackage` isn't found, it means your JDK is older than 14 — install a
recent one (e.g. [Eclipse Temurin](https://adoptium.net/)) and make sure
it's the one on your PATH.

### Alternative: the `.bat` wrapper

`dist/chess-engine.bat` is still included for GUIs that *do* handle `.bat`
launching correctly (Arena, for instance, generally has no trouble with it).
It's a one-line wrapper: `java -jar "%~dp0chess-engine.jar" %*`. If you want
to sanity-check it outside of any GUI first, open Command Prompt, `cd` into
the `dist` folder, run `chess-engine.bat`, and type `uci` followed by Enter
— you should see `uciok` come back immediately. If that works manually but
still fails inside SCID specifically, that's the Tcl piping issue described
above, and the jpackage `.exe` is the fix.

**Note on strength**: this engine plays *legal, reasonably sensible* chess,
but it's intentionally simple (see below) — think "solid amateur", not
"stockfish-competitive". That's the point: it's a foundation, not a finished
product.

## What's intentionally simple (i.e. where to improve it)

This engine covers the essentials — bitboards, full legal move generation,
alpha-beta with a transposition table, iterative deepening, and a working
UCI loop — but plenty is left on the table on purpose:

- **Evaluation** is material + static piece-square tables only. Big wins
  available: tapered eval (blend separate midgame/endgame PSTs by game
  phase), pawn structure (passed/isolated/doubled pawns), king safety,
  mobility, rook-on-open-file, bishop pair tuning, etc. `Evaluator.java` has
  a comment block listing these.
- **Sliding piece attacks** use the classic ray + "first blocker via bitscan"
  technique (correct and reasonably fast — several million nps), not magic
  bitboards. Magic bitboards are the standard next step for real speed —
  look up "magic bitboards" on chessprogramming.org when you're ready; the
  interface (`Bitboards.rookAttacks(sq, occupancy)` /
  `Bitboards.bishopAttacks(sq, occupancy)`) is already shaped so you can swap
  the implementation without touching any caller.
- **Search** has null-move pruning, a simple form of PVS with late-move
  reductions, killer moves, history heuristic, 25-centipawn aspiration windows,
  and SEE/delta pruning for quiescence captures. Regular capture ordering still
  uses MVV-LVA; futility/razoring pruning and true multi-PV are not implemented.
  The `pv`
  reported in `info` lines is reconstructed by walking the transposition
  table after each iteration rather than stored in a dedicated PV table —
  simple and correct, but a real PV table would be more robust.
- **Single-threaded.** Lazy SMP (running several threads searching the same
  position, sharing the transposition table) is the standard way to add
  multi-threading to an alpha-beta engine and wouldn't require restructuring
  much.
- **No opening book or endgame tablebases.**
- **Repetition detection** combines the actual game history (passed in from
  the UCI `position` command) with the current search line, but treats any
  repeated position as a draw (a common simplification) rather than strictly
  implementing the "third occurrence" rule.
- **Time management** is a simple `time / movestogo + 0.8*increment` formula.
  It works but doesn't adapt to how stable the best move is between
  iterations (an easy improvement: extend the budget if the best move keeps
  changing at the last couple of depths, or if the score just dropped a lot).

Everything above is a place you can dig in without needing to understand the
whole codebase first — that's the intent of keeping the files small and
single-purpose.

## Move encoding, if you're reading Search.java or MoveGenerator.java

Moves are packed into a single `int` (see the comment at the top of
`Move.java`): 6 bits "from" square, 6 bits "to" square, 4 bits of flags that
distinguish quiet moves, captures, castling, en passant, and the four
promotion types (with a separate flag for promotion + capture). This is the
same compact scheme used by most bitboard engines, and it means a `MoveList`
is just an `int[]` — no allocation per move.
