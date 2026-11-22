#!/usr/bin/env python3
"""Extract "car-model replay fixtures" from DroidCar (or older CarDeck) runtime logs.

Usage:
    python3 tools/extract_car_fixtures.py <log file or directory>... [-o app/src/test/resources/carplay/fixtures]

Output: each log -> <car model>__<log name>.jsonl
  The first line is meta (car model / source / round overview), followed by events grouped by layer:
  - bt layer: {"layer":"bt","round":N,"dir":"rx|tx","hex":"..."} (RAW rx/tx concatenated per round)
  - tunnel layer: {"layer":"tunnel","dir":"rx","id":...,"hex":"..."} (CSM body of IAPLINK rx)
               {"layer":"tunnel","dir":"tx","id":...} (tunnel tx has only the message id: a golden sequence)

Round delimitation: start (receiver connected in / RFCOMM established) -> end (LINK closed / SESSION failed /
SESSION ended / keep-alive ended). Only rounds containing 0x1d01 are kept (rounds that never reached identification have no replay value),
and they carry outcome and duration (the replay tests use them to control duration and quota).

Fixtures contain real addresses/hotspot names: the directory is gitignored by default, and checking them in needs a separate review (see the README in the directory).
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

# Log markers below match both the current English log text and the Chinese text of older logs.
LINE_RE = re.compile(r"^\[(\d\d):(\d\d):(\d\d)\.(\d\d\d)\] (.*)$")
RAW_RX_RE = re.compile(r"^RAW rx: ((?:[0-9a-f]{2} ?)+)$")
RAW_TX_RE = re.compile(r"^RAW tx: ((?:[0-9a-f]{2} ?)+)$")
LINK_CLOSE_RE = re.compile(r"LINK closed|SESSION failed|SESSION ended|keep-alive ended|SESSION 失败|SESSION 结束|保活结束")
ROUND_START_RE = re.compile(r"receiver connected|connected to the wireless iAP2 service of|接收端已连入|已连入 .+ 的无线 iAP2 服务")
IDENT_RE = re.compile(r"iap2 rx=0x1d01 (?:head unit identity|车机身份) name=(.*?) model=(.*?) manufacturer=(.*)$")
NSD_MODEL_RE = re.compile(r"NSD: TXT .*model=(\S+?),")
TUNNEL_RX_RE = re.compile(r"^IAPLINK: rx 0x([0-9a-f]+) \(.*\) \d+B hex=((?:[0-9a-f]{2} ?)+)")
TUNNEL_TX_RE = re.compile(r"^IAPLINK: (?:tunnel|隧道) tx 0x([0-9a-f]+)")
PHASE1_RE = re.compile(r"Phase 1 complete|Phase 1 完成")


def ms_of(m: re.Match) -> int:
    h, mi, s, ms = (int(m.group(i)) for i in range(1, 5))
    return ((h * 60 + mi) * 60 + s) * 1000 + ms


def car_slug(text: str) -> str:
    ident = IDENT_RE.search(text)
    if ident:
        parts = [p.lower() for p in ident.groups()]
        return re.sub(r"[^a-z0-9]+", "_", "_".join(parts)).strip("_")[:48]
    nsd = NSD_MODEL_RE.search(text)
    if nsd:
        return re.sub(r"[^a-z0-9]+", "_", nsd.group(1).lower()).strip("_")[:48]
    return "unknown"


class Round:
    def __init__(self, index: int, start_ms: int):
        self.index = index
        self.start_ms = start_ms
        self.end_ms = start_ms
        self.rx = bytearray()
        self.tx = bytearray()
        self.ids: list[str] = []  # CSM message ids seen in this round (used for the identification check)
        self.phase1 = False
        self.ident_text = ""


def extract(log_path: Path) -> dict | None:
    rounds: list[Round] = []
    current: Round | None = None
    tunnel_rx: list[dict] = []
    tunnel_tx: list[str] = []
    car_id = "unknown"

    for line in log_path.read_text(encoding="utf-8", errors="replace").splitlines():
        m = LINE_RE.match(line)
        if not m:
            continue
        body = m.group(5).strip()
        now = ms_of(m)

        ident = IDENT_RE.search(body)
        if ident:
            car_id = car_slug(body)

        if ROUND_START_RE.search(body):
            current = Round(len(rounds), now)
            rounds.append(current)
        if current is None:
            # The log starts mid-way: still gather one anonymous round
            current = Round(0, now)
            rounds.append(current)
        current.end_ms = now

        if TUNNEL_RX_RE.match(body):
            t = TUNNEL_RX_RE.match(body)
            tunnel_rx.append(
                {"layer": "tunnel", "dir": "rx", "id": int(t.group(1), 16), "hex": t.group(2).replace(" ", "")}
            )
        else:
            t = TUNNEL_TX_RE.match(body)
            if t:
                tunnel_tx.append(t.group(1))

        rx = RAW_RX_RE.match(body)
        tx = RAW_TX_RE.match(body)
        if rx:
            data = bytes.fromhex(rx.group(1).replace(" ", ""))
            # The RAW dump budget truncation leaves half lines (a line observed with only "ff" left): a frame/marker is at least 6B,
            # shorter fragments are dropped directly to avoid polluting the replay byte stream
            if len(data) >= 6:
                current.rx.extend(data)
                current.end_ms = now
        elif tx:
            data = bytes.fromhex(tx.group(1).replace(" ", ""))
            if len(data) >= 6:
                current.tx.extend(data)
                current.end_ms = now
        if PHASE1_RE.search(body) and current is not None:
            current.phase1 = True
            current.ident_text = body

    keep = []
    for r in rounds:
        if not r.rx:
            continue
        ident = IDENT_RE.search(r.ident_text) or IDENT_RE.search("")  # ident per-round, taken from the body of that round
        raw = bytes(r.rx)
        # The RAW dump budget truncates by bytes (512B in old logs): the cut point often falls mid-frame; trim the tail partial
        # frame at a frame boundary, and record whether the identification frame (a complete 0x1D01 CSM) survived. Rounds whose identification frame was
        # truncated can only do link-layer replay (the engine would wait forever on a partial frame); only rounds with a complete identification frame get full assertions.
        trimmed, truncated, has_ident, has_syn = trim_to_frame_boundary(raw)
        if not ("1d01" in raw.hex() or r.phase1):
            continue  # did not reach identification: no replay value (pure negotiation-failure rounds are covered by link-layer unit tests)
        if not has_syn:
            continue  # not even one SYN with a valid checksum (a wreck round): the link layer cannot even be driven, no replay value
        keep.append(
            {
                "layer": "bt",
                "round": r.index,
                "outcome": "phase1" if r.phase1 else "identified",
                "durationMs": r.end_ms - r.start_ms,
                "rxTruncated": truncated,
                "rxHasIdent": has_ident,
                "rxHex": trimmed.hex(),
                "txHex": bytes(r.tx).hex(),
            }
        )
    if not keep and not tunnel_rx:
        return None
    return {"car": car_id, "source": log_path.name, "btRounds": keep, "tunnelRx": tunnel_rx, "tunnelTxIds": tunnel_tx}


MARKER = bytes.fromhex("ff550200ee10")


def trim_to_frame_boundary(data: bytes) -> tuple[bytes, bool, bool]:
    """Return (trimmed byte stream, whether trimming happened, whether intact frames contain a 0x1D01 CSM).

    "Complete frame length" does not mean "intact bytes": the RAW dump budget truncates by bytes and stitches
    the tails of different reads into one stream, forging frames of exactly complete length; their payload checksum fails and the engine
    silently drops them per protocol (2026-10-20 replay debugging: the root cause of the replay stalling at 0x1D00). So every frame is
    verified with the same algorithm as the engine (header 9B sums to 0; payload + trailing checksum sums to 0), everything from the first
    failing frame is trimmed, and the remaining stream is necessarily accepted frame by frame by the engine.
    """
    off = 0
    has_ident = False
    has_syn = False
    while off < len(data):
        if data[off : off + 6] == MARKER:
            off += 6
            continue
        if off + 4 <= len(data) and data[off] == 0xFF and data[off + 1] == 0x5A:
            frame_len = (data[off + 2] << 8) | data[off + 3]
            if frame_len < 9 or off + frame_len > len(data):
                break  # length incomplete (budget truncation point)
            frame = data[off : off + frame_len]
            if sum(frame[:9]) & 0xFF != 0:
                break  # header checksum fails: a frame forged by stitching
            if frame_len > 9 and sum(frame[9:frame_len]) & 0xFF != 0:
                break  # payload checksum fails: same as above
            if frame_len > 9:
                payload = frame[9 : frame_len - 1]
                if frame[4] & 0x80:  # ctl contains the SYN bit (a sync packet carrying the session table)
                    has_syn = True
                if payload[:2] == b"\x40\x40" and payload[4:6] == b"\x1d\x01":
                    has_ident = True
            off += frame_len
            continue
        break  # unrecognized bytes: stop (conservatively drop the tail)
    return data[:off], off < len(data), has_ident, has_syn


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("inputs", nargs="+")
    ap.add_argument("-o", "--out", default="app/src/test/resources/carplay/fixtures")
    args = ap.parse_args()

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    written = 0
    for raw_path in args.inputs:
        p = Path(raw_path)
        files = [p] if p.is_file() else sorted([*p.glob("DroidCar-log-*.txt"), *p.glob("CarDeck-log-*.txt")]) if p.is_dir() else []
        for f in files:
            data = extract(f)
            if data is None:
                print(f"skip (nothing replayable): {f.name}")
                continue
            target = out_dir / f"{data['car']}__{f.stem}.jsonl"
            with target.open("w", encoding="utf-8") as w:
                w.write(json.dumps({"meta": data["meta"] if "meta" in data else {k: data[k] for k in ("car", "source")}}, ensure_ascii=False) + "\n")
                for r in data["btRounds"]:
                    w.write(json.dumps(r, ensure_ascii=False) + "\n")
                for e in data["tunnelRx"]:
                    w.write(json.dumps(e, ensure_ascii=False) + "\n")
                for tid in data["tunnelTxIds"]:
                    w.write(json.dumps({"layer": "tunnel", "dir": "tx", "id": int(tid, 16)}, ensure_ascii=False) + "\n")
            print(f"wrote {target.name}(bt rounds {len(data['btRounds'])}, tunnel rx {len(data['tunnelRx'])})")
            written += 1
    print(f"Done:{written} fixtures -> {out_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
