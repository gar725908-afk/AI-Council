from __future__ import annotations

import base64
import json
import os
import secrets
import subprocess
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

HOST = "127.0.0.1"
PORT = int(os.environ.get("AI_COUNCIL_BRIDGE_PORT", "8765"))
APP_ROOT = Path(os.environ.get("AI_COUNCIL_ROOT", r"C:\AI-Council"))
STATE_DIR = Path(os.environ.get("APPDATA", str(Path.home()))) / "AI Council" / "bridge"
STATE_FILE = STATE_DIR / "state.json"
LOCK = threading.RLock()


def _default_state() -> dict[str, Any]:
    return {
        "enabled": False,
        "mode": "OFF",
        "token": "",
        "session_id": "",
        "enabled_at": None,
        "last_command_at": None,
        "emergency_stop": False,
    }


def load_state() -> dict[str, Any]:
    with LOCK:
        STATE_DIR.mkdir(parents=True, exist_ok=True)
        if not STATE_FILE.exists():
            return _default_state()
        try:
            data = json.loads(STATE_FILE.read_text(encoding="utf-8"))
            out = _default_state()
            if isinstance(data, dict):
                out.update(data)
            return out
        except Exception:
            return _default_state()


def save_state(state: dict[str, Any]) -> None:
    with LOCK:
        STATE_DIR.mkdir(parents=True, exist_ok=True)
        tmp = STATE_FILE.with_suffix(".tmp")
        tmp.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")
        os.replace(tmp, STATE_FILE)


def set_access(enabled: bool, mode: str = "FULL") -> dict[str, Any]:
    with LOCK:
        state = load_state()
        if enabled:
            state.update(
                enabled=True,
                mode=mode,
                token=secrets.token_urlsafe(48),
                session_id=secrets.token_hex(16),
                enabled_at=time.strftime("%Y-%m-%dT%H:%M:%S"),
                emergency_stop=False,
            )
        else:
            state.update(
                enabled=False,
                mode="OFF",
                token="",
                session_id="",
                emergency_stop=True,
            )
        save_state(state)
        return state


def _authorized(headers) -> bool:
    state = load_state()
    if not state.get("enabled") or state.get("emergency_stop"):
        return False
    auth = headers.get("Authorization", "")
    expected = f"Bearer {state.get('token', '')}"
    return bool(state.get("token")) and secrets.compare_digest(auth, expected)


def _touch() -> None:
    state = load_state()
    state["last_command_at"] = time.strftime("%Y-%m-%dT%H:%M:%S")
    save_state(state)


def _lazy_input():
    try:
        import pyautogui
        return pyautogui
    except Exception as exc:
        raise RuntimeError(f"PyAutoGUI недоступен: {exc}") from exc


def do_screenshot(path: str | None = None) -> dict[str, Any]:
    pyautogui = _lazy_input()
    if path:
        dest = Path(path)
    else:
        dest = Path(tempfile.gettempdir()) / f"ai_council_screen_{int(time.time())}.png"
    dest.parent.mkdir(parents=True, exist_ok=True)
    image = pyautogui.screenshot()
    image.save(dest, format="PNG")
    return {"path": str(dest), "width": image.width, "height": image.height}


def do_mouse(payload: dict[str, Any]) -> dict[str, Any]:
    pyautogui = _lazy_input()
    action = str(payload.get("action", "click"))
    if action == "move":
        pyautogui.moveTo(int(payload["x"]), int(payload["y"]), duration=float(payload.get("duration", 0)))
    elif action == "click":
        pyautogui.click(int(payload["x"]), int(payload["y"]), button=str(payload.get("button", "left")), clicks=int(payload.get("clicks", 1)), interval=float(payload.get("interval", 0.05)))
    elif action == "down":
        pyautogui.moveTo(int(payload["x"]), int(payload["y"]), duration=0)
        pyautogui.mouseDown(button=str(payload.get("button", "left")))
    elif action == "up":
        pyautogui.moveTo(int(payload["x"]), int(payload["y"]), duration=0)
        pyautogui.mouseUp(button=str(payload.get("button", "left")))
    elif action == "drag":
        pyautogui.moveTo(int(payload["x1"]), int(payload["y1"]), duration=0.1)
        pyautogui.dragTo(int(payload["x2"]), int(payload["y2"]), duration=float(payload.get("duration", 0.3)), button=str(payload.get("button", "left")))
    elif action == "scroll":
        pyautogui.moveTo(int(payload.get("x", pyautogui.position().x)), int(payload.get("y", pyautogui.position().y)), duration=0)
        pyautogui.scroll(int(payload.get("clicks", 1)))
    else:
        raise ValueError(f"Неизвестное mouse action: {action}")
    return {"ok": True, "action": action, "position": list(pyautogui.position())}


def do_keyboard(payload: dict[str, Any]) -> dict[str, Any]:
    pyautogui = _lazy_input()
    action = str(payload.get("action", "text"))
    if action == "text":
        text = str(payload.get("text", ""))
        if not text:
            return {"ok": True, "typed": 0}
        try:
            import pyperclip
            pyperclip.copy(text)
            pyautogui.hotkey("ctrl", "v")
        except Exception:
            # ASCII fallback; clipboard path is preferred because it supports Unicode.
            pyautogui.write(text, interval=float(payload.get("interval", 0.0)))
        return {"ok": True, "typed": len(text)}
    if action == "press":
        pyautogui.press(payload["key"], presses=int(payload.get("presses", 1)), interval=float(payload.get("interval", 0.05)))
        return {"ok": True, "pressed": payload["key"]}
    if action == "hotkey":
        keys = [str(x) for x in payload.get("keys", [])]
        if not keys:
            raise ValueError("keys обязателен для hotkey")
        pyautogui.hotkey(*keys)
        return {"ok": True, "hotkey": keys}
    raise ValueError(f"Неизвестное keyboard action: {action}")


