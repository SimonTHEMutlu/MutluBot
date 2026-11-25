"""Small CPU NNUE prototype. Synthetic timing is NOT a chess-strength test."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import random
import struct
import tempfile
import time

import chess
import chess.engine
import numpy as np
import torch
from torch import nn
from checkpoint_io import replace_with_retry
from training_controls import validate_settings, learning_rate, load_phases, training_loss, phase_metrics

PAD = 768
QA, QB = 255, 256


def features(board):
    result = np.full((2, 32), PAD, dtype=np.int64)
    for i, (square, piece) in enumerate(sorted(board.piece_map().items())):
        color = 0 if piece.color == chess.WHITE else 1
        kind = piece.piece_type - 1
        result[0, i] = (color * 6 + kind) * 64 + square
        result[1, i] = ((1 - color) * 6 + kind) * 64 + (square ^ 56)
    return result


def king_buckets(x):
    """v3 map id 1, using each perspective's already-oriented own king."""
    square = torch.where((x >= 320) & (x < 384), x - 320, 0).sum(dim=-1)
    file = square % 8
    return torch.where(square >= 16, 3, torch.where(file <= 2, 0, torch.where(file <= 4, 1, 2)))


class Network(nn.Module):
    def __init__(self, hidden=128, activation="crelu", input_buckets=1):
        super().__init__()
        if input_buckets not in (1, 4):
            raise ValueError("Only one or four input buckets are supported")
        self.hidden = hidden
        self.activation = activation
        self.input_buckets = input_buckets
        self.embedding = nn.EmbeddingBag(769, hidden, mode="sum", padding_idx=PAD)
        if input_buckets == 4:
            self.bucket_embedding = nn.EmbeddingBag(3073, hidden, mode="sum", padding_idx=3072)
            nn.init.zeros_(self.bucket_embedding.weight)
        self.bias = nn.Parameter(torch.full((hidden,), 0.2))
        self.output = nn.Linear(hidden * 2, 1)
        nn.init.normal_(self.embedding.weight, std=0.01)
        with torch.no_grad():
            self.embedding.weight[PAD].zero_()
        nn.init.normal_(self.output.weight, std=0.05)
        nn.init.zeros_(self.output.bias)

    def forward(self, x, stm):
        h = self.embedding(x.reshape(-1, 32)).reshape(-1, 2, self.hidden)
        if self.input_buckets == 4:
            indices = torch.where(x == PAD, 3072, x + king_buckets(x).unsqueeze(-1) * 768)
            h = h + self.bucket_embedding(indices.reshape(-1, 32)).reshape(-1, 2, self.hidden)
        h = torch.clamp(h + self.bias, 0.0, 1.0)
        if self.activation == "screlu":
            h = h.square()
        rows = torch.arange(x.shape[0])
        ordered = torch.cat((h[rows, stm], h[rows, 1 - stm]), dim=1)
        return self.output(ordered).squeeze(1)


def write_json(path, result):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result), flush=True)


def environment():
    return {"python": platform.python_version(), "torch": torch.__version__,
            "numpy": np.__version__, "cpu": platform.processor(),
            "logical_cpus": os.cpu_count()}


def benchmark(args):
    torch.manual_seed(5101)
    results = []
    # Includes embedding gradients and optimizer; no fake dense-only throughput.
    for threads in (1, 4, 8):
        torch.set_num_threads(threads)
        model = Network(args.hidden)
        optimizer = torch.optim.Adam(model.parameters(), lr=0.001)
        x = torch.randint(0, 768, (args.batch, 2, 32))
        y = torch.randn(args.batch)
        stm = torch.randint(0, 2, (args.batch,))
        def step():
            optimizer.zero_grad(set_to_none=True)
            loss = torch.nn.functional.mse_loss(model(x, stm), y)
            loss.backward()
            optimizer.step()
        for _ in range(10):
            step()
        started = time.perf_counter()
        steps = 0
        while time.perf_counter() - started < args.seconds:
            step()
            steps += 1
        elapsed = time.perf_counter() - started
        record = {"threads": threads, "hidden": args.hidden, "batch": args.batch,
                  "steps": steps, "seconds": elapsed,
                  "examples_per_second": steps * args.batch / elapsed}
        results.append(record)
        print(json.dumps(record), flush=True)
    write_json(args.out, {"environment": environment(), "kind": "synthetic throughput only",
                          "results": results})


