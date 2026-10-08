from __future__ import annotations

import json
import os
import secrets
import subprocess
import sys
import threading
import time
import urllib.request
from pathlib import Path
import tkinter as tk
from tkinter import messagebox

HOST = "127.0.0.1"
PORT = int(os.environ.get("AI_COUNCIL_BRIDGE_PORT", "8765"))
BASE = f"http://{HOST}:{PORT}"
APPDATA = Path(os.environ.get("APPDATA", str(Path.home())))
STATE_FILE = APPDATA / "AI Council" / "bridge" / "state.json"


def load_state():
    try:
        return json.loads(STATE_FILE.read_text(encoding="utf-8"))
    except Exception:
        return {"enabled": False, "mode": "OFF"}


def post(path: str):
    req = urllib.request.Request(BASE + path, method="POST")
    with urllib.request.urlopen(req, timeout=3) as r:
        return json.loads(r.read().decode("utf-8"))


def server_up() -> bool:
    try:
        with urllib.request.urlopen(BASE + "/health", timeout=1.5) as r:
            return r.status == 200
    except Exception:
        return False


def ensure_server():
    if server_up():
        return
    here = Path(__file__).resolve().parent
    cmd = [sys.executable, str(here / "bridge_server.py")]
    creationflags = getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0) | getattr(subprocess, "DETACHED_PROCESS", 0)
    subprocess.Popen(cmd, creationflags=creationflags, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, close_fds=True)
    for _ in range(20):
        if server_up():
            return
        time.sleep(0.25)
    raise RuntimeError("Не удалось запустить Bridge Server")


class App:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.root.title("AI Council — Local Bridge")
        self.root.geometry("460x290")
        self.root.resizable(False, False)

        tk.Label(root, text="AI COUNCIL LOCAL BRIDGE", font=("Segoe UI", 17, "bold")).pack(pady=(18, 6))
        self.state_label = tk.Label(root, text="", font=("Segoe UI", 13, "bold"))
        self.state_label.pack(pady=4)
        self.info = tk.Label(root, text="Локальный адрес: 127.0.0.1:8765\nДоступ управляется только с этого компьютера.", justify="center")
        self.info.pack(pady=6)

        row = tk.Frame(root)
        row.pack(pady=8)
        self.toggle = tk.Button(row, text="", width=17, height=2, command=self.toggle_access)
        self.toggle.grid(row=0, column=0, padx=7)
        self.off = tk.Button(row, text="EMERGENCY OFF", width=17, height=2, command=self.emergency_off)
        self.off.grid(row=0, column=1, padx=7)

        tk.Label(root, text="FULL = экран + мышь + клавиатура + файлы + shell + процессы", font=("Segoe UI", 9)).pack(pady=7)
        self.refresh()

    def refresh(self):
        state = load_state()
        enabled = bool(state.get("enabled")) and not bool(state.get("emergency_stop"))
        if enabled:
            self.state_label.config(text="🟢 ACCESS ON")
            self.toggle.config(text="ACCESS OFF")
        else:
            self.state_label.config(text="🔴 ACCESS OFF")
            self.toggle.config(text="ACCESS ON")
        self.root.after(1000, self.refresh)

    def toggle_access(self):
        try:
            state = load_state()
            post("/access/off" if state.get("enabled") else "/access/on")
        except Exception as exc:
            messagebox.showerror("Bridge", str(exc))

    def emergency_off(self):
        try:
            post("/access/off")
        finally:
            self.state_label.config(text="🔴 ACCESS OFF")


def main():
    ensure_server()
    root = tk.Tk()
    App(root)
    root.mainloop()


if __name__ == "__main__":
    main()
