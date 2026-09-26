"""Live BLE access to the strap via bleak (Linux/BlueZ, Windows, macOS).

The fd4b characteristics require an encrypted, bonded link. On Linux and
Windows we can bond ourselves with client.pair(). On macOS/iOS a third party
cannot trigger pairing (there is no readable encrypted characteristic), so the
strap must already be bonded to that computer.
"""
from __future__ import annotations

import asyncio
import json
import sys
import time
from collections.abc import Callable
from typing import IO

from bleak import BleakClient, BleakScanner
from bleak.backends.device import BLEDevice

from . import protocol as P
from .decoders import decode_inner, decode_standard_hr, to_dict


async def scan(timeout: float = 10.0) -> list[tuple[BLEDevice, int, str]]:
    found = await BleakScanner.discover(timeout=timeout, return_adv=True)
    out = []
    for dev, adv in found.values():
        uuids = [u.lower() for u in adv.service_uuids]
        name = adv.local_name or dev.name or ""
        if "whoop" in name.lower() or P.SERVICE_UUID in uuids or P.GEN4_SERVICE_UUID in uuids:
            gen = "5.0/MG" if P.SERVICE_UUID in uuids else "4.0" if P.GEN4_SERVICE_UUID in uuids else "?"
            out.append((dev, adv.rssi, gen))
    return out


class Strap:
    def __init__(self, address: str, log: IO[str] | None = None, pair: bool = True):
        self.address = address
        self.client = BleakClient(address, timeout=30.0)
        self.log = log
        self.pair = pair
        self.assemblers: dict[str, P.FrameAssembler] = {}
        self.on_record: Callable[[str, object], None] | None = None

    async def __aenter__(self) -> "Strap":
        await self.client.connect()
        if self.pair:
            try:
                await asyncio.wait_for(self.client.pair(), timeout=30)
            except NotImplementedError:
                print("! pairing not supported on this OS; relying on existing bond", file=sys.stderr)
            except Exception as e:  # already bonded, or strap not in pairing mode
                print(f"! pair() failed: {e!r}", file=sys.stderr)
        return self

    async def __aexit__(self, *exc) -> None:
        await self.client.disconnect()

    def _log(self, direction: str, char: str, data: bytes) -> None:
        if self.log:
            self.log.write(json.dumps({"t": time.time(), "dir": direction,
                                       "char": char, "hex": data.hex()}) + "\n")
            self.log.flush()

    def _emit(self, char: str, record: object) -> None:
        if self.on_record:
            self.on_record(char, record)

    def _handler(self, uuid: str):
        name = P.UUID_NAMES.get(uuid, uuid)
        asm = self.assemblers.setdefault(uuid, P.FrameAssembler())

        def cb(_sender, data: bytearray) -> None:
            raw = bytes(data)
            self._log("rx", uuid, raw)
            if uuid == P.HR_MEASUREMENT_UUID:
                rec = decode_standard_hr(raw)
                if rec:
                    self._emit(name, rec)
                return
            for frame in asm.feed(raw):
                self._emit(name, decode_inner(frame))
        return cb

    async def subscribe_all(self) -> list[str]:
        """Subscribe to every notify/indicate characteristic. Returns failures."""
        failed = []
        for svc in self.client.services:
            for ch in svc.characteristics:
                if not {"notify", "indicate"} & set(ch.properties):
                    continue
                try:
                    await asyncio.wait_for(
                        self.client.start_notify(ch, self._handler(ch.uuid.lower())), timeout=8)
                except Exception as e:
                    failed.append(f"{ch.uuid}: {e!r}")
        return failed

    async def send(self, cmd: int, params: bytes = b"") -> bytes:
        frame = P.build_command(cmd, params)
        self._log("tx", P.CMD_WRITE_UUID, frame)
        await self.client.write_gatt_char(P.CMD_WRITE_UUID, frame, response=True)
        return frame

    async def gatt_dump(self) -> list[dict]:
        rows = []
        for svc in self.client.services:
            for ch in svc.characteristics:
                row = {"service": svc.uuid, "char": ch.uuid, "handle": ch.handle,
                       "props": ch.properties}
                if "read" in ch.properties:
                    try:
                        v = await self.client.read_gatt_char(ch)
                        row["value_hex"] = v.hex()
                        if v and all(32 <= b < 127 for b in v):
                            row["value_text"] = v.decode()
                    except Exception as e:
                        row["read_error"] = repr(e)
                rows.append(row)
        return rows


def print_record(char: str, record: object) -> None:
    d = to_dict(record)
    print(f"{time.strftime('%H:%M:%S')} {char:12s} " + json.dumps(d, default=str))
