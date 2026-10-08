from __future__ import annotations

import json
import os
import logging
import tempfile
import re
import shutil
from dataclasses import asdict
from datetime import datetime
from pathlib import Path
from typing import Any

from PySide6.QtCore import QStandardPaths


class LocalStorage:
    """File-based local storage. No database is used.

    Runtime state is auto-saved after changes. Explicitly saved projects are
    copied into a human-readable folder structure under Storage/Projects.
    """

    def __init__(self) -> None:
        base = Path(QStandardPaths.writableLocation(QStandardPaths.AppDataLocation))
        self.root = base / "AI-Council" / "Storage"
        self.runtime = self.root / "Runtime"
        self.projects = self.root / "Projects"
        self.attachments = self.runtime / "attachments"
        for path in (self.root, self.runtime, self.projects, self.attachments):
            path.mkdir(parents=True, exist_ok=True)

        self.active_file = self.runtime / "active_council.json"

    def active_file_for(self, workspace: str) -> Path:
        key = self._safe_name(workspace, "workspace")
        return self.runtime / f"active_{key}.json"

    @staticmethod
    def _safe_name(value: str, fallback: str = "Совет") -> str:
        value = re.sub(r"[<>:\"/\\|?*]", "_", value).strip(" .")
        return value[:120] or fallback

    def save_active(self, session: Any, attachments: list[str] | None = None) -> None:
        payload = {
            "version": 3,
            "updated_at": datetime.now().isoformat(timespec="seconds"),
            "session": session,
            "attachments": attachments or [],
        }
        target = self.active_file_for(getattr(session, "get", lambda _k, _d=None: "Совет ИИ")("workspace", "Совет ИИ")) if isinstance(session, dict) else self.active_file
        encoded = json.dumps(payload, ensure_ascii=False, indent=2)
        fd, temporary = tempfile.mkstemp(prefix=target.stem + "-", suffix=".tmp", dir=target.parent)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as stream:
                stream.write(encoded)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, target)
        finally:
            Path(temporary).unlink(missing_ok=True)

    def load_active(self) -> dict[str, Any] | None:
        return self.load_active_for("Совет ИИ")

    def load_active_for(self, workspace: str) -> dict[str, Any] | None:
        target = self.active_file_for(workspace)
        if not target.exists():
            return None
        try:
            return json.loads(target.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return None

    def clear_active(self, delete_runtime_attachments: bool = True) -> None:
        self.clear_active_for("Совет ИИ", delete_runtime_attachments)

    def clear_active_for(self, workspace: str, delete_runtime_attachments: bool = True) -> None:
        target = self.active_file_for(workspace)
        try:
            target.unlink(missing_ok=True)
        except OSError as exc:
            logging.getLogger(__name__).warning("History cleanup failed: %s", exc)
        if delete_runtime_attachments:
            workspace_dir = self.workspace_attachment_dir(workspace)
            for child in workspace_dir.iterdir():
                try:
                    if child.is_dir():
                        shutil.rmtree(child)
                    else:
                        child.unlink()
                except OSError as exc:
                    logging.getLogger(__name__).warning("Attachment cleanup failed: %s", exc)

    def workspace_attachment_dir(self, workspace: str) -> Path:
        path = self.attachments / self._safe_name(workspace, "workspace")
        path.mkdir(parents=True, exist_ok=True)
        return path

    def copy_attachment_to_runtime(self, source: str, workspace: str = "Совет ИИ") -> str:
        src = Path(source)
        target = self.workspace_attachment_dir(workspace) / src.name
        stem = target.stem
        suffix = target.suffix
        counter = 2
        while target.exists():
            target = self.workspace_attachment_dir(workspace) / f"{stem}_{counter}{suffix}"
            counter += 1
        shutil.copy2(src, target)
        return str(target)

    def save_project(
        self,
        name: str,
        session: dict[str, Any],
        attachment_paths: list[str],
        kind: str = "Совет ИИ",
    ) -> Path:
        clean = self._safe_name(name, "Совет")
        stamp = datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
        project_dir = self.projects / f"{clean}_{stamp}"
        project_dir.mkdir(parents=True, exist_ok=False)
        (project_dir / "attachments").mkdir(parents=True, exist_ok=True)
        payload = {
            "version": 3,
            "saved_at": datetime.now().isoformat(timespec="seconds"),
            "kind": kind,
            "session": session,
            "attachments": [],
        }
        for raw in attachment_paths:
            src = Path(raw)
            if not src.exists():
                continue
            dest = project_dir / "attachments" / src.name
            counter = 2
            while dest.exists():
                dest = project_dir / "attachments" / f"{src.stem}_{counter}{src.suffix}"
                counter += 1
            shutil.copy2(src, dest)
            payload["attachments"].append(str(Path("attachments") / dest.name))
        (project_dir / "project.json").write_text(
            json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        return project_dir

    def list_projects(self) -> list[Path]:
        if not self.projects.exists():
            return []
        return sorted(
            [p for p in self.projects.iterdir() if p.is_dir()],
            key=lambda p: p.stat().st_mtime,
            reverse=True,
        )

    def load_project(self, project_dir: str | Path) -> dict[str, Any] | None:
        path = Path(project_dir) / "project.json"
        if not path.exists():
            return None
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return None

    def delete_project(self, project_dir: str | Path) -> bool:
        path = Path(project_dir)
        if not path.exists() or path.parent != self.projects:
            return False
        try:
            shutil.rmtree(path)
            return True
        except OSError:
            return False

    def open_in_explorer(self, path: str | Path) -> None:
        import os
        os.startfile(str(path))
