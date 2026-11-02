#!/usr/bin/env python3
"""
Firestick Remote - a phone-friendly web remote for Amazon Fire TV / Firestick.

Runs on any computer (or Android phone via Termux) on the same Wi-Fi as the
Firestick and talks to it over ADB. Open the printed URL on your phone.

Requirements: Python 3.8+ and the `adb` tool (Android platform-tools).
No pip packages needed.

Usage:
    python3 server.py                 # then open http://<this-computer-ip>:8080
    python3 server.py --tv 192.168.1.50 --port 8080
"""

import argparse
import ipaddress
import json
import os
import shlex
import shutil
import socket
import subprocess
import sys
import threading
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(HERE, "config.json")

# Android key codes understood by `input keyevent`
KEYS = {
    "up": 19, "down": 20, "left": 21, "right": 22, "select": 23,
    "back": 4, "home": 3, "menu": 82,
    "playpause": 85, "rewind": 89, "fastforward": 90,
    "next": 87, "previous": 88,
    "volup": 24, "voldown": 25, "mute": 164,
    "sleep": 223, "wakeup": 224, "power": 26,
    "delete": 67, "enter": 66, "search": 84,
}

# App shortcuts: name -> package. Edit to taste.
APPS = {
    "Netflix": "com.netflix.ninja",
    "YouTube": "com.amazon.firetv.youtube",
    "Prime Video": "com.amazon.avod",
    "Disney+": "com.disney.disneyplus",
    "Stan": "au.com.stan.and",
    "Binge": "au.com.streamotion.ares.tv",
    "ABC iview": "au.net.abc.iview",
    "SBS On Demand": "com.sbs.ondemand.tv",
    "9Now": "au.com.nine.jumpin.android",
    "7plus": "au.com.seven.plus",
    "10 Play": "au.com.tenplay",
    "Kayo": "au.com.kayosports.tv",
    "Spotify": "com.spotify.tv.android",
    "Plex": "com.plexapp.android",
    "Kodi": "org.xbmc.kodi",
}


def load_config():
    try:
        with open(CONFIG_PATH) as f:
            return json.load(f)
    except Exception:
        return {}


def save_config(cfg):
    try:
        with open(CONFIG_PATH, "w") as f:
            json.dump(cfg, f, indent=2)
    except Exception as e:
        print(f"warning: could not save config: {e}")


class Adb:
    """Keeps a persistent `adb shell` open so key presses are instant."""

    def __init__(self, adb_path):
        self.adb = adb_path
        self.target = None  # "ip:5555"
        self.shell = None
        self.lock = threading.Lock()

    def _run(self, *args, timeout=15):
        r = subprocess.run([self.adb, *args], capture_output=True, text=True, timeout=timeout)
        return (r.stdout + r.stderr).strip()

    def connect(self, host):
        host = host.strip()
        if ":" not in host:
            host += ":5555"
        with self.lock:
            self._close_shell()
            out = self._run("connect", host)
            ok = "connected" in out and "unable" not in out and "failed" not in out
            if ok:
                self.target = host
                # Fails with "unauthorized" until "Allow" is pressed on the TV.
                state = self._run("-s", host, "get-state", timeout=5)
                if "device" not in state:
                    return False, (
                        "Connected, but the Firestick hasn't authorised this computer yet. "
                        "Look at the TV and choose 'Allow' (tick 'Always allow'), then press Connect again. "
                        f"[{state}]"
                    )
            return ok, out

    def status(self):
        if not self.target:
            return "disconnected"
        try:
            state = self._run("-s", self.target, "get-state", timeout=5)
        except Exception:
            return "disconnected"
        if state == "device":
            return "connected"
        if "unauthorized" in state:
            return "unauthorized"
        return "disconnected"

    def _close_shell(self):
        if self.shell:
            try:
                self.shell.kill()
            except Exception:
                pass
            self.shell = None

    def _ensure_shell(self):
        if self.shell and self.shell.poll() is None:
            return
        self.shell = subprocess.Popen(
            [self.adb, "-s", self.target, "shell"],
            stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            text=True, bufsize=1,
        )

    def send(self, cmd):
        """Fire-and-forget shell command via the persistent shell."""
        if not self.target:
            raise RuntimeError("Not connected to a Firestick")
        with self.lock:
            try:
                self._ensure_shell()
                self.shell.stdin.write(cmd + "\n")
                self.shell.stdin.flush()
            except (BrokenPipeError, OSError):
                # Shell died (e.g. the TV slept and dropped ADB): reconnect and retry once.
                self._close_shell()
                self._run("connect", self.target)
                self._ensure_shell()
                self.shell.stdin.write(cmd + "\n")
                self.shell.stdin.flush()

    def query(self, cmd, timeout=15):
        if not self.target:
            raise RuntimeError("Not connected to a Firestick")
        return self._run("-s", self.target, "shell", cmd, timeout=timeout)


