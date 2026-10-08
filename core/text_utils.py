from __future__ import annotations

import re
from typing import Any

_MOJIBAKE_MARKERS = (
    "Р°", "Р±", "Рµ", "Рѕ", "Рё", "Р°", "Рї", "РЅ", "Рґ", "Р»", "Рї",
    "С‚", "СЃ", "СЂ", "Сѓ", "С‚", "СЊ", "СЏ", "С‹", "СЌ", "С‡", "С€", "С‰",
    "РІ", "Рі", "Рє", "Рј", "Рѕ", "Рџ", "Рљ", "Рђ", "Р’", "Рњ", "Рќ",
)


def _mojibake_score(text: str) -> int:
    # High score means "looks like UTF-8 Cyrillic decoded as CP1251/Latin-1".
    score = sum(text.count(marker) for marker in _MOJIBAKE_MARKERS)
    score += len(re.findall(r"[РС][\u0400-\u04FF]", text)) * 2
    score += text.count("Â") + text.count("Ã") + text.count("Ð") + text.count("Ñ")
    return score


def repair_mojibake(value: Any) -> Any:
    """Repair common UTF-8/Cyrillic mojibake conservatively and idempotently."""
    if not isinstance(value, str) or not value:
        return value
    current = value
    for _ in range(2):
        base_score = _mojibake_score(current)
        if base_score < 2:
            break
        candidates: list[str] = []
        for source_encoding in ("cp1251", "latin1", "cp1252"):
            try:
                candidates.append(current.encode(source_encoding).decode("utf-8"))
            except (UnicodeEncodeError, UnicodeDecodeError):
                continue
        if not candidates:
            break
        candidate = min(candidates, key=_mojibake_score)
        if _mojibake_score(candidate) >= base_score:
            break
        current = candidate
    return current


def repair_mojibake_tree(value: Any) -> Any:
    if isinstance(value, str):
        return repair_mojibake(value)
    if isinstance(value, list):
        return [repair_mojibake_tree(item) for item in value]
    if isinstance(value, tuple):
        return tuple(repair_mojibake_tree(item) for item in value)
    if isinstance(value, dict):
        return {repair_mojibake_tree(k): repair_mojibake_tree(v) for k, v in value.items()}
    return value
