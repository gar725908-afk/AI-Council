from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.request
from pathlib import Path

HOST = "127.0.0.1"
PORT = int(os.environ.get("AI_COUNCIL_BRIDGE_PORT", "8765"))
BASE = f"http://{HOST}:{PORT}"
STATE_FILE = Path(os.environ.get("APPDATA", str(Path.home()))) / "AI Council" / "bridge" / "state.json"


def state():
    return json.loads(STATE_FILE.read_text(encoding="utf-8"))


def request(path: str, payload=None, auth=False):
    data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    if auth:
        token = state().get("token", "")
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method="POST" if data is not None else "GET")
    with urllib.request.urlopen(req, timeout=30) as r:
        raw = r.read().decode("utf-8")
    return json.loads(raw)


def main():
    p = argparse.ArgumentParser(description="AI Council Local Bridge client")
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("health")
    sub.add_parser("status")
    sub.add_parser("on")
    sub.add_parser("off")
    s = sub.add_parser("screen")
    s.add_argument("--path")
    m = sub.add_parser("mouse")
    m.add_argument("action", choices=["move", "click", "down", "up", "drag", "scroll"])
    m.add_argument("--x", type=int); m.add_argument("--y", type=int); m.add_argument("--x1", type=int); m.add_argument("--y1", type=int); m.add_argument("--x2", type=int); m.add_argument("--y2", type=int)
    m.add_argument("--button", default="left"); m.add_argument("--clicks", type=int, default=1); m.add_argument("--duration", type=float, default=0.3)
    m.add_argument("--interval", type=float, default=0.05)
    k = sub.add_parser("text"); k.add_argument("text")
    k = sub.add_parser("press"); k.add_argument("key"); k.add_argument("--presses", type=int, default=1)
    k = sub.add_parser("hotkey"); k.add_argument("keys", nargs="+")
    sh = sub.add_parser("shell"); sh.add_argument("command"); sh.add_argument("--shell", choices=["powershell", "cmd"], default="powershell"); sh.add_argument("--cwd")
    f = sub.add_parser("read"); f.add_argument("path")
    f = sub.add_parser("write"); f.add_argument("path"); f.add_argument("text")
    f = sub.add_parser("ls"); f.add_argument("path")
    pr = sub.add_parser("process"); pr.add_argument("action", choices=["list", "start", "kill"]); pr.add_argument("--command"); pr.add_argument("--pid", type=int)
    args = p.parse_args()

    if args.cmd == "health": result = request("/health")
    elif args.cmd == "status": result = request("/status")
    elif args.cmd == "on": result = request("/access/on")
    elif args.cmd == "off": result = request("/access/off")
    elif args.cmd == "screen": result = request("/screen", {"path": args.path}, auth=True)
    elif args.cmd == "mouse":
        result = request("/mouse", vars(args), auth=True)
    elif args.cmd == "text": result = request("/keyboard", {"action":"text", "text":args.text}, auth=True)
    elif args.cmd == "press": result = request("/keyboard", {"action":"press", "key":args.key, "presses":args.presses}, auth=True)
    elif args.cmd == "hotkey": result = request("/keyboard", {"action":"hotkey", "keys":args.keys}, auth=True)
    elif args.cmd == "shell": result = request("/shell", {"command":args.command, "shell":args.shell, "cwd":args.cwd}, auth=True)
    elif args.cmd == "read": result = request("/file", {"action":"read", "path":args.path}, auth=True)
    elif args.cmd == "write": result = request("/file", {"action":"write", "path":args.path, "text":args.text}, auth=True)
    elif args.cmd == "ls": result = request("/file", {"action":"list", "path":args.path}, auth=True)
    elif args.cmd == "process": result = request("/process", {"action":args.action, "command":args.command, "pid":args.pid}, auth=True)
    else: raise SystemExit("unknown command")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
