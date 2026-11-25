"""Small functional tests only; fixture rows never enter the real dataset."""
import contextlib
import io
import json
import hashlib
import numpy as np
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import chess
import torch

import nnue_lab as lab


class TrainerTest(unittest.TestCase):
    def fixture(self, root):
        board=chess.Board();rows=[]
        for i,word in enumerate('e2e4 e7e5 g1f3 b8c6 f1c4 g8f6 d2d3 f8c5'.split()):
            board.push_uci(word)
            rows.append({'fen':board.fen(),'cp':i*8,'split':'validation' if i%4==3 else 'train'})
        file=root/'data.jsonl';file.write_text('\n'.join(json.dumps(r) for r in rows)+'\n')
        return file

    def args(self,root,data,**changes):
        values=dict(data=str(data),out=str(root),resume=None,pause_file=None,hidden=8,threads=1,batch=2,
                    epochs=3,seed=37,lr_start=0.001,lr_end=0.0001,lr_schedule='cosine',phase_index=None,
                    opening_weight=1.0,activation='screlu',reset_optimizer=True,
                    allow_activation_transfer=False,selection_metric='sigmoid_mse')
        values.update(changes)
        return SimpleNamespace(**values)

    def test_formats_and_parity(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);data=self.fixture(root)
            for activation,magic in [('crelu',b'MUTLUN01'),('screlu',b'MUTLUN02')]:
                out=root/activation
                with contextlib.redirect_stdout(io.StringIO()):lab.train(self.args(out,data,activation=activation,epochs=2))
                self.assertEqual((out/'pilot.nnue').read_bytes()[:8],magic)
                summary=json.loads((out/'summary.json').read_text())
                self.assertIn('sigmoid_mse',summary['final_validation'])
                self.assertEqual(summary['history'][-1]['updates'],6)
                self.assertEqual(summary['activation'],activation)
                self.assertTrue((out/'parity.tsv').read_text().strip())

    def test_failed_export_preserves_previous_complete_network(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);file=root/'active.nnue'
            lab.export(lab.Network(8,'crelu'),file);original=file.read_bytes()
            model=lab.Network(8,'screlu',4);pack=lab.struct.pack;calls=0
            def interrupted(*args):
                nonlocal calls
                calls+=1
                if calls==2:raise OSError('Injected interruption after header write')
                return pack(*args)
            with patch.object(lab.struct,'pack',interrupted):
                with self.assertRaises(OSError):lab.export(model,file)
            self.assertEqual(file.read_bytes(),original)
            self.assertEqual(list(root.iterdir()),[file])
            with patch.object(lab,'replace_with_retry',side_effect=PermissionError('Injected exhausted sharing retries')):
                with self.assertRaises(PermissionError):lab.export(model,file)
            self.assertEqual(file.read_bytes(),original)
            self.assertEqual(list(root.iterdir()),[file])
            lab.export(model,file)
            self.assertEqual(file.read_bytes()[:8],b'MUTLUN03')
            self.assertEqual(list(root.iterdir()),[file])

    def test_resume_is_exact_and_protocol_locked(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);data=self.fixture(root)
            with contextlib.redirect_stdout(io.StringIO()):lab.train(self.args(root/'complete',data))
            args=self.args(root/'resumed',data,pause_file=str(root/'PAUSE'))
            original_step=torch.optim.Adam.step
            def pause_after_step(optimizer,*a,**kw):
                result=original_step(optimizer,*a,**kw);Path(args.pause_file).touch();return result
            with patch.object(torch.optim.Adam,'step',pause_after_step),contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaises(SystemExit) as exc:lab.train(args)
                self.assertEqual(exc.exception.code,75)
            Path(args.pause_file).unlink()
            with contextlib.redirect_stdout(io.StringIO()):lab.train(args)
            full=torch.load(root/'complete/latest.pt',weights_only=True)
            resumed=torch.load(root/'resumed/latest.pt',weights_only=True)
            for key in full['model']:self.assertTrue(torch.equal(full['model'][key],resumed['model'][key]))
            self.assertEqual((root/'complete/pilot.nnue').read_bytes(),(root/'resumed/pilot.nnue').read_bytes())
            args.lr_end=0.00001
            with self.assertRaisesRegex(ValueError,'inputs/settings changed'),contextlib.redirect_stdout(io.StringIO()):lab.train(args)

    def test_activation_transfer_requires_explicit_opt_in(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);data=self.fixture(root)
            with contextlib.redirect_stdout(io.StringIO()):lab.train(self.args(root/'original',data,activation='crelu',epochs=1))
            args=self.args(root/'transfer',data,resume=str(root/'original/checkpoint.pt'))
            with self.assertRaisesRegex(ValueError,'explicit'),contextlib.redirect_stdout(io.StringIO()):lab.train(args)
            args.allow_activation_transfer=True
            with contextlib.redirect_stdout(io.StringIO()):lab.train(args)
            self.assertEqual((root/'transfer/pilot.nnue').read_bytes()[:8],b'MUTLUN02')

    def test_bucket_transfer_function_and_conditioned_gradients(self):
        torch.manual_seed(17)
        base=lab.Network(8,'screlu');bucket=lab.Network(8,'screlu',4)
        missing,unexpected=bucket.load_state_dict(base.state_dict(),strict=False)
        self.assertEqual(missing,['bucket_embedding.weight']);self.assertFalse(unexpected)
        fens=['7k/8/8/8/8/8/3K4/8 w - - 0 1','7k/8/8/8/8/8/5K2/8 b - - 0 1',
              '7k/8/8/8/8/2K5/8/8 w - - 0 1','7k/8/8/8/8/8/2K5/8 b - - 0 1']
        x=torch.from_numpy(np.stack([lab.features(chess.Board(f)) for f in fens]));stm=torch.tensor([0,1,0,1])
        self.assertTrue(torch.equal(base(x,stm),bucket(x,stm)))
        self.assertEqual(lab.king_buckets(x).tolist(),[[1,2],[2,2],[3,2],[0,2]])
        bucket(x,stm).sum().backward()
        gradients=bucket.bucket_embedding.weight.grad.reshape(3073,8)
        self.assertTrue(all(float(gradients[i*768:(i+1)*768].abs().sum())>0 for i in range(4)))
        self.assertEqual(float(gradients[3072].abs().sum()),0.0)
        # The merged export is exactly the original when residuals are zero.
        self.assertTrue(np.array_equal(lab.quantized(bucket)[0].reshape(4,768,8)[0],lab.quantized(base)[0]))
        with torch.no_grad():bucket.bucket_embedding.weight[768:1536].fill_(0.25)
        matrices=lab.quantized(bucket)[0].reshape(4,768,8)
        self.assertFalse(np.array_equal(matrices[0],matrices[1]))

    def test_bucket_packed_training_transfer_and_resume(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);data=self.fixture(root)
            rows=[json.loads(s) for s in data.read_text().splitlines()]
            packed=root/'packed';packed.mkdir();manifest={'format':'mutlu-packed-v1','files':{},'counts':{},'parity_fens':[r['fen'] for r in rows]}
            for split in ('train','validation'):
                selected=[r for r in rows if r['split']==split];manifest['counts'][split]=len(selected)
                for name,array in [('x',np.stack([lab.features(chess.Board(r['fen'])) for r in selected]).astype(np.uint16)),
                                   ('stm',np.array([0 if chess.Board(r['fen']).turn else 1 for r in selected],dtype=np.uint8)),
                                   ('y',np.array([r['cp']/100 for r in selected],dtype=np.float32))]:
                    path=packed/f'{split}-{name}.npy';np.save(path,array);manifest['files'][path.name]=hashlib.sha256(path.read_bytes()).hexdigest()
            (packed/'manifest.json').write_text(json.dumps(manifest))
            with contextlib.redirect_stdout(io.StringIO()):lab.train(self.args(root/'base',packed,epochs=1))
            args=self.args(root/'bucket',packed,input_buckets=4,resume=str(root/'base/checkpoint.pt'))
            with self.assertRaisesRegex(ValueError,'Bucket transfer requires explicit'),contextlib.redirect_stdout(io.StringIO()):lab.train(args)
            args.allow_bucket_transfer=True;args.pause_file=str(root/'PAUSE');Path(args.pause_file).touch()
            with self.assertRaises(SystemExit),contextlib.redirect_stdout(io.StringIO()):lab.train(args)
            Path(args.pause_file).unlink()
            with contextlib.redirect_stdout(io.StringIO()):lab.train(args)
            full=self.args(root/'uninterrupted',packed,input_buckets=4,resume=str(root/'base/checkpoint.pt'),allow_bucket_transfer=True)
            with contextlib.redirect_stdout(io.StringIO()):lab.train(full)
            self.assertEqual((root/'bucket/pilot.nnue').read_bytes(),(root/'uninterrupted/pilot.nnue').read_bytes())
            summary=json.loads((root/'bucket/summary.json').read_text())
            self.assertEqual(summary['input_buckets'],4)
            self.assertEqual((root/'bucket/pilot.nnue').read_bytes()[:8],b'MUTLUN03')
            for fen in manifest['parity_fens']:
                self.assertIn(fen,(root/'bucket/parity.tsv').read_text())


if __name__=='__main__':unittest.main(verbosity=2)
