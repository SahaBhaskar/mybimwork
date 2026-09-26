"""Command-line entry point: python -m whoop5 <subcommand> --help"""
from __future__ import annotations

import argparse
import asyncio
import json
import sys
from collections import Counter
from pathlib import Path

from . import protocol as P
from .decoders import decode_inner, to_dict


def _parse_cmd(s: str) -> int:
    try:
        return P.Cmd[s.upper()].value
    except KeyError:
        return int(s, 0)


# --- offline ---------------------------------------------------------------

def cmd_frame(a) -> None:
    for frame in P.FrameAssembler().feed(bytes.fromhex(a.hex.replace(" ", ""))):
        print(frame.describe())
        print(json.dumps(to_dict(decode_inner(frame)), default=str, indent=2))


def cmd_build(a) -> None:
    print(P.build_command(_parse_cmd(a.command), bytes.fromhex(a.params), seq=a.seq).hex())


def _iter_chunks(path: Path):
    """Yield (key, direction, uuid, bytes) from a btsnoop capture or a sniff .jsonl log."""
    data = path.read_bytes()
    if data.startswith(b"btsnoop"):
        from .btsnoop import parse
        for e in parse(data):
            yield (e.acl_handle, e.att_handle), e.direction, e.uuid or f"handle 0x{e.att_handle:04x}", e.value
    else:
        for line in data.decode().splitlines():
            if line.strip():
                r = json.loads(line)
                d = "app->strap" if r["dir"] == "tx" else "strap->app"
                yield r["char"], d, r["char"], bytes.fromhex(r["hex"])


def cmd_decode(a) -> None:
    asms: dict = {}
    stats: Counter = Counter()
    for key, direction, uuid, chunk in _iter_chunks(Path(a.file)):
        name = P.UUID_NAMES.get(uuid, uuid)
        frames = asms.setdefault(key, P.FrameAssembler()).feed(chunk)
        if not frames and a.raw:
            print(f"{direction:10s} {name:14s} raw {chunk.hex()}")
        for f in frames:
            stats[(direction, f.type_name, f.command if f.packet_type in (0x23, 0x24) else None)] += 1
            if a.type and f.type_name != a.type.upper():
                continue
            rec = to_dict(decode_inner(f))
            print(f"{direction:10s} {name:14s} {f.describe()}")
            if rec["kind"] != "GenericRecord":
                print(" " * 26 + json.dumps(rec, default=str))
    print("\n# summary", file=sys.stderr)
    for (d, t, c), n in stats.most_common():
        extra = f" cmd={P.enum_name(P.Cmd, c)}" if c is not None else ""
        print(f"#  {n:6d}  {d:10s} {t}{extra}", file=sys.stderr)
    dropped = sum(x.dropped for x in asms.values())
    if dropped:
        print(f"#  {dropped} bytes did not belong to any WHOOP frame", file=sys.stderr)


# --- live --------------------------------------------------------------------

def _run(coro):
    try:
        asyncio.run(coro)
    except KeyboardInterrupt:
        pass


async def _scan(a):
    from .ble import scan
    for dev, rssi, gen in await scan(a.timeout):
        print(f"{dev.address}  rssi={rssi:4d}  gen={gen:6s}  {dev.name}")


async def _gatt(a):
    from .ble import Strap
    async with Strap(a.address, pair=not a.no_pair) as s:
        for row in await s.gatt_dump():
            print(json.dumps(row))


async def _sniff(a):
    from .ble import Strap, print_record
    with open(a.out, "a") as log:
        async with Strap(a.address, log=log, pair=not a.no_pair) as s:
            s.on_record = print_record if not a.quiet else None
            for f in await s.subscribe_all():
                print(f"! subscribe failed {f}", file=sys.stderr)
            for c in a.send:
                name, _, params = c.partition(":")
                code = _parse_cmd(name)
                if code in P.MUTATING_CMDS and not a.allow_write:
                    sys.exit(f"{name} changes strap state; pass --allow-write if you mean it")
                print(f"> {P.build_command(code, bytes.fromhex(params)).hex()}  ({name})")
                await s.send(code, bytes.fromhex(params))
                await asyncio.sleep(0.3)
            print(f"logging to {a.out}; Ctrl-C to stop", file=sys.stderr)
            await asyncio.sleep(a.seconds or 10**9)


async def _hr(a):
    from .ble import Strap
    from .decoders import RealtimeHR, StandardHR

    def show(char, rec):
        if isinstance(rec, StandardHR):
            print(f"HR {rec.bpm:3d} bpm  rr={rec.rr_ms}  (0x2A37)")
        elif isinstance(rec, RealtimeHR) and rec.valid:
            print(f"HR {rec.bpm:3d} bpm  rr={rec.rr_ms}  (0x28 stream)")

    async with Strap(a.address, pair=not a.no_pair) as s:
        s.on_record = show
        await s.subscribe_all()
        await s.send(P.Cmd.TOGGLE_GENERIC_HR_PROFILE, P.toggle_params(True))
        await asyncio.sleep(10**9)


def main(argv: list[str] | None = None) -> None:
    ap = argparse.ArgumentParser(prog="whoop5", description=__doc__)
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("frame", help="decode hex bytes containing one or more frames")
    p.add_argument("hex")
    p.set_defaults(fn=cmd_frame)

    p = sub.add_parser("build", help="encode a command frame, e.g. build GET_HELLO")
    p.add_argument("command", help="name (GET_BATTERY_LEVEL) or number (0x1a)")
    p.add_argument("params", nargs="?", default="", help="param bytes as hex")
    p.add_argument("--seq", type=lambda s: int(s, 0), default=1)
    p.set_defaults(fn=cmd_build)

    p = sub.add_parser("decode", help="decode an Android btsnoop_hci.log or a sniff .jsonl")
    p.add_argument("file")
    p.add_argument("--type", help="only show this packet type, e.g. EVENT")
    p.add_argument("--raw", action="store_true", help="also show chunks that are not frames")
    p.set_defaults(fn=cmd_decode)

    p = sub.add_parser("scan", help="find nearby straps")
    p.add_argument("--timeout", type=float, default=10)
    p.set_defaults(fn=lambda a: _run(_scan(a)))

    for name, fn, hlp in (("gatt", _gatt, "connect, bond and dump the GATT table"),
                          ("sniff", _sniff, "log every notification to a .jsonl file"),
                          ("hr", _hr, "print live heart rate")):
        p = sub.add_parser(name, help=hlp)
        p.add_argument("address", help="MAC (Linux/Windows) or UUID (macOS) from `scan`")
        p.add_argument("--no-pair", action="store_true")
        p.set_defaults(fn=lambda a, fn=fn: _run(fn(a)))
        if name == "sniff":
            p.add_argument("--out", default="whoop_sniff.jsonl")
            p.add_argument("--send", action="append", default=[],
                           help="command to send after subscribing, NAME or NAME:hexparams; repeatable")
            p.add_argument("--seconds", type=float, default=0)
            p.add_argument("--quiet", action="store_true")
            p.add_argument("--allow-write", action="store_true",
                           help="permit commands that change strap state")

    a = ap.parse_args(argv)
    a.fn(a)


if __name__ == "__main__":
    main()