def do_shell(payload: dict[str, Any]) -> dict[str, Any]:
    command = str(payload.get("command", "")).strip()
    if not command:
        raise ValueError("command обязателен")
    cwd = str(payload.get("cwd") or APP_ROOT)
    timeout = max(1, min(int(payload.get("timeout", 60)), 900))
    shell_type = str(payload.get("shell", "powershell"))
    if shell_type == "cmd":
        argv = ["cmd.exe", "/d", "/s", "/c", command]
    else:
        argv = ["powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", command]
    completed = subprocess.run(argv, cwd=cwd, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)
    return {
        "ok": completed.returncode == 0,
        "returncode": completed.returncode,
        "stdout": completed.stdout[-20000:],
        "stderr": completed.stderr[-20000:],
        "cwd": cwd,
    }


def do_process(payload: dict[str, Any]) -> dict[str, Any]:
    action = str(payload.get("action", "list"))
    if action == "list":
        import psutil
        items = []
        for p in psutil.process_iter(["pid", "name", "exe", "cmdline"]):
            try:
                items.append(p.info)
            except Exception:
                pass
        return {"ok": True, "processes": items[:2000]}
    if action == "start":
        command = str(payload.get("command", "")).strip()
        if not command:
            raise ValueError("command обязателен")
        proc = subprocess.Popen(command, cwd=str(payload.get("cwd") or APP_ROOT), shell=True)
        return {"ok": True, "pid": proc.pid}
    if action == "kill":
        import psutil
        pid = int(payload["pid"])
        try:
            p = psutil.Process(pid)
            p.terminate()
            return {"ok": True, "pid": pid, "action": "terminate"}
        except psutil.NoSuchProcess:
            return {"ok": False, "pid": pid, "error": "process_not_found"}
    raise ValueError(f"Неизвестное process action: {action}")


def do_file(payload: dict[str, Any]) -> dict[str, Any]:
    action = str(payload.get("action", "read"))
    raw_path = str(payload.get("path", ""))
    if not raw_path:
        raise ValueError("path обязателен")
    path = Path(raw_path).expanduser()
    if action == "read":
        data = path.read_bytes()
        try:
            text = data.decode("utf-8")
        except UnicodeDecodeError:
            text = data.decode("cp1251", errors="replace")
        return {"ok": True, "path": str(path), "text": text[:1_000_000], "size": len(data)}
    if action == "write":
        text = str(payload.get("text", ""))
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return {"ok": True, "path": str(path), "size": len(text.encode('utf-8'))}
    if action == "list":
        items = []
        for p in path.iterdir():
            items.append({"name": p.name, "path": str(p), "is_dir": p.is_dir(), "size": p.stat().st_size if p.is_file() else None})
        return {"ok": True, "path": str(path), "items": items}
    raise ValueError(f"Неизвестное file action: {action}")


class Handler(BaseHTTPRequestHandler):
    server_version = "AI-Council-Bridge/1.0"

    def log_message(self, fmt, *args):
        # Keep stdout clean for automation; append a minimal audit line to state.
        return

    def _json(self, code: int, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_json(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length", "0"))
        if length > 5_000_000:
            raise ValueError("request too large")
        raw = self.rfile.read(length) if length else b"{}"
        value = json.loads(raw.decode("utf-8"))
        if not isinstance(value, dict):
            raise ValueError("JSON body must be an object")
        return value

    def do_GET(self):
        if self.path == "/health":
            state = load_state()
            self._json(200, {"ok": True, "service": "AI Council Bridge", "enabled": bool(state.get("enabled")), "mode": state.get("mode", "OFF"), "session_id": state.get("session_id") or None})
            return
        if self.path == "/status":
            state = load_state()
            safe = {k: v for k, v in state.items() if k != "token"}
            self._json(200, {"ok": True, "state": safe, "root": str(APP_ROOT), "host": HOST, "port": PORT})
            return
        if self.path == "/token":
            if not _authorized(self.headers):
                self._json(403, {"ok": False, "error": "access_off_or_bad_token"})
                return
            self._json(200, {"ok": True, "token": load_state().get("token")})
            return
        self._json(404, {"ok": False, "error": "not_found"})

    def do_POST(self):
        if self.path == "/access/on":
            self._json(200, {"ok": True, "state": set_access(True, "FULL")})
            return
        if self.path == "/access/off":
            self._json(200, {"ok": True, "state": set_access(False)})
            return
        if not _authorized(self.headers):
            self._json(403, {"ok": False, "error": "access_off_or_bad_token"})
            return
        try:
            payload = self._read_json()
            _touch()
            if self.path == "/screen":
                result = do_screenshot(payload.get("path"))
            elif self.path == "/mouse":
                result = do_mouse(payload)
            elif self.path == "/keyboard":
                result = do_keyboard(payload)
            elif self.path == "/shell":
                result = do_shell(payload)
            elif self.path == "/process":
                result = do_process(payload)
            elif self.path == "/file":
                result = do_file(payload)
            else:
                self._json(404, {"ok": False, "error": "not_found"})
                return
            self._json(200, result)
        except Exception as exc:
            self._json(500, {"ok": False, "error": str(exc), "type": type(exc).__name__})


def run_server() -> None:
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    if not STATE_FILE.exists():
        save_state(_default_state())
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    print(f"AI Council Bridge listening on http://{HOST}:{PORT}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    run_server()
