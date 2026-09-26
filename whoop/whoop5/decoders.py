"""Decoders for payloads whose layout is known (or partly known).

Anything not recognised is returned as a GenericRecord so the raw bytes are
never thrown away — unknown records are exactly what you want to study.
Offsets are relative to the inner packet (byte 0 = packet type).
"""
from __future__ import annotations

import struct
from dataclasses import asdict, dataclass

from .protocol import Cmd, Frame, PacketType, enum_name


@dataclass
class RealtimeHR:
    """REALTIME_DATA (0x28) compact record, type 2, ~1 Hz."""
    unix_ts: int
    bpm: int
    valid: bool
    rr_ms: int | None


@dataclass
class HistoricalHR:
    """HISTORICAL_DATA (0x2F) record type 18, one per second of history."""
    sequence: int
    bpm: int
    flag: int
    rr_ms: int | None
    smoothed_bpm: int
    quaternion: tuple[float, float, float, float] | None


@dataclass
class CommandResponse:
    command: str
    seq: int
    body: str  # hex; per-command layouts are mostly still unknown


@dataclass
class GenericRecord:
    packet_type: str
    record_type: int | None
    length: int
    hex: str


@dataclass
class StandardHR:
    """Bluetooth SIG Heart Rate Measurement (0x2A37)."""
    bpm: int
    contact: bool | None
    rr_ms: list[float]


def decode_inner(frame: Frame) -> object:
    p = frame.inner
    pt = frame.packet_type

    if pt == PacketType.REALTIME_DATA and len(p) >= 12 and p[1] == 0x02:
        ts, = struct.unpack_from("<I", p, 2)
        valid = p[9] == 0x01
        rr = struct.unpack_from("<H", p, 10)[0]
        return RealtimeHR(unix_ts=ts, bpm=p[8], valid=valid, rr_ms=rr if valid and rr else None)

    if pt == PacketType.HISTORICAL_DATA and len(p) >= 49 and p[1] == 18:
        seq, = struct.unpack_from("<H", p, 2)
        rr = struct.unpack_from("<H", p, 16)[0]
        quat = struct.unpack_from("<4f", p, 33)
        return HistoricalHR(sequence=seq, bpm=p[14], flag=p[15],
                            rr_ms=rr if p[15] and rr else None,
                            smoothed_bpm=p[29], quaternion=quat)

    if pt in (PacketType.COMMAND_RESPONSE, PacketType.PUFFIN_COMMAND_RESPONSE):
        return CommandResponse(command=enum_name(Cmd, frame.command), seq=frame.seq,
                               body=frame.params.hex())

    return GenericRecord(packet_type=frame.type_name,
                         record_type=p[1] if len(p) > 1 else None,
                         length=len(p), hex=p.hex())


def decode_standard_hr(data: bytes) -> StandardHR | None:
    if len(data) < 2:
        return None
    flags = data[0]
    i = 1
    if flags & 0x01:
        if len(data) < 3:
            return None
        bpm = struct.unpack_from("<H", data, 1)[0]
        i = 3
    else:
        bpm = data[1]
        i = 2
    contact = bool(flags & 0x02) if flags & 0x04 else None
    if flags & 0x08:
        i += 2  # energy expended
    rr = []
    if flags & 0x10:
        while i + 1 < len(data):
            rr.append(round(struct.unpack_from("<H", data, i)[0] * 1000 / 1024, 1))
            i += 2
    return StandardHR(bpm=bpm, contact=contact, rr_ms=rr)


def to_dict(record: object) -> dict:
    d = asdict(record)
    d["kind"] = type(record).__name__
    return d