OPENINGS = [
    "d2d4 d7d5 c2c4 e7e6 b1c3 g8f6", "e2e4 e7e5 g1f3 b8c6 f1c4 f8c5",
    "e2e4 c7c5 g1f3 e7e6 d2d4 c5d4 f3d4", "d2d4 g8f6 c2c4 e7e6 b1c3 f8b4",
    "e2e4 c7c6 d2d4 d7d5 e4e5 c8f5", "c2c4 e7e5 b1c3 g8f6 g2g3",
    "g1f3 d7d5 g2g3 c7c5 f1g2", "e2e4 e7e6 d2d4 d7d5 b1c3",
]


def generate(args):
    rng = random.Random(args.seed)
    exe = Path(args.teacher).resolve(strict=True)
    started = time.perf_counter()
    count, game = 0, 0
    path = Path(args.out)
    path.parent.mkdir(parents=True, exist_ok=True)
    # Never overwrite an earlier dataset/evidence file.
    with path.open("x", encoding="utf-8") as f, chess.engine.SimpleEngine.popen_uci(str(exe)) as engine:
        engine.configure({"Threads": 1, "Hash": 64})
        while count < args.positions:
            board = chess.Board()
            for move in OPENINGS[game % len(OPENINGS)].split():
                board.push_uci(move)
            game_id = game
            game += 1
            for ply in range(120):
                if board.is_game_over(claim_draw=True):
                    break
                info = engine.analyse(board, chess.engine.Limit(nodes=args.nodes))
                score = info["score"].pov(board.turn)
                if (not board.is_check() and not score.is_mate()
                        and abs(score.score()) <= 1500 and info.get("pv")
                        and not board.is_capture(info["pv"][0])
                        and not info["pv"][0].promotion):
                    # Split entire games, never random neighboring positions.
                    record = {"fen": board.fen(), "cp": score.score(), "game": game_id,
                              "split": "validation" if game_id % 5 == 4 else "train"}
                    f.write(json.dumps(record) + "\n")
                    f.flush()
                    count += 1
                    if count >= args.positions:
                        break
                if ply < 12 and rng.random() < 0.2:
                    move = rng.choice(list(board.legal_moves))
                else:
                    move = info["pv"][0]
                board.push(move)
            print(f"generated {count}/{args.positions}, games={game}", flush=True)
    write_json(str(path) + ".metadata.json", {"teacher": str(exe),
        "teacher_sha256": hashlib.sha256(exe.read_bytes()).hexdigest(), "nodes_per_label": args.nodes,
        "positions": count, "games": game, "seconds": time.perf_counter() - started,
        "seed": args.seed, "dataset_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "limits": "small pilot, correlated opening families, not strength/acceptance evidence"})


def load_data(path):
    if Path(path).is_dir():
        from packed_dataset import load_packed
        return load_packed(path)
    data = {"train": [], "validation": []}
    seen = {}
    duplicate_conflicts = 0
    records = [json.loads(line) for line in Path(path).read_text().splitlines() if line.strip()]
    # Exclude normalized duplicate positions across the two splits.
    validation_keys = {" ".join(r["fen"].split()[:4]) for r in records if r["split"] == "validation"}
    for r in records:
        key = " ".join(r["fen"].split()[:4])
        if r["split"] == "train" and key in validation_keys:
            duplicate_conflicts += 1
            continue
        split_key = (r["split"], key)
        if split_key in seen:
            continue
        seen[split_key] = True
        b = chess.Board(r["fen"])
        data[r["split"]].append((features(b), 0 if b.turn else 1, r["cp"] / 100.0))
    tensors = {}
    for split, rows in data.items():
        if not rows:
            raise ValueError(f"No {split} examples; generate at least five games")
        tensors[split] = (torch.from_numpy(np.stack([r[0] for r in rows])),
                          torch.tensor([r[1] for r in rows]),
                          torch.tensor([r[2] for r in rows], dtype=torch.float32))
    return tensors, duplicate_conflicts


def quantized(model):
    state = model.state_dict()
    def q(t, scale, dtype):
        rounded = np.rint(t.detach().numpy() * scale)
        limits = np.iinfo(dtype)
        if not np.all(np.isfinite(rounded)) or np.any(rounded < limits.min) or np.any(rounded > limits.max):
            raise ValueError("Nonfinite/out-of-range network quantization; refusing export")
        return rounded.astype(dtype)
    embedding_float = state["embedding.weight"][:768]
    if model.input_buckets == 4:
        embedding_float = embedding_float.unsqueeze(0) + state["bucket_embedding.weight"][:3072].reshape(4, 768, model.hidden)
    embedding = q(embedding_float.reshape(-1, model.hidden), QA, np.int16)
    bias = q(state["bias"], QA, np.int32)
    output = q(state["output.weight"].reshape(-1), QB, np.int16)
    output_bias = int(round(state["output.bias"].item() * QA * QB))
    if np.any(np.abs(bias.astype(np.int64)) > 1000000) or abs(output_bias) > 1000000000:
        raise ValueError("Export exceeds Java loader bounds")
    return embedding, bias, output, output_bias


def export(model, path):
    embedding, bias, output, output_bias = quantized(model)
    destination = Path(path)
    descriptor, name = tempfile.mkstemp(prefix=destination.name+".", suffix=".tmp", dir=destination.parent)
    temporary = Path(name)
    try:
        with os.fdopen(descriptor, "wb") as f:
            f.write(b"MUTLUN03" if model.input_buckets == 4 else b"MUTLUN02" if model.activation == "screlu" else b"MUTLUN01")
            f.write(struct.pack(">4i", model.hidden, QA, QB, 768))
            if model.activation == "screlu" or model.input_buckets == 4:
                f.write(struct.pack(">3i", int(model.activation == "screlu"), model.input_buckets, 1))
            if model.input_buckets == 4:
                f.write(struct.pack(">i", 1))  # frozen king-bucket map id
            f.write(bias.astype(">i4").tobytes())
            f.write(embedding.astype(">i2").tobytes())
            f.write(output.astype(">i2").tobytes())
            f.write(struct.pack(">q", output_bias))
            f.flush()
            os.fsync(f.fileno())
        replace_with_retry(temporary, destination)
    finally:
        temporary.unlink(missing_ok=True)


def integer_evaluate(model, board, parameters=None):
    """Reference integer score for export parity, independent of float forward."""
    e, b, w, ob = quantized(model) if parameters is None else parameters
    f = features(board)
    buckets = king_buckets(torch.from_numpy(f).unsqueeze(0))[0].numpy() if model.input_buckets == 4 else [0, 0]
    accum = []
    for perspective in (0, 1):
        indices = f[perspective][f[perspective] != PAD] + int(buckets[perspective]) * 768
        accum.append(np.clip(b.astype(np.int64) + e[indices].astype(np.int64).sum(axis=0), 0, QA))
    side = 0 if board.turn else 1
    activation = np.concatenate([accum[side], accum[1-side]])
    divisor = QA * QB
    if model.activation == "screlu":
        activation = activation * activation
        raw = int(np.dot(activation, w.astype(np.int64))) + ob * QA
        divisor *= QA
    else:
        raw = int(np.dot(activation, w.astype(np.int64))) + ob
    cp = (abs(raw*100)//divisor) * (-1 if raw < 0 else 1)
    return max(-20000, min(20000, cp))


def train(args):
    validate_settings(args)
    torch.set_num_threads(args.threads)
    torch.manual_seed(args.seed)
    data, excluded = load_data(args.data)
    input_buckets = getattr(args, "input_buckets", 1)
    model = Network(args.hidden, args.activation, input_buckets)
    if input_buckets == 4:
        for values, _, _ in data.values():
            for start in range(0, len(values), 16384):
                block = values[start:start+16384]
                if not torch.all(((block >= 320) & (block < 384)).sum(dim=-1) == 1):
                    raise ValueError("King-bucket training requires exactly one own king per perspective")
    optimizer = torch.optim.Adam(model.parameters(), lr=args.lr_start)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    from packed_dataset import data_sha, parity_fens
    data_hash = data_sha(args.data)
    protocol = {"data_sha256":data_hash,"hidden":args.hidden,"epochs":args.epochs,
                "batch":args.batch,"threads":args.threads,"seed":args.seed,
                "resume_sha256": hashlib.sha256(Path(args.resume).read_bytes()).hexdigest() if args.resume else None}
    protocol.update({"lr_start": args.lr_start, "lr_end": args.lr_end,
                     "lr_schedule": args.lr_schedule, "opening_weight": args.opening_weight,
                     "phase_index_sha256": hashlib.sha256((Path(args.phase_index)/"coverage.json").read_bytes()).hexdigest() if args.phase_index else None,
                     "trainer_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                     "controls_sha256": hashlib.sha256(Path(__file__).with_name("training_controls.py").read_bytes()).hexdigest()})
    protocol.update({"activation":args.activation,"reset_optimizer":args.reset_optimizer,
                     "allow_activation_transfer":args.allow_activation_transfer,
                     "selection_metric":args.selection_metric})
    protocol.update({"input_buckets":input_buckets,"king_bucket_map":1 if input_buckets == 4 else 0,
                     "allow_bucket_transfer":getattr(args,"allow_bucket_transfer",False)})
    latest = out / "latest.pt"
    x, stm, y = data["train"]
    vx, vs, vy = data["validation"]
    phases = load_phases(args.phase_index, {"train": len(x), "validation": len(vx)}, data_hash)
    weight_mean = 1.0 if phases is None else 1.0 + (args.opening_weight-1.0)*float((phases["train"] == 0).double().mean())
    def validate():
        with torch.no_grad():
            predictions = torch.cat([model(vx[i:i+256], vs[i:i+256]) for i in range(0, len(vx), 256)])
            metrics = {"sigmoid_mse": torch.mean((torch.sigmoid(predictions/4.0)-torch.sigmoid(vy[:]/4.0))**2).item(),
                       "mse_pawns": torch.mean((predictions-vy[:])**2).item(),
                       "mae_cp": torch.mean(torch.abs(predictions-vy[:])).item()*100}
            if phases is not None:
                metrics["by_phase"] = phase_metrics(predictions, vy[:], phases["validation"], vs[:])
            return metrics
    def atomic_save(value, path):
        temporary = Path(str(path)+".tmp")
        torch.save(value, temporary)
        replace_with_retry(temporary,path)
    start_epoch, next_batch, total = 0, 0, 0.0
    history, elapsed_before = [], 0.0
    if latest.exists():
        saved = torch.load(latest, weights_only=True)
        if saved["protocol"] != protocol:
            raise ValueError("Training inputs/settings changed; cannot resume")
        model.load_state_dict(saved["model"])
        optimizer.load_state_dict(saved["optimizer"])
        start_epoch, next_batch, total = saved["epoch"], saved["next_batch"], saved["total"]
        history, initial, best = saved["history"], saved["initial"], saved["best"]
        retained = torch.load(out / "checkpoint.pt", weights_only=True)
        best = min(best, retained.get("selection_value", best))
        elapsed_before = saved["seconds"]
        print(f"Resuming epoch {start_epoch+1}, batch {next_batch}", flush=True)
    else:
        if args.resume:
            previous = torch.load(args.resume, weights_only=True)
            if previous["hidden"] != args.hidden:
                raise ValueError("Checkpoint architecture mismatch")
            source_activation = previous.get("activation", "crelu")
            if source_activation != args.activation and not args.allow_activation_transfer:
                raise ValueError("Activation transfer requires explicit --allow-activation-transfer")
            source_buckets = previous.get("input_buckets", 1)
            if source_buckets != input_buckets:
                if not (source_buckets == 1 and input_buckets == 4 and getattr(args,"allow_bucket_transfer",False)):
                    raise ValueError("Bucket transfer requires explicit --allow-bucket-transfer from one to four")
                if not args.reset_optimizer:
                    raise ValueError("Bucket transfer requires --reset-optimizer")
                missing, unexpected = model.load_state_dict(previous["model"], strict=False)
                if missing != ["bucket_embedding.weight"] or unexpected:
                    raise ValueError("Unexpected bucket-transfer checkpoint fields")
            else:
                model.load_state_dict(previous["model"])
            if "optimizer" in previous and not args.reset_optimizer:
                if source_activation != args.activation:
                    raise ValueError("Activation transfer requires --reset-optimizer")
                optimizer.load_state_dict(previous["optimizer"])
        initial = validate()
        best = initial[args.selection_metric]
        atomic_save({"model":model.state_dict(),"hidden":model.hidden,"activation":model.activation,"input_buckets":model.input_buckets,
                     "selection_value":best,"selection_metric":args.selection_metric,"selected_epoch":0,
                     "optimizer":optimizer.state_dict()},out / "checkpoint.pt")
        export(model, out / "pilot.nnue")
    (out / "epochs.jsonl").write_text("".join(json.dumps(r)+"\n" for r in history))
    started = time.perf_counter()
    last_save = started
    cursor_epoch, cursor_batch, cursor_total = start_epoch, next_batch, total
    def save_progress():
        atomic_save({"model":model.state_dict(),"hidden":model.hidden,
                     "optimizer":optimizer.state_dict(),"protocol":protocol,
                     "epoch":cursor_epoch,"next_batch":cursor_batch,"total":cursor_total,
                     "history":history,"initial":initial,"best":best,
                     "seconds":elapsed_before+time.perf_counter()-started}, latest)
    save_progress()
    for epoch in range(start_epoch, args.epochs):
        for group in optimizer.param_groups:
            group["lr"] = learning_rate(args, epoch)
        # Stable per-epoch permutations allow exact batch continuation without
        # serializing the entire prepared corpus or depending on process RNG state.
        generator = torch.Generator().manual_seed(args.seed+epoch)
        order = torch.randperm(len(x),generator=generator)
        offset = next_batch if epoch == start_epoch else 0
        total = cursor_total if epoch == start_epoch else 0.0
        for batch_index, idx in enumerate(order.split(args.batch)):
            if batch_index < offset:
                continue
            if getattr(args,"pause_file",None) and Path(args.pause_file).exists():
                save_progress()
                print("Paused after saving training progress",flush=True)
                raise SystemExit(75)
            optimizer.zero_grad(set_to_none=True)
            prediction = model(x[idx], stm[idx])
            loss = training_loss(prediction, y[idx], phases["train"][idx] if phases is not None else None, args.opening_weight, weight_mean)
            if not torch.isfinite(loss):
                raise ValueError("Nonfinite training loss; stopping")
            loss.backward()
            optimizer.step()
            total += loss.item() * len(idx)
            cursor_epoch, cursor_batch, cursor_total = epoch, batch_index+1, total
            if time.perf_counter()-last_save >= 10.0:
                save_progress()
                last_save = time.perf_counter()
        metrics = validate()
        record = {"epoch":epoch+1,"seconds":elapsed_before+time.perf_counter()-started,
                  "train_loss":total/len(x),"learning_rate":learning_rate(args,epoch),
                  "updates":(epoch+1)*((len(x)+args.batch-1)//args.batch),**metrics}
        history.append(record)
        if metrics[args.selection_metric] < best:
            best = metrics[args.selection_metric]
            atomic_save({"model":model.state_dict(),"hidden":model.hidden,"activation":model.activation,"input_buckets":model.input_buckets,
                         "selection_value":best,"selection_metric":args.selection_metric,"selected_epoch":epoch+1,
                         "optimizer":optimizer.state_dict()},out / "checkpoint.pt")
            export(model,out / "pilot.nnue")
        cursor_epoch, cursor_batch, cursor_total = epoch+1, 0, 0.0
        save_progress()
        with (out / "epochs.jsonl").open("a") as f:
            f.write(json.dumps(record)+"\n")
        if epoch % 10 == 0 or epoch == args.epochs-1:
            print(json.dumps(record),flush=True)
    # Re-export from the best checkpoint even if interrupted between its saves.
    # Export exact integer reference scores for Java cross-language parity.
    saved = torch.load(out / "checkpoint.pt", weights_only=True)
    model.load_state_dict(saved["model"])
    export(model, out / "pilot.nnue")
    parameters = quantized(model)
    vectors = []
    for fen in parity_fens(args.data):
        vectors.append(f"{fen}\t{integer_evaluate(model, chess.Board(fen), parameters)}")
    (out / "parity.tsv").write_text("\n".join(vectors)+"\n")
    write_json(out / "summary.json", {"environment": environment(), "initial_validation": initial,
        "final_validation": history[-1], "selected_validation":validate(), "selected_epoch":saved.get("selected_epoch"),
        "best_selection_value": best, "selection_metric": args.selection_metric,
        "activation":model.activation, "input_buckets":model.input_buckets, "history": history,
        "data_sha256": data_hash,
        "resume_checkpoint": args.resume,
        "train_examples": len(x), "validation_examples": len(vx), "cross_split_duplicates_excluded": excluded,
        "seconds": elapsed_before+time.perf_counter()-started, "threads": args.threads, "hidden": args.hidden,
        "epochs": args.epochs, "seed": args.seed, "training_protocol": protocol,
        "network_sha256": hashlib.sha256((out / "pilot.nnue").read_bytes()).hexdigest(),
        "interpretation": "pilot validation fit only; no measured HCE improvement or Elo claim"})
    if Path(args.data).is_dir():
        from packed_dataset import close_packed
        close_packed(data)


def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="command", required=True)
    b = sub.add_parser("benchmark")
    b.add_argument("--hidden", type=int, default=128)
    b.add_argument("--batch", type=int, default=1024)
    b.add_argument("--seconds", type=float, default=12)
    b.add_argument("--out", required=True)
    g = sub.add_parser("generate")
    g.add_argument("--teacher", required=True)
    g.add_argument("--positions", type=int, default=2500)
    g.add_argument("--nodes", type=int, default=5000)
    g.add_argument("--seed", type=int, default=20261121)
    g.add_argument("--out", required=True)
    t = sub.add_parser("train")
    t.add_argument("--resume", default=None)
    t.add_argument("--pause-file", default=None)
    t.add_argument("--data", required=True)
    t.add_argument("--out", required=True)
    t.add_argument("--hidden", type=int, default=128)
    t.add_argument("--threads", type=int, default=4)
    t.add_argument("--batch", type=int, default=256)
    t.add_argument("--epochs", type=int, default=80)
    t.add_argument("--seed", type=int, default=20261121)
    t.add_argument("--lr-schedule", choices=("constant", "cosine"), default="constant")
    t.add_argument("--lr-start", type=float, default=0.001)
    t.add_argument("--lr-end", type=float, default=0.0001)
    t.add_argument("--phase-index", default=None)
    t.add_argument("--opening-weight", type=float, default=1.0)
    t.add_argument("--activation", choices=("crelu", "screlu"), default="crelu")
    t.add_argument("--reset-optimizer", action="store_true")
    t.add_argument("--allow-activation-transfer", action="store_true")
    t.add_argument("--input-buckets", type=int, choices=(1, 4), default=1)
    t.add_argument("--allow-bucket-transfer", action="store_true")
    t.add_argument("--selection-metric", choices=("mse_pawns", "sigmoid_mse"), default="sigmoid_mse")
    args = p.parse_args()
    {"benchmark": benchmark, "generate": generate, "train": train}[args.command](args)


if __name__ == "__main__":
    main()
