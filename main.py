import atexit
import os
import logging
import shutil
import sys
import tempfile
from pathlib import Path

# QtWebEngine's default profile cache is volatile. Give every AI Council
# process its own cache directory so stale/locked Chromium cache files from
# another renderer cannot break page startup.
_WEB_CACHE_DIR = Path(tempfile.mkdtemp(prefix="AI-Council-webcache-"))
_existing_flags = os.environ.get("QTWEBENGINE_CHROMIUM_FLAGS", "").strip()
_cache_flag = f'--disk-cache-dir="{_WEB_CACHE_DIR}"'
if "--disk-cache-dir=" not in _existing_flags:
    os.environ["QTWEBENGINE_CHROMIUM_FLAGS"] = (
        f"{_existing_flags} {_cache_flag}".strip()
    )
atexit.register(lambda: shutil.rmtree(_WEB_CACHE_DIR, ignore_errors=True))

from PySide6.QtCore import QLockFile, QStandardPaths
from PySide6.QtWidgets import QApplication
import qdarktheme

from ui.main_window import MainWindow
from dotenv import load_dotenv

load_dotenv(Path(__file__).with_name(".env"))


def main() -> int:
    app = QApplication(sys.argv)
    app.setOrganizationName("AI Council")
    app.setApplicationName("AI Council")
    app.setApplicationDisplayName("AI Council")
    lock_root = Path(
        QStandardPaths.writableLocation(QStandardPaths.AppLocalDataLocation)
    )
    lock_root.mkdir(parents=True, exist_ok=True)
    lock = QLockFile(str(lock_root / "AI-Council.lock"))
    lock.setStaleLockTime(0)
    if not lock.tryLock(100):
        print("AI Council уже запущен.", flush=True)
        return 0
    try:
        app.setStyleSheet(qdarktheme.load_stylesheet())
    except Exception as exc:
        logging.getLogger(__name__).warning("Theme fallback: %s", exc)
    window = MainWindow()
    window.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
