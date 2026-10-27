#!/usr/bin/env python3
"""Exercise the packaged plugin in a real TransferProxy 1.2.1 process.

Requires only Python's standard library, Java 17+, and an official proxy JAR.
All listeners bind to loopback; all DB requests use a small synthetic fixture.
"""
import argparse
import datetime as dt
import http.cookiejar
import http.server
import io
import json
from pathlib import Path
import shutil
import socket
import struct
import subprocess
import threading
import time
import urllib.request
import uuid
import zipfile
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parent.parent
PASSWORD = "local-preview-only-4283"


def port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def wait_for(check, label, seconds=15):
    until = time.monotonic() + seconds
    while time.monotonic() < until:
        try:
            if check():
                return
        except (OSError, ValueError, KeyError):
            pass
        time.sleep(.1)
    raise AssertionError(f"Timed out: {label}")


class Target:
    def __init__(self):
        self.port = port()
        self.sock = None
        self.thread = None
        self.start()

    def start(self):
        self.sock = socket.socket()
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(("127.0.0.1", self.port))
        self.sock.listen(100)
        self.sock.settimeout(.1)
        sock = self.sock

        def accept():
            while True:
                try:
                    client, _ = sock.accept()
                    client.close()
                except socket.timeout:
                    continue
                except OSError:
                    return
        self.thread = threading.Thread(target=accept, daemon=True)
        self.thread.start()

    def close(self):
        if self.sock:
            self.sock.close()
            self.thread.join(1)
            self.sock = None

    @property
    def address(self):
        return f"127.0.0.1:{self.port}"


def varint(value):
    result = bytearray()
    while True:
        result.append((value & 127) | (128 if value > 127 else 0))
        value >>= 7
        if not value:
            return bytes(result)


def string(value):
    data = value.encode()
    return varint(len(data)) + data


def read_varint(stream):
    result = 0
    for i in range(5):
        byte = stream.read(1)
        if not byte:
            raise EOFError("Proxy closed the connection")
        result |= (byte[0] & 127) << (7 * i)
        if not byte[0] & 128:
            return result
    raise ValueError("Invalid VarInt")


