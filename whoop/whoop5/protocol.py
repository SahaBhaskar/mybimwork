"""WHOOP 5.0 / MG ("Maverick") BLE wire protocol: framing, CRCs, enums, commands.

Frame layout (all integers little-endian):

    [0]      0xAA            start of frame
    [1]      0x01            protocol version
    [2..3]   u16             length of inner packet + 4 (the CRC32 trailer)
    [4]      0x00            role byte A (app)
    [5]      0x01            role byte B (strap)
    [6..7]   u16             CRC16-MODBUS over bytes [0..6)
    [8..]    inner packet    [packet_type][seq][command][params...]
    [..]     u32             CRC32 (zlib) over the inner packet

Outgoing inner packets are zero-padded to a multiple of 4 bytes, matching the
official app. Facts here come from public community research (see
docs/PROTOCOL.md for sources); nothing is copied from the official app.
"""
from __future__ import annotations

import struct
import zlib
from dataclasses import dataclass, field
from enum import IntEnum
from itertools import count

SOF = 0xAA
VERSION = 0x01
HEADER_LEN = 8
TRAILER_LEN = 4
MAX_INNER_LEN = 4096  # sanity bound when resyncing on garbage

# --- GATT ------------------------------------------------------------------

_BASE = "-cce1-4033-93ce-002d5875f58a"
SERVICE_UUID = "fd4b0001" + _BASE
CMD_WRITE_UUID = "fd4b0002" + _BASE      # app -> strap (write / write-no-resp)
CMD_RESPONSE_UUID = "fd4b0003" + _BASE   # notify
EVENTS_UUID = "fd4b0004" + _BASE         # notify
DATA_UUID = "fd4b0005" + _BASE           # notify (bulk / fragmented)
MEMFAULT_UUID = "fd4b0007" + _BASE       # notify (crash dumps)
NOTIFY_UUIDS = (CMD_RESPONSE_UUID, EVENTS_UUID, DATA_UUID, MEMFAULT_UUID)

# WHOOP 4.0 uses a different service and framing; kept for detection only.
GEN4_SERVICE_UUID = "61080001-8d6d-82b8-614a-1c8cb0f8dcc6"

HR_SERVICE_UUID = "0000180d-0000-1000-8000-00805f9b34fb"
HR_MEASUREMENT_UUID = "00002a37-0000-1000-8000-00805f9b34fb"
BATTERY_LEVEL_UUID = "00002a19-0000-1000-8000-00805f9b34fb"
DEVICE_INFO_UUIDS = {
    "model": "00002a24-0000-1000-8000-00805f9b34fb",
    "serial": "00002a25-0000-1000-8000-00805f9b34fb",
    "firmware": "00002a26-0000-1000-8000-00805f9b34fb",
    "hardware": "00002a27-0000-1000-8000-00805f9b34fb",
    "manufacturer": "00002a29-0000-1000-8000-00805f9b34fb",
}

UUID_NAMES = {
    CMD_WRITE_UUID: "cmd_write",
    CMD_RESPONSE_UUID: "cmd_response",
    EVENTS_UUID: "events",
    DATA_UUID: "data",
    MEMFAULT_UUID: "memfault",
    HR_MEASUREMENT_UUID: "heart_rate",
    BATTERY_LEVEL_UUID: "battery",
}


class PacketType(IntEnum):
    COMMAND = 0x23
    COMMAND_RESPONSE = 0x24
    PUFFIN_COMMAND = 0x25
    PUFFIN_COMMAND_RESPONSE = 0x26
    REALTIME_DATA = 0x28
    REALTIME_RAW_DATA = 0x2B
    HISTORICAL_DATA = 0x2F
    EVENT = 0x30
    METADATA = 0x31
    CONSOLE_LOGS = 0x32
    REALTIME_IMU = 0x33
    HISTORICAL_IMU = 0x34
    RELATIVE_PUFFIN_EVENTS = 0x35
    PUFFIN_EVENTS = 0x36
    BATTERY_PACK_CONSOLE_LOGS = 0x37
    PUFFIN_METADATA = 0x38


