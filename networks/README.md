# Pilot 2

`pilot2.nnue` is our own trained MutluBot network, published for inference on
the `mutlubotNNUE` branch. It is the same network used in the author's Pilot 2
matches, not a replacement made from another engine's pretrained weights.

- SHA-256: `ce70ca5e6a1189e7e7ae3b2d2860040d57a1c3a8e0e0c5c0a584d93d31a3976d`.
- File size: 197,664 bytes; own `MUTLUN01` format.
- Architecture: 768 piece/color/square inputs, shared 128-unit transformer
  evaluated from both color perspectives, CReLU, one input bucket, one output.
- Quantization: QA=255, QB=256; output is centipawns from the side to move.
- Training corpus: 2,584,890 training and 644,667 validation positions.
  Positions came from the project's user/tournament-game labeling pipeline,
  including TCEC. Stockfish supplied evaluation labels, not deployed weights.
- The selected original run completed 30 epochs. Validation MAE was
  approximately 73.06 centipawns. That is static label disagreement, not a
  playing-strength or Elo measurement.

`pilot2-parity.tsv` contains FENs and this exported network's integer outputs
for compatibility tests. These are NNUE predictions, not fresh teacher labels.
Its line endings are fixed to LF so its checksum is the same on every platform.
`pilot2.json` records hashes and minimal provenance. Training datasets and
optimizer checkpoints are not required to run the engine and are not bundled.

## Load and verify

Build with `bash build.sh` on macOS/Linux or `build.bat` on Windows. Run the
resulting `dist/chess-engine.jar`, then send:

```text
uci
setoption name EvalFile value /absolute/path/to/MutluBotNNUE/networks/pilot2.nnue
setoption name UseNNUE value true
isready
position startpos
go movetime 1000
```

Replace the path with the real absolute local path; `~` is not expanded by
the engine's UCI parser. Verify `info string evaluator NNUE v1 hidden 128
activation CReLU input-buckets 1 sha256 ...` with the hash above. Wait for
`bestmove` before sending `quit`. `UseNNUE=false` selects the original HCE.
No Python/PyTorch dependency is needed for inference.

## Evidence limits

Pilot 2 scored 24-2-6 (78.125%) in a 32-game, color-reversed development screen
at 5+0.05 against matched-source HCE. An identical-weight repeat scored 23-5-4
on the same roots. Repeated roots are not new independent evidence. Full
frozen two-opponent confirmation is pending; no certified Elo claim is made.
The SCReLU and four-bucket architectures remain experiments, not selected
replacement weights. See [NNUE details](../docs/NNUE.md).
