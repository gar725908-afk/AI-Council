from __future__ import annotations

import re

SERVICE_TIMEOUTS_MS = {
    "ChatGPT": 90_000, "Claude": 60_000, "Алиса": 60_000,
    "Kimi": 60_000, "DeepSeek": 60_000, "Grok": 60_000, "Qwen": 60_000,
}
SHORT_ANSWERS = {"готово", "ok", "yes", "no", "да", "нет"}
STATUS_LINE = re.compile(
    r"^(?:(?:working for|работа для) \d+(?:\.\d+)?\s*(?:ms|s|sec|seconds)?|"
    r"(?:thinking|loading|generating|processing|please wait|reading sources|searching the web)[.\u2026]{0,3}|"
    r"(?:думаю|генерирую|загрузка|обработка|читаю источники)[.\u2026]{0,3})(?:\s+(?:skip|пропустить))?$", re.I,
)
LEGACY_STATUS = re.compile(
    r"^(?:Contemplating running|Analyze the user's input and formulate a structured response strategy Skip|"
    r"Обработка запроса с учетом системных ограничений Skip|[^.!?\n]+ \d+s running [\ue000-\uf8ff])$", re.I,
)


def clean_response(text: str) -> str:
    # Filter complete status lines only. A sentence about 'working' is content.
    lines = []
    after_status = False
    for line in str(text).strip().splitlines():
        stripped = line.strip()
        if STATUS_LINE.fullmatch(stripped) or LEGACY_STATUS.fullmatch(stripped):
            after_status = True
            continue
        if after_status and stripped.casefold() in {"skip", "пропустить"}:
            continue
        if stripped:
            after_status = False
        lines.append(line)
    return "\n".join(lines).strip()


def response_complete(text: str, stable: int, quiet_seconds: float,
                      generating: bool, saw_generating: bool) -> bool:
    if generating or not text or stable < 3:
        return False
    # A Stop control disappearing is stronger evidence than a pause in tokens.
    if saw_generating:
        return quiet_seconds >= 1.0
    short = text.strip().rstrip(".!?…").casefold() in SHORT_ANSWERS
    if short or re.search(r"[.!?…][\"'»\)\]]*$", text.rstrip()):
        return quiet_seconds >= 1.5
    # Some services have no Stop control and finish with a code fence/list.
    return quiet_seconds >= 5.0