class Cmd(IntEnum):
    LINK_VALID = 0x01
    GET_MAX_PROTOCOL_VERSION = 0x02
    TOGGLE_REALTIME_HR = 0x03
    REPORT_VERSION_INFO = 0x07
    SET_CLOCK = 0x0A
    GET_CLOCK = 0x0B
    TOGGLE_GENERIC_HR_PROFILE = 0x0E
    RUN_HAPTIC_PATTERN_MAVERICK = 0x13
    ABORT_HISTORICAL_TRANSMITS = 0x14
    SEND_HISTORICAL_DATA = 0x16
    HISTORICAL_DATA_RESULT = 0x17
    GET_BATTERY_LEVEL = 0x1A
    REBOOT_STRAP = 0x1D
    GET_DATA_RANGE = 0x22
    SEND_R10_R11_REALTIME = 0x3F
    SET_ALARM_TIME = 0x42
    GET_ALARM_TIME = 0x43
    RUN_ALARM = 0x44
    DISABLE_ALARM = 0x45
    START_RAW_DATA = 0x51
    STOP_RAW_DATA = 0x52
    GET_BODY_LOCATION_AND_STATUS = 0x54
    ENTER_HIGH_FREQ_SYNC = 0x60
    EXIT_HIGH_FREQ_SYNC = 0x61
    GET_EXTENDED_BATTERY_INFO = 0x62
    TOGGLE_IMU_MODE_HISTORICAL = 0x69
    TOGGLE_IMU_MODE = 0x6A
    ENABLE_OPTICAL_DATA = 0x6B
    TOGGLE_OPTICAL_MODE = 0x6C
    SET_FF_VALUE = 0x78
    STOP_HAPTICS = 0x7A
    SELECT_WRIST = 0x7B
    GET_FF_VALUE = 0x80
    GET_ADVERTISING_NAME = 0x8D
    GET_HELLO = 0x91
    GET_BATTERY_PACK_INFO = 0x97


# Commands that change strap state. The CLI refuses these unless --allow-write.
MUTATING_CMDS = {
    Cmd.SET_CLOCK, Cmd.REBOOT_STRAP, Cmd.SET_ALARM_TIME, Cmd.DISABLE_ALARM,
    Cmd.SET_FF_VALUE, Cmd.SELECT_WRIST, Cmd.ABORT_HISTORICAL_TRANSMITS,
    Cmd.HISTORICAL_DATA_RESULT,
}


def enum_name(enum: type[IntEnum], value: int) -> str:
    try:
        return enum(value).name
    except ValueError:
        return f"0x{value:02X}"


# --- CRCs -----------------------------------------------------------------

def crc16_modbus(data: bytes) -> int:
    crc = 0xFFFF
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = (crc >> 1) ^ 0xA001 if crc & 1 else crc >> 1
    return crc


def crc32(data: bytes) -> int:
    return zlib.crc32(data) & 0xFFFFFFFF


# --- Frames ----------------------------------------------------------------

class FrameError(ValueError):
    pass


@dataclass
class Frame:
    inner: bytes
    role_a: int = 0x00
    role_b: int = 0x01
    version: int = VERSION

    @property
    def packet_type(self) -> int:
        return self.inner[0]

    @property
    def seq(self) -> int:
        return self.inner[1] if len(self.inner) > 1 else 0

    @property
    def command(self) -> int:
        return self.inner[2] if len(self.inner) > 2 else 0

    @property
    def params(self) -> bytes:
        return self.inner[3:]

    @property
    def type_name(self) -> str:
        return enum_name(PacketType, self.packet_type)

    def encode(self) -> bytes:
        return encode_frame(self.inner, role_a=self.role_a, role_b=self.role_b,
                            version=self.version)

    def describe(self) -> str:
        s = f"{self.type_name} seq={self.seq}"
        if self.packet_type in (PacketType.COMMAND, PacketType.COMMAND_RESPONSE,
                                PacketType.PUFFIN_COMMAND, PacketType.PUFFIN_COMMAND_RESPONSE):
            s += f" cmd={enum_name(Cmd, self.command)}"
        return s + f" [{len(self.inner)}B] {self.inner.hex()}"


