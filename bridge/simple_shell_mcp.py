#!/usr/bin/env python3
"""Raw MCP stdio server - no SDK dependency."""
import sys, os, json, subprocess

WORKDIR = os.environ.get("SHELL_MCP_WORKDIR", r"C:\AI-Council")
PROTOCOL_VERSION = "2024-11-05"

def send(msg):
    sys.stdout.write(json.dumps(msg) + "\n")
    sys.stdout.flush()

def handle(msg):
    method = msg.get("method")
    msg_id = msg.get("id")

    if method == "initialize":
        return {"jsonrpc": "2.0", "id": msg_id, "result": {
            "protocolVersion": PROTOCOL_VERSION,
            "capabilities": {"tools": {}},
            "serverInfo": {"name": "simple-shell", "version": "1.0.0"}
        }}
    if method == "notifications/initialized":
        return None
    if method == "tools/list":
        return {"jsonrpc": "2.0", "id": msg_id, "result": {"tools": [{
            "name": "shell_execute",
            "description": "Execute shell command, return stdout/stderr.",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "command": {"type": "string"},
                    "workdir": {"type": "string"},
                    "timeout": {"type": "integer"}
                },
                "required": ["command"]
            }
        }]}}
    if method == "tools/call":
        args = msg.get("params", {}).get("arguments", {})
        command = args.get("command", "")
        workdir = args.get("workdir", WORKDIR)
        timeout = int(args.get("timeout", 60))
        try:
            r = subprocess.run(command, shell=True, cwd=workdir, capture_output=True,
                               text=True, timeout=timeout, encoding="utf-8", errors="replace")
            out = f"EXIT_CODE: {r.returncode}\n"
            if r.stdout: out += f"STDOUT:\n{r.stdout}\n"
            if r.stderr: out += f"STDERR:\n{r.stderr}\n"
        except subprocess.TimeoutExpired:
            out = f"TIMEOUT after {timeout}s"
        except Exception as e:
            out = f"ERROR: {e}"
        return {"jsonrpc": "2.0", "id": msg_id,
                "result": {"content": [{"type": "text", "text": out}]}}
    if method == "ping":
        return {"jsonrpc": "2.0", "id": msg_id, "result": {}}
    if msg_id is not None:
        return {"jsonrpc": "2.0", "id": msg_id,
                "error": {"code": -32601, "message": f"Method not found: {method}"}}
    return None

def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except Exception:
            continue
        resp = handle(msg)
        if resp is not None:
            send(resp)

if __name__ == "__main__":
    main()