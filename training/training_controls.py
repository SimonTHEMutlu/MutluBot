"""Training-only controls. No teacher labels or Java inference are changed."""
import math
import hashlib
import json
from pathlib import Path

import numpy as np
import torch

PHASES = ("opening", "middlegame", "endgame")


def validate_settings(args):
    for name in ("lr_start", "lr_end", "opening_weight"):
        value = getattr(args, name)
        if not math.isfinite(value) or value <= 0:
            raise ValueError(f"{name} must be finite and positive")
    if args.lr_end > args.lr_start:
        raise ValueError("lr_end must not exceed lr_start")
    if args.epochs < 1:
        raise ValueError("epochs must be positive")
    if args.opening_weight != 1.0 and not args.phase_index:
        raise ValueError("Opening emphasis requires a phase index")


def learning_rate(args, epoch):
    if args.lr_schedule == "constant" or args.epochs == 1:
        return args.lr_start
    progress = epoch / (args.epochs - 1)
    return args.lr_end + (args.lr_start - args.lr_end) * (1 + math.cos(math.pi * progress)) / 2


def load_phases(directory, counts, data_hash):
    if not directory:
        return None
    coverage = json.loads((Path(directory) / "coverage.json").read_text())
    if coverage["packed_manifest_sha256"] != data_hash:
        raise ValueError("Phase index belongs to a different packed dataset")
    phases = {}
    for split, count in counts.items():
        path = Path(directory) / f"{split}-phase.npy"
        if hashlib.sha256(path.read_bytes()).hexdigest() != coverage["files"][path.name]:
            raise ValueError(f"Phase index hash mismatch: {path}")
        array = np.load(path, allow_pickle=False)
        if array.dtype != np.uint8 or array.shape != (count,) or np.any(array > 2):
            raise ValueError(f"Invalid phase index: {path}")
        phases[split] = torch.from_numpy(array.astype(np.int64))
    return phases


def training_loss(prediction, target, phases=None, opening_weight=1.0, normalization=1.0):
    error = (torch.sigmoid(prediction / 4.0) - torch.sigmoid(target / 4.0)) ** 2
    # Exact original arithmetic when emphasis is disabled.
    if opening_weight == 1.0:
        return torch.mean(error)
    weights = torch.where(phases == 0, opening_weight, 1.0)
    return torch.mean(error * weights) / normalization


def phase_metrics(prediction, target, phases, stm):
    result = {}
    for code, name in enumerate(PHASES):
        for side, side_code in (("all", None), ("white_to_move", 0), ("black_to_move", 1)):
            mask = phases == code
            if side_code is not None:
                mask &= stm == side_code
            count = int(mask.sum())
            if count:
                error = prediction[mask] - target[mask]
                result[f"{name}/{side}"] = {
                    "positions": count,
                    "mae_cp": float(error.abs().mean()) * 100,
                    "mse_pawns": float(error.square().mean()),
                    "bias_cp": float(error.mean()) * 100,
                }
    return result