def local_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()


def scan_for_adb(port=5555):
    """Look for devices on the local /24 with the ADB port open."""
    net = ipaddress.ip_network(local_ip() + "/24", strict=False)

    def probe(ip):
        try:
            with socket.create_connection((str(ip), port), timeout=0.4):
                return str(ip)
        except Exception:
            return None

    with ThreadPoolExecutor(max_workers=64) as ex:
        return [ip for ip in ex.map(probe, net.hosts()) if ip]


def escape_text(text):
    # `input text` treats spaces specially; %s is a space.
    return shlex.quote(text.replace("%", "\\%").replace(" ", "%s"))


class Handler(BaseHTTPRequestHandler):
    adb: Adb = None
    cfg: dict = {}

    def log_message(self, fmt, *args):
        pass  # keep the console quiet

    def _json(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _file(self, name, ctype):
        path = os.path.join(HERE, "static", name)
        try:
            with open(path, "rb") as f:
                data = f.read()
        except FileNotFoundError:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        p = self.path.split("?")[0]
        if p in ("/", "/index.html"):
            return self._file("index.html", "text/html; charset=utf-8")
        if p == "/manifest.json":
            return self._file("manifest.json", "application/manifest+json")
        if p == "/icon.svg":
            return self._file("icon.svg", "image/svg+xml")
        if p == "/api/status":
            return self._json(200, {
                "status": self.adb.status(),
                "tv": self.adb.target or self.cfg.get("tv", ""),
                "apps": list(APPS.keys()),
            })
        self.send_error(404)

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        try:
            data = json.loads(self.rfile.read(length) or b"{}")
        except Exception:
            data = {}
        p = self.path
        try:
            if p == "/api/connect":
                host = data.get("host") or self.cfg.get("tv", "")
                if not host:
                    return self._json(400, {"ok": False, "msg": "Enter the Firestick's IP address"})
                ok, msg = self.adb.connect(host)
                if ok:
                    self.cfg["tv"] = host
                    save_config(self.cfg)
                return self._json(200, {"ok": ok, "msg": msg})

            if p == "/api/scan":
                return self._json(200, {"ok": True, "found": scan_for_adb()})

            if p == "/api/key":
                key = data.get("key")
                if key not in KEYS:
                    return self._json(400, {"ok": False, "msg": f"Unknown key {key}"})
                flag = "--longpress " if data.get("long") else ""
                self.adb.send(f"input keyevent {flag}{KEYS[key]}")
                return self._json(200, {"ok": True})

            if p == "/api/text":
                text = data.get("text", "")
                if text:
                    self.adb.send(f"input text {escape_text(text)}")
                return self._json(200, {"ok": True})

            if p == "/api/app":
                pkg = APPS.get(data.get("name"))
                if not pkg:
                    return self._json(400, {"ok": False, "msg": "Unknown app"})
                out = self.adb.query(f"monkey -p {pkg} -c android.intent.category.LAUNCHER 1")
                ok = "No activities found" not in out and "aborted" not in out.lower()
                return self._json(200, {"ok": ok, "msg": "" if ok else f"{data.get('name')} isn't installed"})

            if p == "/api/settings":
                self.adb.send("am start -a android.settings.SETTINGS")
                return self._json(200, {"ok": True})

            self.send_error(404)
        except Exception as e:
            self._json(500, {"ok": False, "msg": str(e)})


def find_adb():
    for cand in (shutil.which("adb"), os.path.join(HERE, "platform-tools", "adb"),
                 os.path.join(HERE, "platform-tools", "adb.exe")):
        if cand and os.path.exists(cand):
            return cand
    return None


def main():
    ap = argparse.ArgumentParser(description="Web remote for Fire TV / Firestick")
    ap.add_argument("--tv", help="Firestick IP address (remembered for next time)")
    ap.add_argument("--port", type=int, default=8080, help="Web port (default 8080)")
    args = ap.parse_args()

    adb_path = find_adb()
    if not adb_path:
        print("Could not find 'adb'. Install Android platform-tools (see README.md),\n"
              "or unzip platform-tools into this folder.")
        sys.exit(1)

    cfg = load_config()
    if args.tv:
        cfg["tv"] = args.tv
        save_config(cfg)

    adb = Adb(adb_path)
    Handler.adb = adb
    Handler.cfg = cfg

    if cfg.get("tv"):
        ok, msg = adb.connect(cfg["tv"])
        print(("Connected to " if ok else "Could not connect to ") + cfg["tv"] + f"  ({msg})")

    server = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    print(f"\n  Firestick Remote is running.\n  Open on your phone:  http://{local_ip()}:{args.port}\n")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
