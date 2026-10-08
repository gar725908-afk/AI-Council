from __future__ import annotations

from dataclasses import asdict, dataclass, field
from typing import Any
from uuid import uuid4
from core.request_policy import clean_response
PARTICIPANTS = [
    "ChatGPT",
    "Claude",
    "Kimi",
    "DeepSeek",
    "Grok",
    "Qwen",
    "Алиса",
]


@dataclass
class CouncilMessage:
    """One event in the single shared Council chat.

    ``round_number`` remains only as a legacy field so existing v3 runtime
    JSON files can still be opened. The current application never displays or
    uses rounds.
    """

    author: str
    text: str
    timestamp: str = ""
    sender_type: str = "ai"
    round_number: int = 0  # legacy compatibility; never used by the UI
    role: str = "assistant"
    request_id: str = ""
    parent_message_id: str = ""


@dataclass
class CouncilSession:
    """The central message bus/history of one AI Council workspace."""

    title: str = "Новый Совет"
    participants: list[str] = field(default_factory=lambda: PARTICIPANTS.copy())
    messages: list[CouncilMessage] = field(default_factory=list)
    attachments: list[str] = field(default_factory=list)
    workspace: str = "Совет ИИ"

    def add(
        self,
        author: str,
        text: str,
        timestamp: str = "",
        sender_type: str | None = None,
        request_id: str = "",
        parent_message_id: str = "",
    ) -> CouncilMessage:
        if sender_type is None:
            sender_type = "user" if author == "Ты" else "ai"
        if author in PARTICIPANTS:
            sender_type = "ai"
        if sender_type == "ai":
            text = clean_response(text)
        if not text.strip():
            raise ValueError("Пустое сообщение не сохраняется")
        if request_id:
            existing = next((m for m in self.messages if m.author == author and m.request_id == request_id), None)
            if existing is not None:
                return existing
        message = CouncilMessage(
            author=author,
            text=text,
            timestamp=timestamp,
            sender_type=sender_type,
            role="user" if sender_type == "user" else "assistant",
            request_id=request_id or uuid4().hex,
            parent_message_id=parent_message_id,
        )
        self.messages.append(message)
        self.messages = self.messages[-50:]
        return message

    def transcript(self) -> str:
        """Return the complete local transcript with clear role boundaries."""
        if not self.messages:
            return ""

        blocks: list[str] = []
        for index, message in enumerate(self.messages, 1):
            role = "ПОЛЬЗОВАТЕЛЬ" if message.sender_type == "user" else "ИИ"
            stamp = f"; время={message.timestamp}" if message.timestamp else ""
            blocks.append(
                f"[Сообщение {index}; автор={message.author}; роль={role}{stamp}]\n"
                f"{message.text}"
            )
        return "\n\n".join(blocks)

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "CouncilSession":
        stored_participants = [
            str(x).strip() for x in (data.get("participants") or []) if str(x).strip()
        ]
        current_names = set(PARTICIPANTS)
        merged_participants: list[str] = []
        seen: set[str] = set()
        for name in stored_participants:
            if name in current_names and name not in seen:
                seen.add(name)
                merged_participants.append(name)
        for name in PARTICIPANTS:
            if name not in seen:
                seen.add(name)
                merged_participants.append(name)

        session = cls(
            title=data.get("title", "Новый Совет"),
            participants=merged_participants or PARTICIPANTS.copy(),
            attachments=data.get("attachments") or [],
            workspace=data.get("workspace", "Совет ИИ"),
        )

        messages: list[CouncilMessage] = []
        for raw in data.get("messages", []):
            if not isinstance(raw, dict):
                continue
            author = str(raw.get("author", "ИИ"))
            text = str(raw.get("text", ""))
            sender = str(raw.get("sender_type") or ("user" if author == "Ты" else "ai"))
            if author in set(PARTICIPANTS) | {"Алиса"}:
                sender = "ai"
            if sender != "user":
                text = clean_response(text)
            if not text:
                continue
            messages.append(
                CouncilMessage(
                    author=author,
                    text=text,
                    timestamp=str(raw.get("timestamp", "")),
                    sender_type=sender,
                    role="user" if sender == "user" else "assistant",
                    request_id=str(raw.get("request_id") or ""),
                    parent_message_id=str(raw.get("parent_message_id") or ""),
                    round_number=int(raw.get("round_number", 0) or 0),
                )
            )
        seen = set()
        session.messages = []
        for message in messages:
            key = (message.author, message.request_id)
            if message.request_id and key in seen:
                continue
            if message.request_id:
                seen.add(key)
            session.messages.append(message)
        session.messages = session.messages[-50:]
        return session
