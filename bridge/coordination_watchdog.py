import ctypes, hashlib, json, re, time
from ctypes import wintypes
from pathlib import Path
import pyautogui, pyperclip

ROOT = Path(r"C:\AI-Council")
BUS = ROOT / "_diagnostics" / "ai_bus.md"
DEEPSEEK_MAIL = ROOT / "_diagnostics" / "chat.txt"
STATE = ROOT / "bridge" / "coordination_watchdog_state.json"
INTERVAL = 5

user32 = ctypes.windll.user32
EnumWindows = user32.EnumWindows
EnumWindowsProc = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
GetWindowTextLengthW = user32.GetWindowTextLengthW
GetWindowTextW = user32.GetWindowTextW
IsWindowVisible = user32.IsWindowVisible
ShowWindow = user32.ShowWindow
SetForegroundWindow = user32.SetForegroundWindow
GetWindowRect = user32.GetWindowRect

class RECT(ctypes.Structure):
    _fields_ = [("left", wintypes.LONG), ("top", wintypes.LONG), ("right", wintypes.LONG), ("bottom", wintypes.LONG)]

def load_state():
    try:
        return json.loads(STATE.read_text(encoding="utf-8"))
    except Exception:
        return {}

def save_state(s):
    STATE.parent.mkdir(parents=True, exist_ok=True)
    STATE.write_text(json.dumps(s, ensure_ascii=False, indent=2), encoding="utf-8")

def find_window(keyword):
    found = []
    def cb(hwnd, _):
        if IsWindowVisible(hwnd):
            n = GetWindowTextLengthW(hwnd)
            buf = ctypes.create_unicode_buffer(n + 1)
            GetWindowTextW(hwnd, buf, n + 1)
            title = buf.value
            if keyword.lower() in title.lower():
                found.append((hwnd, title))
        return True
    EnumWindows(EnumWindowsProc(cb), 0)
    return found[0] if found else None

def latest_chatgpt_block(text):
    # Works with UTF-8 Russian markers and with the older mojibake already in the file.
    matches = list(re.finditer(r"(?ms)^\s*\[[^\]]+\].*?ChatGPT.*?$", text))
    if not matches:
        return ""
    # Prefer the latest line/block containing ChatGPT.
    idx = matches[-1].start()
    tail = text[idx:]
    end = tail.find("\n---")
    return (tail if end < 0 else tail[:end]).strip()

def latest_deepseek_block(text):
    matches = list(re.finditer(r"(?ms)^\s*\[[^\]]+\]\s*DeepSeek:.*?(?=\n---|\Z)", text))
    return matches[-1].group(0).strip() if matches else ""

def send_to_deepseek(message):
    w = find_window("DeepSeek")
    if not w:
        return False, "DeepSeek window not found"
    hwnd, _ = w
    ShowWindow(hwnd, 9)
    SetForegroundWindow(hwnd)
    time.sleep(0.7)
    r = RECT()
    GetWindowRect(hwnd, ctypes.byref(r))
    # Bottom-center of the window is a safer target than a fixed screen coordinate.
    x = (r.left + r.right) // 2
    y = r.bottom - max(70, int((r.bottom - r.top) * 0.08))
    pyautogui.click(x, y)
    time.sleep(0.25)
    pyautogui.hotkey("ctrl", "end")
    pyperclip.copy(message)
    pyautogui.hotkey("ctrl", "v")
    time.sleep(0.25)
    pyautogui.press("enter")
    return True, "sent"

state = load_state()
while True:
    try:
        bus_text = BUS.read_text(encoding="utf-8", errors="replace") if BUS.exists() else ""
        chat_text = DEEPSEEK_MAIL.read_text(encoding="utf-8", errors="replace") if DEEPSEEK_MAIL.exists() else ""

        cgpt = latest_chatgpt_block(bus_text)
        if cgpt:
            digest = hashlib.sha256(cgpt.encode("utf-8")).hexdigest()
            if digest != state.get("last_chatgpt_digest"):
                ok, detail = send_to_deepseek(
                    "ChatGPT добавил новый блок в ai_bus.md. Прочитай ВЕСЬ файл, возьми следующий конкретный шаг AI Council, выполни безопасные проверки/обратимые изменения через свой MCP Bridge и запиши результат и следующий шаг в ai_bus.md. Не проси пользователя подтверждать обычные действия."
                )
                if ok:
                    state["last_chatgpt_digest"] = digest
                    state["last_sent_at"] = time.strftime("%Y-%m-%dT%H:%M:%S")
                else:
                    state["last_error"] = detail

        ds = latest_deepseek_block(chat_text)
        if ds:
            state["last_deepseek_digest"] = hashlib.sha256(ds.encode("utf-8")).hexdigest()

        state["heartbeat"] = time.strftime("%Y-%m-%dT%H:%M:%S")
        save_state(state)
        time.sleep(INTERVAL)
    except Exception as exc:
        state["last_error"] = repr(exc)
        save_state(state)
        time.sleep(INTERVAL)