def encode_frame(inner: bytes, *, role_a: int = 0x00, role_b: int = 0x01,
                 version: int = VERSION) -> bytes:
    length = len(inner) + TRAILER_LEN
    header = struct.pack("<BBHBB", SOF, version, length, role_a, role_b)
    return (header + struct.pack("<H", crc16_modbus(header)) + inner
            + struct.pack("<I", crc32(inner)))


def decode_frame(data: bytes) -> Frame:
    """Decode exactly one complete frame. Raises FrameError on any mismatch."""
    if len(data) < HEADER_LEN + TRAILER_LEN:
        raise FrameError(f"too short ({len(data)} bytes)")
    sof, version, length, role_a, role_b, hcrc = struct.unpack_from("<BBHBBH", data)
    if sof != SOF:
        raise FrameError(f"bad SOF 0x{sof:02X}")
    if hcrc != crc16_modbus(data[:6]):
        raise FrameError("header CRC16 mismatch")
    if len(data) != HEADER_LEN + length:
        raise FrameError(f"length field says {HEADER_LEN + length}, got {len(data)}")
    inner = bytes(data[HEADER_LEN:HEADER_LEN + length - TRAILER_LEN])
    (pcrc,) = struct.unpack_from("<I", data, HEADER_LEN + length - TRAILER_LEN)
    if pcrc != crc32(inner):
        raise FrameError("payload CRC32 mismatch")
    if not inner:
        raise FrameError("empty inner packet")
    return Frame(inner=inner, role_a=role_a, role_b=role_b, version=version)


@dataclass
class FrameAssembler:
    """Reassembles frames from a stream of BLE notification chunks.

    Frames larger than the negotiated MTU arrive split across notifications,
    and a notification may also carry the tail of one frame and the head of
    the next. Bytes that cannot start a valid header are dropped (and counted)
    so the assembler resynchronises after corruption.
    """
    buf: bytearray = field(default_factory=bytearray)
    dropped: int = 0

    def feed(self, chunk: bytes) -> list[Frame]:
        self.buf.extend(chunk)
        out: list[Frame] = []
        while True:
            start = self.buf.find(SOF)
            if start < 0:
                self.dropped += len(self.buf)
                self.buf.clear()
                return out
            if start:
                self.dropped += start
                del self.buf[:start]
            if len(self.buf) < HEADER_LEN:
                return out
            length = struct.unpack_from("<H", self.buf, 2)[0]
            if (crc16_modbus(bytes(self.buf[:6])) != struct.unpack_from("<H", self.buf, 6)[0]
                    or not TRAILER_LEN < length <= MAX_INNER_LEN):
                self.dropped += 1
                del self.buf[:1]
                continue
            total = HEADER_LEN + length
            if len(self.buf) < total:
                return out
            raw = bytes(self.buf[:total])
            try:
                out.append(decode_frame(raw))
                del self.buf[:total]
            except FrameError:
                self.dropped += 1
                del self.buf[:1]


# --- Commands --------------------------------------------------------------

_seq = count(1)


def next_seq() -> int:
    return next(_seq) & 0xFF


def build_command(cmd: int, params: bytes = b"", *, seq: int | None = None,
                  packet_type: int = PacketType.COMMAND) -> bytes:
    """Encode a command frame ready to write to CMD_WRITE_UUID."""
    inner = bytes([packet_type, next_seq() if seq is None else seq & 0xFF, cmd]) + params
    inner += b"\x00" * (-len(inner) % 4)
    return encode_frame(inner)


def set_clock_params(epoch_seconds: int) -> bytes:
    return struct.pack("<I", epoch_seconds) + b"\x00"


def toggle_params(enabled: bool) -> bytes:
    return bytes([1 if enabled else 0])


def toggle_imu_params(enabled: bool) -> bytes:
    return bytes([0x01, 1 if enabled else 0])  # [revision, enable]
