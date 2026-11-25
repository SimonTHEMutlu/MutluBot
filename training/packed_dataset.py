"""Disk-backed NNUE tensors: bounded batch copies, unchanged model/loss."""
import hashlib
import json
from pathlib import Path

import numpy as np
import torch


def file_sha(path):
    digest=hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda:stream.read(1024*1024),b""):digest.update(block)
    return digest.hexdigest()


class BatchArray:
    def __init__(self,array,dtype):self.array,self.dtype=array,dtype
    def __len__(self):return len(self.array)
    def __getitem__(self,index):
        if isinstance(index,torch.Tensor):index=index.numpy()
        # Writable batch copy avoids exposing read-only memmap storage to torch.
        return torch.from_numpy(np.array(self.array[index],copy=True)).to(self.dtype)
    def close(self):self.array._mmap.close()


def close_packed(data):
    for arrays in data.values():
        for array in arrays:
            if isinstance(array,BatchArray):array.close()


def load_packed(directory):
    root=Path(directory)
    manifest=json.loads((root/"manifest.json").read_text())
    if manifest["format"]!="mutlu-packed-v1":raise ValueError("Unknown packed dataset format")
    for name,expected in manifest["files"].items():
        path=root/name
        if file_sha(path)!=expected:raise ValueError(f"Packed data hash mismatch: {path}")
    arrays={};opened=[]
    try:
        for split in ("train","validation"):
            result=[]
            for name,dtype,shape,torch_type in [("x",np.dtype("uint16"),(2,32),torch.long),
                                              ("stm",np.dtype("uint8"),(),torch.long),
                                              ("y",np.dtype("float32"),(),torch.float32)]:
                path=root/f"{split}-{name}.npy"
                array=np.load(path,mmap_mode="r",allow_pickle=False);opened.append(array)
                if array.dtype!=dtype or array.shape!=(manifest["counts"][split],*shape):
                    raise ValueError(f"Packed data shape/type mismatch: {path}")
                result.append(BatchArray(array,torch_type))
            if not len(result[0]):raise ValueError(f"Empty {split} split")
            arrays[split]=tuple(result)
    except BaseException:
        for array in opened:array._mmap.close()
        raise
    return arrays,0


def data_sha(path):
    path=Path(path)
    return file_sha(path/"manifest.json" if path.is_dir() else path)


def parity_fens(path,limit=128):
    path=Path(path)
    if path.is_dir():
        yield from json.loads((path/"manifest.json").read_text())["parity_fens"][:limit]
    else:
        with path.open() as stream:
            for i,line in enumerate(stream):
                if i>=limit:break
                yield json.loads(line)["fen"]
