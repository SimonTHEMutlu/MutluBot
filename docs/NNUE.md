# MutluBot's own NNUE

This branch adds an optional NNUE evaluator to the existing alpha-beta engine.
The network architecture and training are ours. No third-party trained network
is substituted. Stockfish is a label teacher, not the evaluator shipped here.
Our published Pilot 2 weights and integer parity fixtures are in `networks/`.
Training datasets, checkpoints and large experiment evidence remain outside
the repository. See [Pilot 2 model information](../networks/README.md).

## Inference

The current model uses 768 piece/color/square inputs, a shared 128-unit feature
transformer evaluated from both color perspectives, and one score output. Widths
1–512 are supported by the loader. V1 uses clipped ReLU; the V2 experiment uses
squared clipped ReLU. V3 adds four own-king input buckets without changing
width, output heads or search. Castling, captures, en passant, promotions, null moves and
full undo are covered by real-network parity/update tests.

Board changes accumulate bounded feature deltas. Changes made and immediately
undone during legality generation cancel before wide vectors are updated. The
delta is applied at evaluation, without per-node object allocation. Internal
accumulator inspection must flush first; ordinary callers use Evaluator.evaluate.

- `MUTLUN01`: 8-byte magic, big-endian int32 hidden/QA/QB/base-features.
- `MUTLUN02`: same fields, followed by int32 activation/input-buckets/output-heads.
  The current implementation accepts activation 0=CReLU or 1=SCReLU, one input
  bucket and one output head. Unsupported future architectures are rejected.
- `MUTLUN03`: V2 header with exactly four input buckets and one output head,
  followed by int32 king-map id 1. Orient each perspective by rank reflection;
  ranks 1-2/files a-c select bucket 0, d-e bucket 1, f-h bucket 2; ranks 3-8
  select bucket 3. Each perspective uses its own king. A bucket crossing
  rebuilds only that perspective at evaluation, including pending piece deltas.
- Bias: hidden int32 values. Input weights: buckets*768*hidden int16 values. Output
  weights: 2*hidden int16 values. Output bias: one int64 value. Trailing bytes,
  truncation, invalid dimensions/scales or unbounded biases are rejected.
- QA=255, QB=256. CReLU cp is `100*(sum(a*w)+bias)/(QA*QB)`.
  SCReLU cp is `100*(sum(a*a*w)+bias*QA)/(QA*QA*QB)`.
  Accumulation uses wide integers and division truncates toward zero. Results
  are bounded below the search's mate-score range.

The trained export's SHA256 is reported when NNUE is active. New versions must
use explicit format/version dispatch and pass exact Python/Java parity. A
Stockfish `.nnue` file is incompatible with these formats.

## UCI usage

```text
uci
setoption name Hash value 64
setoption name EvalFile value /absolute/path/to/MutluBotNNUE/networks/pilot2.nnue
setoption name UseNNUE value true
isready
position startpos
go movetime 1000
```

Look for `info string evaluator NNUE ... sha256 ...`. `UseNNUE=false` selects
HCE; there is no HCE blending while a network is active. NNUE requested without
a loaded file is explicitly reported as HCE. A failed file load retains the
previous working evaluator. Loading/enabling/disabling joins any active search
and clears old TT bounds. Hash/new-game/position commands preserve the chosen
evaluator, including the copied search board. Empty EvalFile unloads the net.

Default is HCE until a network is explicitly loaded and enabled. Existing user
JARs, native bundles and Scid entries are not replaced by experimental builds.
For Pilot 2, the active SHA-256 must be
`ce70ca5e6a1189e7e7ae3b2d2860040d57a1c3a8e0e0c5c0a584d93d31a3976d`.
An NNUE option being advertised does not by itself establish activation.

## CPU training

`training/nnue_lab.py` and helpers restore our earlier prototype pipeline with
explicit activation/version, learning-rate schedule, optimizer reset and
checkpoint selection. Dependencies: PyTorch, NumPy and python-chess. Four
training threads are the current local operating preference.

Training accepts JSONL or our hashed packed-array format. The packed dataset
is checked before use. Per-epoch validation logs both transformed-score MSE and
raw cp MSE/MAE, phase/side errors, LR and optimizer step counts. The selection
metric, source code, data hashes, seed and settings are part of the resume
protocol. Resume rejects changed inputs. Changing activation requires explicit
opt-in and resetting optimizer state; this is not a silent compatible resume.

Four-bucket training uses a shared embedding plus zero-initialized bucket
residuals. A one-to-four-bucket transfer with unchanged activation preserves the
float function; it requires `--input-buckets 4 --allow-bucket-transfer
--reset-optimizer`. Export merges the shared and residual vectors before
quantization, so runtime has one matrix lookup per piece. A trained checkpoint
records its bucket count; the data format stays unchanged. Confirmed strength
for this architecture remains to be measured.

Best checkpoints are saved atomically before exporting the corresponding net.
Network exports also use a flushed temporary file and atomic replacement with
bounded Windows sharing retries. Interrupted writes preserve the previous
complete network; temporary export files are cleaned up.
Export is regenerated from the selected checkpoint at completion. Checkpoints
save exact deterministic batch continuation and retain initial weights in
selection. Training fit or higher NPS alone is not proof of playing strength.

The current campaign freezes pilot2, actual Scid HCE and matched-source HCE
separately. Candidate training and game snapshots are immutable during tests.
Final acceptance uses previously frozen, color-reversed opening pairs with
clock/process logs and declared statistical rules; development wins do not
complete the strength goal. Do not train on reserved acceptance roots/results.
No claim of superiority over HCE has been established yet.

## Checks

Compile all source/tests to a separate directory, preserving existing packages.
Run all assertion programs, both depth-5 perfts, evaluation/cache/repetition
tests and UCI suites as required by AGENTS.md. Additional focused commands:

```text
java -ea -cp <classes> engine.NnueTest <our-network.nnue> <parity.tsv>
java -ea -cp <classes> engine.UciNnueProtocolTest
java -ea -cp <classes> engine.NnueSearchTest <our-network.nnue>
python -m unittest discover -s training -p "test_*.py"
python test/tools/verify_nnue_proofs.py
```

Synthetic networks in tests are only deterministic format/arbiter fixtures and
never enter the chess-strength training corpus or become a deployed candidate.
