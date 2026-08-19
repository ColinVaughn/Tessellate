#!/usr/bin/env python3
"""Small Minecraft RCON client used by the benchmark tools.

The dev server is driven over RCON rather than stdin because Gradle does not wire stdin
through to the server process.

Usage:
    python tools/rcon.py "command"           run one command, print the reply
    python tools/rcon.py -f commands.txt     run one command per line
    python tools/rcon.py --wait 180          block until the server answers, then exit
"""

import argparse
import os
import socket
import struct
import sys
import time

HOST = "127.0.0.1"
PORT = 25585
PASSWORD = os.environ.get("TESSELLATE_RCON_PASSWORD")

TYPE_LOGIN = 3
TYPE_COMMAND = 2
TYPE_RESPONSE = 0


class RconError(Exception):
    pass


class Rcon:
    def __init__(self, host=HOST, port=PORT, password=PASSWORD, timeout=30.0):
        self.host = host
        self.port = port
        self.password = password
        self.timeout = timeout
        self.sock = None
        self._request_id = 0

    def __enter__(self):
        self.connect()
        return self

    def __exit__(self, *_):
        self.close()

    def connect(self):
        if not self.password:
            raise RconError("set TESSELLATE_RCON_PASSWORD or pass --password")
        self.sock = socket.create_connection((self.host, self.port), timeout=self.timeout)
        self.sock.settimeout(self.timeout)
        request_id = self._send(TYPE_LOGIN, self.password)
        resp_id, _, _ = self._recv()
        # A login failure is signalled by request id -1.
        if resp_id == -1 or resp_id != request_id:
            raise RconError("RCON authentication failed")

    def close(self):
        if self.sock:
            self.sock.close()
            self.sock = None

    def _send(self, packet_type, payload):
        self._request_id += 1
        body = struct.pack("<ii", self._request_id, packet_type) + payload.encode("utf-8") + b"\x00\x00"
        self.sock.sendall(struct.pack("<i", len(body)) + body)
        return self._request_id

    def _read_exactly(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise RconError("connection closed by server")
            buf += chunk
        return buf

    def _recv(self):
        (length,) = struct.unpack("<i", self._read_exactly(4))
        body = self._read_exactly(length)
        request_id, packet_type = struct.unpack("<ii", body[:8])
        payload = body[8:-2].decode("utf-8", errors="replace")
        return request_id, packet_type, payload

    def command(self, cmd):
        self._send(TYPE_COMMAND, cmd)
        _, _, payload = self._recv()
        return payload


def wait_for_server(seconds, host=HOST, port=PORT, password=PASSWORD):
    """Wait until RCON accepts a command."""
    deadline = time.time() + seconds
    while time.time() < deadline:
        try:
            with Rcon(host, port, password, timeout=5.0) as rcon:
                rcon.command("list")
                return True
        except (OSError, RconError):
            time.sleep(2.0)
    return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", nargs="?", help="single command to run")
    parser.add_argument("-f", "--file", help="file of commands, one per line")
    parser.add_argument("--wait", type=float, metavar="SECONDS",
                        help="wait until the server answers, then exit")
    parser.add_argument("--host", default=HOST)
    parser.add_argument("--port", type=int, default=PORT)
    parser.add_argument("--password", default=PASSWORD)
    args = parser.parse_args()

    if args.wait is not None:
        ok = wait_for_server(args.wait, args.host, args.port, args.password)
        print("server ready" if ok else "TIMEOUT waiting for server")
        return 0 if ok else 1

    commands = []
    if args.command:
        commands.append(args.command)
    if args.file:
        with open(args.file, encoding="utf-8") as handle:
            commands.extend(
                line.strip() for line in handle
                if line.strip() and not line.strip().startswith("#")
            )
    if not commands:
        parser.error("give a command, -f FILE, or --wait")

    with Rcon(args.host, args.port, args.password) as rcon:
        for cmd in commands:
            reply = rcon.command(cmd)
            print(f"> {cmd}")
            if reply.strip():
                print(reply)
    return 0


if __name__ == "__main__":
    sys.exit(main())
