import struct

import pytest

from whoop5 import protocol as P
from whoop5.btsnoop import parse, write_btsnoop
from whoop5.cli import main
from whoop5.decoders import (CommandResponse, HistoricalHR, RealtimeHR,
                             decode_inner, decode_standard_hr)

# Publicly documented TOGGLE_IMU_MODE frame (see docs/PROTOCOL.md).
KNOWN = bytes.fromhex("aa010c000001e741" "23f16a0101000000" "58e961fc")


def test_crcs_match_known_frame():
    assert P.crc16_modbus(KNOWN[:6]) == 0x41E7
    assert P.crc32(KNOWN[8:16]) == 0xFC61E958


def test_build_command_reproduces_known_frame():
    frame = P.build_command(P.Cmd.TOGGLE_IMU_MODE, bytes([1, 1]), seq=0xF1)
    assert frame == KNOWN


def test_decode_roundtrip():
    f = P.decode_frame(KNOWN)
    assert f.packet_type == P.PacketType.COMMAND
    assert f.seq == 0xF1 and f.command == P.Cmd.TOGGLE_IMU_MODE
    assert f.encode() == KNOWN


@pytest.mark.parametrize("i", [0, 7, 9, 19])
def test_decode_rejects_corruption(i):
    bad = bytearray(KNOWN)
    bad[i] ^= 0x40
    with pytest.raises(P.FrameError):
        P.decode_frame(bytes(bad))


def test_commands_padded_to_four():
    inner = P.decode_frame(P.build_command(P.Cmd.GET_HELLO, seq=1)).inner
    assert inner == bytes([0x23, 0x01, 0x91, 0x00])


def test_assembler_handles_fragments_and_garbage():
    a, b = P.build_command(1, seq=1), P.build_command(0x1A, seq=2)
    stream = b"\x00\xaa\x13" + a + b
    asm = P.FrameAssembler()
    frames = []
    for i in range(0, len(stream), 5):
        frames += asm.feed(stream[i:i + 5])
    assert [f.command for f in frames] == [1, 0x1A]
    assert asm.dropped == 3


def _realtime(bpm, rr):
    inner = bytes([0x28, 0x02]) + struct.pack("<I", 1_750_000_000) + b"\x00\x00"
    inner += bytes([bpm, 1]) + struct.pack("<H", rr) + bytes(6) + b"\x01\x00"
    return P.Frame(inner)


def test_realtime_hr():
    rec = decode_inner(_realtime(61, 983))
    assert rec == RealtimeHR(unix_ts=1_750_000_000, bpm=61, valid=True, rr_ms=983)


def test_historical_hr():
    inner = bytearray(116)
    inner[0], inner[1] = 0x2F, 18
    struct.pack_into("<H", inner, 2, 77)
    inner[14], inner[15] = 55, 1
    struct.pack_into("<H", inner, 16, 1090)
    inner[29] = 53
    struct.pack_into("<4f", inner, 33, 1.0, 0.0, 0.0, 0.0)
    rec = decode_inner(P.Frame(bytes(inner)))
    assert isinstance(rec, HistoricalHR)
    assert (rec.sequence, rec.bpm, rec.rr_ms, rec.smoothed_bpm) == (77, 55, 1090, 53)
    assert rec.quaternion == (1.0, 0.0, 0.0, 0.0)


def test_command_response():
    rec = decode_inner(P.Frame(bytes([0x24, 5, 0x1A, 0x01, 0x55])))
    assert rec == CommandResponse(command="GET_BATTERY_LEVEL", seq=5, body="0155")


def test_standard_hr():
    rec = decode_standard_hr(bytes([0x16, 72]) + struct.pack("<H", 1024))
    assert rec.bpm == 72 and rec.contact is True and rec.rr_ms == [1000.0]


def _acl(conn, pb, payload):
    return bytes([0x02]) + struct.pack("<HH", conn | pb << 12, len(payload)) + payload


def _att(op, handle, value):
    att = bytes([op]) + struct.pack("<H", handle) + value
    return struct.pack("<HH", len(att), 4) + att


def test_btsnoop_extracts_and_reassembles():
    # Characteristic discovery: value handle 0x0021 is fd4b0003 (128-bit UUID).
    uuid = bytes.fromhex(P.CMD_RESPONSE_UUID.replace("-", ""))[::-1]
    decl = struct.pack("<HBH", 0x0020, 0x10, 0x0021) + uuid
    disc = bytes([0x09, 21]) + decl
    disc_l2 = struct.pack("<HH", len(disc), 4) + disc

    write = _att(0x52, 0x001E, P.build_command(P.Cmd.GET_HELLO, seq=3))
    notify = _att(0x1B, 0x0021, _realtime(64, 900).encode())
    capture = write_btsnoop([
        (True, _acl(0x40, 2, disc_l2)),
        (False, _acl(0x40, 0, write)),
        (True, _acl(0x40, 2, notify[:10])),   # fragmented across two ACL packets
        (True, _acl(0x40, 1, notify[10:])),
    ])
    events = parse(capture)
    assert [e.op for e in events] == ["write_cmd", "notify"]
    assert events[0].direction == "app->strap"
    assert P.decode_frame(events[0].value).command == P.Cmd.GET_HELLO
    assert events[1].uuid == P.CMD_RESPONSE_UUID
    assert decode_inner(P.decode_frame(events[1].value)).bpm == 64


def test_cli_decode_btsnoop(tmp_path, capsys):
    path = tmp_path / "btsnoop_hci.log"
    path.write_bytes(write_btsnoop([(True, _acl(1, 2, _att(0x1B, 0x21, _realtime(70, 850).encode())))]))
    main(["decode", str(path)])
    out = capsys.readouterr().out
    assert "REALTIME_DATA" in out and '"bpm": 70' in out
