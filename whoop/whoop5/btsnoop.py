"""Extract WHOOP traffic from an Android Bluetooth HCI snoop log (btsnoop).

This is the main reverse-engineering loop: let the *official* app talk to your
strap while Android records every Bluetooth packet, then decode what it said.

Supports btsnoop datalink 1002 (H4 UART, what Android writes) and 1001.
Pulls ATT writes/notifications out of ACL -> L2CAP (CID 4), reassembling
fragmented ACL packets, and maps ATT handles to characteristic UUIDs when the
GATT discovery is in the capture (it is if you "forget" the strap first or
toggle Bluetooth before capturing).
"""
from __future__ import annotations

import struct
import uuid as uuidlib
from collections.abc import Iterator
from dataclasses import dataclass, field

MAGIC = b"btsnoop\x00"
H4_ACL = 0x02
ATT_CID = 0x0004

ATT_READ_BY_TYPE_RSP = 0x09
ATT_READ_RSP = 0x0B
ATT_WRITE_REQ = 0x12
ATT_WRITE_CMD = 0x52
ATT_NOTIFY = 0x1B
ATT_INDICATE = 0x1D
ATT_OPS = {ATT_WRITE_REQ: "write", ATT_WRITE_CMD: "write_cmd",
           ATT_NOTIFY: "notify", ATT_INDICATE: "indicate"}

# btsnoop timestamps are microseconds since 0000-01-01; this shifts to Unix.
_EPOCH_DELTA_US = 0x00DCDDB30F2F8000


@dataclass
class AttEvent:
    ts: float            # unix seconds
    direction: str       # "app->strap" | "strap->app"
    op: str
    acl_handle: int
    att_handle: int
    value: bytes
    uuid: str | None = None


def _records(data: bytes) -> Iterator[tuple[int, int, bytes]]:
    if data[:8] != MAGIC:
        raise ValueError("not a btsnoop file (bad magic)")
    _version, datalink = struct.unpack_from(">II", data, 8)
    if datalink not in (1001, 1002):
        raise ValueError(f"unsupported btsnoop datalink {datalink}")
    off = 16
    while off + 24 <= len(data):
        _orig, incl, flags, _drops, ts = struct.unpack_from(">IIIIq", data, off)
        off += 24
        pkt = data[off:off + incl]
        off += incl
        if datalink == 1002:
            if not pkt or pkt[0] != H4_ACL:
                continue
            pkt = pkt[1:]
        elif flags & 0x02:  # 1001: command/event, not ACL data
            continue
        yield ts, flags, pkt


def _uuid_str(raw: bytes) -> str:
    if len(raw) == 2:
        return f"0000{struct.unpack('<H', raw)[0]:04x}-0000-1000-8000-00805f9b34fb"
    return str(uuidlib.UUID(bytes=raw[::-1]))


@dataclass
class _Reassembly:
    buf: bytearray = field(default_factory=bytearray)
    need: int = 0


def parse(data: bytes) -> list[AttEvent]:
    """Return every ATT write/notification in the capture, in order."""
    events: list[AttEvent] = []
    handle_uuid: dict[int, str] = {}
    partial: dict[tuple[int, int], _Reassembly] = {}

    for ts, flags, acl in _records(data):
        if len(acl) < 4:
            continue
        hdr, _dlen = struct.unpack_from("<HH", acl)
        conn, pb = hdr & 0x0FFF, (hdr >> 12) & 0x3
        received = bool(flags & 0x01)
        key = (conn, received)
        body = acl[4:]

        if pb == 0x1:  # continuation fragment
            r = partial.get(key)
            if r is None:
                continue
            r.buf.extend(body)
        else:
            if len(body) < 4:
                continue
            l2len, = struct.unpack_from("<H", body)
            r = partial[key] = _Reassembly(bytearray(body), l2len + 4)
        if len(r.buf) < r.need:
            continue
        del partial[key]
        l2len, cid = struct.unpack_from("<HH", r.buf)
        if cid != ATT_CID:
            continue
        att = bytes(r.buf[4:4 + l2len])
        if not att:
            continue
        op = att[0]

        if op == ATT_READ_BY_TYPE_RSP and len(att) > 2:
            # Characteristic declarations: [decl handle][props][value handle][uuid]
            size = att[1]
            for i in range(2, len(att) - size + 1, size):
                entry = att[i:i + size]
                if size in (7, 21):
                    value_handle, = struct.unpack_from("<H", entry, 3)
                    handle_uuid[value_handle] = _uuid_str(entry[5:])
            continue

        if op in ATT_OPS and len(att) >= 3:
            h, = struct.unpack_from("<H", att, 1)
            events.append(AttEvent(
                ts=(ts - _EPOCH_DELTA_US) / 1e6,
                direction="strap->app" if received else "app->strap",
                op=ATT_OPS[op], acl_handle=conn, att_handle=h, value=att[3:],
            ))

    for e in events:
        e.uuid = handle_uuid.get(e.att_handle)
    return events


def write_btsnoop(records: list[tuple[bool, bytes]]) -> bytes:
    """Build a minimal btsnoop (datalink 1002) file. Used by the tests."""
    out = bytearray(MAGIC + struct.pack(">II", 1, 1002))
    for i, (received, h4) in enumerate(records):
        out += struct.pack(">IIIIq", len(h4), len(h4), int(received), 0,
                           _EPOCH_DELTA_US + 1_700_000_000_000_000 + i * 1000)
        out += h4
    return bytes(out)
