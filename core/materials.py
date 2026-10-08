from __future__ import annotations

import json
import logging
import mimetypes
import re
import shutil
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Iterable


SUPPORTED = {
    ".pdf", ".png", ".jpg", ".jpeg", ".webp", ".bmp", ".gif",
    ".mp4", ".mov", ".avi", ".mkv", ".webm",
    ".docx", ".doc", ".xlsx", ".xls", ".pptx", ".ppt",
    ".txt", ".md", ".json", ".csv",
}


@dataclass
class Material:
    original_path: str
    stored_path: str
    name: str
    extension: str
    size: int
    mime: str
    indexed_text: str = ""
    index_status: str = "not_indexed"


class MaterialLibrary:
    """Local, file-based project material library.

    The library itself has no artificial count/size limit. Model-specific
    limits are handled later by selecting relevant indexed snippets.
    """

    def __init__(self, root: str | Path):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        self.manifest = self.root / "materials.json"
        self.items: list[Material] = []
        self.load()

    def load(self) -> None:
        self.items = []
        if not self.manifest.exists():
            return
        try:
            data = json.loads(self.manifest.read_text(encoding="utf-8"))
            self.items = [Material(**row) for row in data]
        except (OSError, json.JSONDecodeError, TypeError):
            self.items = []

    def save(self) -> None:
        self.manifest.write_text(
            json.dumps([asdict(x) for x in self.items], ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    @staticmethod
    def is_supported(path: str | Path) -> bool:
        return Path(path).suffix.lower() in SUPPORTED

    def add_files(self, files: Iterable[str | Path]) -> list[Material]:
        added: list[Material] = []
        for raw in files:
            src = Path(raw)
            if not src.exists() or not src.is_file() or not self.is_supported(src):
                continue
            target = self.root / src.name
            counter = 2
            while target.exists():
                target = self.root / f"{src.stem}_{counter}{src.suffix}"
                counter += 1
            shutil.copy2(src, target)
            item = Material(
                original_path=str(src),
                stored_path=str(target),
                name=target.name,
                extension=target.suffix.lower(),
                size=target.stat().st_size,
                mime=mimetypes.guess_type(str(target))[0] or "application/octet-stream",
            )
            self.items.append(item)
            added.append(item)
        self.save()
        return added

    def remove(self, name: str) -> bool:
        for i, item in enumerate(self.items):
            if item.name != name:
                continue
            try:
                Path(item.stored_path).unlink(missing_ok=True)
            except OSError:
                logging.getLogger(__name__).warning("Material parse failed", exc_info=True)
            del self.items[i]
            self.save()
            return True
        return False

    def index_all(self) -> None:
        for item in self.items:
            try:
                text = self._extract(item.stored_path)
                item.indexed_text = text[:2_000_000]
                item.index_status = "indexed" if text else "indexed_empty"
            except Exception as exc:
                item.indexed_text = ""
                item.index_status = f"error: {type(exc).__name__}"
        self.save()

    def _extract(self, path: str) -> str:
        p = Path(path)
        ext = p.suffix.lower()
        if ext in {".txt", ".md", ".json", ".csv"}:
            return p.read_text(encoding="utf-8", errors="ignore")
        if ext == ".pdf":
            import fitz  # PyMuPDF
            doc = fitz.open(path)
            parts = []
            for page in doc:
                parts.append(page.get_text("text"))
            return "\n".join(parts)
        if ext == ".docx":
            from docx import Document
            doc = Document(path)
            return "\n".join(p.text for p in doc.paragraphs)
        if ext in {".xlsx", ".xls"}:
            from openpyxl import load_workbook
            wb = load_workbook(path, read_only=True, data_only=True)
            out: list[str] = []
            for ws in wb.worksheets:
                out.append(f"[Лист: {ws.title}]")
                for row in ws.iter_rows(values_only=True):
                    out.append("\t".join("" if v is None else str(v) for v in row))
            return "\n".join(out)
        if ext == ".pptx":
            from pptx import Presentation
            prs = Presentation(path)
            out: list[str] = []
            for idx, slide in enumerate(prs.slides, start=1):
                out.append(f"[Слайд {idx}]")
                for shape in slide.shapes:
                    if hasattr(shape, "text"):
                        out.append(shape.text)
            return "\n".join(out)
        if ext in {".png", ".jpg", ".jpeg", ".webp", ".bmp", ".gif"}:
            from PIL import Image
            with Image.open(path) as im:
                return f"Изображение {p.name}: размер {im.width}x{im.height}, формат {im.format}."
        if ext in {".mp4", ".mov", ".avi", ".mkv", ".webm"}:
            return self._index_video(path)
        return ""

    @staticmethod
    def _index_video(path: str) -> str:
        import cv2
        cap = cv2.VideoCapture(path)
        if not cap.isOpened():
            return "Видео: не удалось открыть файл для локального индексирования."
        fps = cap.get(cv2.CAP_PROP_FPS) or 0
        frames = cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0
        seconds = frames / fps if fps else 0
        cap.release()
        return f"Видео {Path(path).name}: длительность примерно {seconds:.1f} сек.; FPS {fps:.1f}. Кадры будут отбираться по необходимости."

    def relevant_context(self, query: str, max_chars: int = 40_000) -> str:
        """Return a bounded, deterministic context from the local library.

        The full library remains on disk. Only relevant indexed excerpts are
        placed into a model prompt so large projects do not overflow a model's
        context window.
        """
        tokens = {t.lower() for t in re.findall(r"[\w-]{3,}", query, flags=re.UNICODE)}
        scored: list[tuple[int, Material]] = []
        for item in self.items:
            blob = f"{item.name}\n{item.indexed_text}".lower()
            score = sum(1 for tok in tokens if tok in blob)
            if score or item.index_status.startswith("indexed"):
                scored.append((score, item))
        scored.sort(key=lambda x: (-x[0], x[1].name.lower()))
        parts: list[str] = []
        used = 0
        for score, item in scored:
            snippet = item.indexed_text[:8_000]
            block = f"\n[Материал: {item.name}, релевантность={score}]\n{snippet}\n"
            if used + len(block) > max_chars:
                break
            parts.append(block)
            used += len(block)
        return "".join(parts).strip()