def player(proxy_port, name):
    with socket.create_connection(("127.0.0.1", proxy_port), timeout=5) as sock:
        stream = sock.makefile("rb")

        def send(packet_id, data=b""):
            payload = varint(packet_id) + data
            sock.sendall(varint(len(payload)) + payload)

        def receive():
            size = read_varint(stream)
            payload = io.BytesIO(stream.read(size))
            return read_varint(payload), payload

        send(0, varint(774) + string("localhost") + struct.pack(">H", proxy_port) + varint(2))
        send(0, string(name) + uuid.uuid4().bytes)
        packet_id, _ = receive()
        assert packet_id == 2, f"Expected login success, got {packet_id}"
        send(3)  # Login acknowledged, enter CONFIG state.
        send(0, string("ko_kr") + bytes([8, 0, 1, 127, 1, 0, 1, 0]))
        packet_id, payload = receive()
        if packet_id == 0x0b:
            host = payload.read(read_varint(payload)).decode()
            return host, read_varint(payload)
        assert packet_id == 2, f"Expected transfer or disconnect, got {packet_id}"
        return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True)
    parser.add_argument("--proxy", required=True, type=Path)
    parser.add_argument("--plugin", type=Path, default=ROOT / "build/libs/ISPSender-1.1.1.jar")
    parser.add_argument("--workdir", required=True, type=Path, help="New empty directory for disposable test data")
    parser.add_argument("--keep", action="store_true", help="Keep the passing test proxy alive for browser QA")
    args = parser.parse_args()
    args.workdir.mkdir(parents=True, exist_ok=False)
    work = args.workdir.resolve()
    plugin_dir = work / "plugins/ISPSender"
    plugin_dir.mkdir(parents=True)
    shutil.copy2(args.plugin, work / "plugins/ISPSender.jar")
    proxy_port, web_port = port(), port()
    with zipfile.ZipFile(args.proxy) as jar:
        config = jar.read("config.yml").decode().replace('"0.0.0.0"', '"127.0.0.1"').replace('bind-port: 25565', f'bind-port: {proxy_port}').replace('use-epoll: true', 'use-epoll: false')
    (work / "config.yml").write_text(config)
    fixture = (ROOT / "src/test/resources/mmdb/ipv4-24.mmdb").read_bytes()
    downloads = []

    class DatabaseHandler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            downloads.append(self.path)
            self.send_response(200)
            self.send_header("Content-Length", str(len(fixture)))
            self.end_headers()
            self.wfile.write(fixture)

        def log_message(self, *_):
            pass

    db_server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), DatabaseHandler)
    threading.Thread(target=db_server.serve_forever, daemon=True).start()
    targets = [Target() for _ in range(3)]
    cfg = {
        "token": "test-token", "database-url": f"http://127.0.0.1:{db_server.server_port}/db?token={{token}}",
        "default-server": [targets[0].address, targets[1].address], "asn-servers": {}, "country-servers": {},
        "fallback-servers": [targets[2].address], "fallback-enabled": True,
        "web-enabled": True, "web-bind": "127.0.0.1", "web-port": web_port, "web-password": PASSWORD,
    }
    # JSON is valid YAML and is read by the proxy's real YAML mapper.
    (plugin_dir / "config.yml").write_text(json.dumps(cfg))
    (plugin_dir / "ipinfo_lite.mmdb").write_bytes(fixture)
    today = dt.datetime.now(ZoneInfo("Asia/Seoul")).date()
    stamp = plugin_dir / "ipinfo_lite.mmdb.download-date"
    stamp.write_text(str(today))
    output = (work / "proxy.log").open("a")
    proxy = None
    base = f"http://127.0.0.1:{web_port}"
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    csrf = ""

    def request(path, data=None, method=None):
        headers = {"Content-Type": "application/json", "X-CSRF-Token": csrf}
        req = urllib.request.Request(base + path, data=None if data is None else json.dumps(data).encode(), headers=headers, method=method)
        with opener.open(req, timeout=5) as response:
            return json.load(response)

    def state():
        return request("/api/state")

    def start():
        nonlocal proxy, csrf
        proxy = subprocess.Popen([args.java, "-jar", str(args.proxy.resolve())], cwd=work, stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT, text=True)
        wait_for(lambda: urllib.request.urlopen(base, timeout=1).status == 200, "web listener startup")
        csrf = request("/api/login", {"password": PASSWORD})["csrf"]
        wait_for(lambda: all(s["alive"] for s in state()["servers"]), "initial health checks")
        # TransferProxy starts plugins before binding its Minecraft listener.
        def minecraft_ready():
            with socket.create_connection(("127.0.0.1", proxy_port), timeout=1):
                return True
        wait_for(minecraft_ready, "Minecraft listener startup")

    def stop():
        if proxy and proxy.poll() is None:
            proxy.stdin.write("stop\n")
            proxy.stdin.flush()
            try:
                proxy.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proxy.kill()
                proxy.wait()

    def save(values):
        return request("/api/config", values, "PUT")

    try:
        start()
        assert len(downloads) == 0, "Current-day database should be reused"
        first = {player(proxy_port, f"Balance{i}") for i in range(40)}
        assert first == {("127.0.0.1", t.port) for t in targets[:2]}, first
        print("PASS: real Minecraft clients receive both default transfer destinations", flush=True)
        targets[0].close()
        wait_for(lambda: not next(s["alive"] for s in state()["servers"] if s["server"] == targets[0].address), "10-second health failure detection")
        for i in range(5):
            assert player(proxy_port, f"OneAlive{i}") == ("127.0.0.1", targets[1].port)
        print("PASS: dead default excluded after a scheduled probe", flush=True)
        targets[1].close()
        wait_for(lambda: sum(s["alive"] for s in state()["servers"]) == 1, "all default servers down")
        assert player(proxy_port, "Fallback") == ("127.0.0.1", targets[2].port)
        print("PASS: fallback transfer when ordinary destinations are down", flush=True)
        proxy.stdin.write("fallback off\n"); proxy.stdin.flush()
        wait_for(lambda: state()["config"]["fallback-enabled"] is False, "console fallback off")
        assert player(proxy_port, "NoFallback") is None
        assert 'fallback-enabled: false' in (plugin_dir / "config.yml").read_text()
        proxy.stdin.write("fallback on\n"); proxy.stdin.flush()
        wait_for(lambda: state()["config"]["fallback-enabled"] is True, "console fallback on")
        assert player(proxy_port, "FallbackOn") == ("127.0.0.1", targets[2].port)
        print("PASS: console fallback off/on persists and changes real player behavior", flush=True)
        targets[2].close()
        wait_for(lambda: not any(s["alive"] for s in state()["servers"]), "all destinations down")
        assert player(proxy_port, "AllDown") is None
        for t in targets:
            t.start()
        wait_for(lambda: all(s["alive"] for s in state()["servers"]), "server recovery")
        assert player(proxy_port, "Recovered")[1] in {t.port for t in targets[:2]}
        print("PASS: all-down kick and automatic recovery", flush=True)
        stop(); start()
        assert len(downloads) == 0, "Same-day restart downloaded the DB"
        assert request(f"/api/logs?date={today}"), "Historical logs lost on restart"
        stop(); stamp.write_text(str(today - dt.timedelta(days=1))); start()
        wait_for(lambda: state().get("lastDownload") == str(today), "missed-day download")
        assert len(downloads) == 1, downloads
        assert stamp.read_text() == str(today)
        assert state()["databaseLoaded"]
        player(proxy_port, "AfterRestart")
        print("PASS: same-day reuse, missed-day startup refresh, persisted logs and validated MMDB swap", flush=True)
        print(f"ALL SMOKE CHECKS PASSED · Web preview: {base}", flush=True)
        if args.keep:
            input("Preview is running. Press Enter to stop.\n")
    finally:
        stop()
        for t in targets:
            t.close()
        db_server.shutdown()
        output.close()


if __name__ == "__main__":
    main()
