"""Verify every defense and the complete accepted root set without Java/HCE/NNUE.

Developer dependency: python-chess. Run from any directory. This never labels
training data and does not use an evaluation heuristic.
"""
import json
from pathlib import Path
import chess


def forces(board,attacker,left):
    if board.is_checkmate():return board.turn!=attacker
    if left==0 or board.is_stalemate() or board.is_insufficient_material():return False
    results=[]
    for move in list(board.legal_moves):
        board.push(move);win=forces(board,attacker,left-1);board.pop();results.append(win)
    return any(results) if board.turn==attacker else bool(results) and all(results)


def verify(board,attacker,left,proof):
    if proof=={'mate':True}:
        assert board.is_checkmate() and board.turn!=attacker
        return
    assert left>0
    if board.turn==attacker:
        move=board.parse_uci(proof['choose']);board.push(move)
        verify(board,attacker,left-1,proof['proof']);board.pop()
    else:
        assert set(proof['all_replies'])=={m.uci() for m in board.legal_moves}
        for word,child in proof['all_replies'].items():
            board.push_uci(word);verify(board,attacker,left-1,child);board.pop()


def main():
    resource=Path(__file__).resolve().parents[1]/'resources'
    catalog=json.loads((resource/'nnue-mate-proofs.json').read_text())
    tsv=[]
    for row in catalog['positions']:
        board=chess.Board(row['fen']);assert board.is_valid()
        if row.get('source_history_uci'):
            replay=chess.Board()
            for word in row['source_history_uci']:replay.push_uci(word)
            assert replay.fen()==row['fen'],'Source game/root mismatch'
        attacker=board.turn;found=set()
        for move in list(board.legal_moves):
            board.push(move)
            if forces(board,attacker,row['mate_plies']-1):found.add(move.uci())
            board.pop()
        assert found==set(row['accepted_moves'])
        for word,proof in row['accepted_moves'].items():
            board.push_uci(word);verify(board,attacker,row['mate_plies']-1,proof);board.pop()
        tsv.append(f"{row['fen']}\t{row['mate_plies']}\t{','.join(sorted(found))}")
    assert (resource/'nnue-mate-proofs.tsv').read_text()=='\n'.join(tsv)+'\n'
    print(f"Verified {len(tsv)} complete mate-in-two root sets and every defender reply")


if __name__=='__main__':main()
