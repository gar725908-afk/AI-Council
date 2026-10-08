from __future__ import annotations

import json
import os
import shutil
import time
from collections import OrderedDict
from datetime import datetime, timezone
import hashlib
import logging
from logging.handlers import RotatingFileHandler
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Callable, Optional

from PySide6.QtCore import QEventLoop, QPoint, QStandardPaths, QTimer, QUrl, Qt, Signal
from PySide6.QtGui import QInputMethodEvent
from PySide6.QtTest import QTest
from PySide6.QtWidgets import (
    QComboBox,
    QDialog,
    QHBoxLayout,
    QLabel,
    QMessageBox,
    QPushButton,
    QVBoxLayout,
    QWidget,
    QApplication,
)
from PySide6.QtWebEngineCore import QWebEnginePage, QWebEngineProfile, QWebEngineScript, QWebEngineSettings
from PySide6.QtWebEngineWidgets import QWebEngineView
from shiboken6 import delete as delete_qt, isValid

from core.text_utils import repair_mojibake
from core.request_policy import SERVICE_TIMEOUTS_MS, clean_response, response_complete


@dataclass(frozen=True)
class AIService:
    name: str
    url: str


@dataclass(frozen=True)
class ServiceAdapter:
    input_selectors: tuple[str, ...]
    send_selectors: tuple[str, ...]
    response_selectors: tuple[str, ...]
    completion_selectors: tuple[str, ...] = ()
    login_url_fragments: tuple[str, ...] = ()
    login_text_fragments: tuple[str, ...] = ()
    enter_after_attempts: int = 8
    user_selectors: tuple[str, ...] = (
        '[data-message-author-role="user"]',
        '[data-role="user"]',
        '[data-message-author="user"]',
    )


SERVICES = [
    AIService("ChatGPT", "https://chatgpt.com/"),
    AIService("Claude", "https://claude.ai/"),
    AIService("DeepSeek", "https://chat.deepseek.com/"),
    AIService("Kimi", "https://www.kimi.ai/"),
    AIService("Grok", "https://grok.com/"),
    AIService("Qwen", "https://chat.qwen.ai/"),
    AIService('Алиса', 'https://alice.yandex.ru/'),
]


CUSTOM_SERVICES_FILE = Path(__file__).resolve().parents[1] / "settings" / "custom_services.json"

def load_custom_services():
    try:
        data = json.loads(CUSTOM_SERVICES_FILE.read_text(encoding="utf-8"))
        return [AIService(item["name"], item["url"]) for item in data
                if isinstance(item, dict) and item.get("name") and
                str(item.get("url", "")).startswith(("https://", "http://"))]
    except (OSError, ValueError, KeyError, TypeError):
        return []

for _custom in load_custom_services():
    if not any(x.name == _custom.name for x in SERVICES):
        SERVICES.append(_custom)
        from core.council import PARTICIPANTS
        if _custom.name not in PARTICIPANTS:
            PARTICIPANTS.append(_custom.name)

ADAPTERS: dict[str, ServiceAdapter] = {
    "ChatGPT": ServiceAdapter(
        input_selectors=(
            "#prompt-textarea",
            'textarea[name="prompt-textarea"]',
            'div.ProseMirror[role="textbox"][aria-label="Ask ChatGPT"]',
            'div.ProseMirror[contenteditable="true"][role="textbox"]',
            'div[contenteditable="true"][role="textbox"]',
            'div[contenteditable="true"][aria-label="Chat with ChatGPT"]',
            'textarea[aria-label="Chat with ChatGPT"]',
            'textarea[placeholder="Ask anything"]',
            '[role="textbox"][contenteditable="true"]',
        ),
        send_selectors=(
            '[data-testid="send-button"]',
            '#composer-submit-button',
            'button[aria-label="Send prompt"]',
            'button[aria-label*="Send"]',
            'button[aria-label*="Отправ"]',
        ),
        response_selectors=(
            '[data-chatgpt-search-unit-key$=":assistant"]',
            '[data-content-search-unit-key$=":assistant"]',
            '[data-message-author-role="assistant"]',
            '[data-role="assistant"]',
            '[data-message-author="assistant"]',
            '.agent-turn',
        ),
        completion_selectors=('button[aria-label*="Stop"]','button[aria-label*="stop"]'),
        user_selectors=(
            '[data-user-message-bubble="true"]',
            '[data-chatgpt-search-unit-key$=":user"]',
            '[data-content-search-unit-key$=":user"]',
            '[data-message-author-role="user"]',
            '[data-role="user"]',
            '[data-message-author="user"]',
            '.user-turn',
        ),
        login_url_fragments=("/auth", "/login", "/signup"),
        login_text_fragments=("log in", "sign up"),
        enter_after_attempts=12,
    ),
    "Claude": ServiceAdapter(
        input_selectors=(
            '[contenteditable="true"][role="textbox"]',
            '[contenteditable="true"].ProseMirror',
            '[contenteditable="true"][data-lexical-editor="true"]',
            '[role="textbox"][contenteditable="true"]',
            'textarea',
        ),
        send_selectors=(
            'button[aria-label="Send message"]',
            'button[aria-label="Send"]',
            'button[type="submit"]',
        ),
        response_selectors=(
            'div.font-claude-response',
            'div.standard-markdown',
            '.progressive-markdown',
            '[data-is-streaming="true"]',
            '[data-testid*="message-content"]',
            '[data-testid="ai-message"]',
            '[data-testid="message-assistant"]',
            '.font-claude-message',
        ),
        completion_selectors=('button[aria-label*="Stop"]','button[aria-label*="stop"]'),
        login_url_fragments=("/login", "/signin", "/sign-in"),
        login_text_fragments=("sign in", "log in"),
        enter_after_attempts=8,
    ),
    "DeepSeek": ServiceAdapter(
        input_selectors=(
            'textarea[placeholder="Message DeepSeek"]',
            'textarea[placeholder*="Message"]',
            '[contenteditable="true"][role="textbox"]',
            'textarea',
        ),
        send_selectors=(
            'div.ds-button.ds-button--primary.ds-button--filled.ds-button--circle',
            'div.ds-button--primary.ds-button--circle',
            'div[role="button"].ds-button--primary',
            'button[aria-label*="Send"]',
            'button[data-testid*="send"]',
            'button[type="submit"]',
            '[role="button"][aria-label*="Send"]',
        ),
        response_selectors=(
            'div.ds-markdown',
            '[data-message-author-role="assistant"]',
            '[class*="ds-markdown"]',
        ),
        completion_selectors=('button[aria-label*="Stop"]', '[data-testid="stop-button"]'),
        login_url_fragments=("/sign_in", "/signin", "/login"),
        login_text_fragments=("log in", "sign up", "log in with google", "登录", "注册", "用 google 登录"),
        enter_after_attempts=1,
    ),
    "Kimi": ServiceAdapter(
        input_selectors=(
            'div.chat-input-editor',
            '[data-testid="chat-editor"] .chat-input-editor',
            'div.chat-input-editor[role="textbox"][contenteditable="true"]',
            'div.chat-input-editor[contenteditable="true"]',
            '[contenteditable="true"][role="textbox"]',
            'textarea',
            '[role="textbox"]',
        ),
        send_selectors=(
            'div.send-button-container:not(.disabled):not(.stop)',
            'div[role="button"][aria-label="Send"]',
            'button[aria-label="Send"]',
            'div.send-button',
        ),
        response_selectors=(
            'div.markdown-container',
            'div.chat-content-item.chat-content-item-assistant',
            'div.segment.segment-assistant',
            '[data-message-author-role="assistant"]',
            '.segment-container',
            '[class*="markdown"]',
        ),
        completion_selectors=('div.send-button-container.stop',),
        user_selectors=(
            '[data-message-author-role="user"]',
            'div.chat-content-item.chat-content-item-user',
            'div.segment.segment-user',
            '[class*="message-item-user"]',
        ),
        login_url_fragments=("/login", "/signin", "/sign-in"),
        login_text_fragments=("log in", "sign in", "sign up"),
        enter_after_attempts=8,
    ),
    "Grok": ServiceAdapter(
        input_selectors=(
            '[contenteditable="true"][role="textbox"][aria-label="Ask Grok anything"]',
            '[contenteditable="true"][role="textbox"]',
        ),
        send_selectors=(
            '[data-testid="chat-submit"]',
            'button[aria-label="Submit"]',
        ),
        response_selectors=(
            'div[data-testid="assistant-message"]',
            '[data-message-author-role="assistant"]',
            '[data-testid*="assistant"]',
        ),
        completion_selectors=('button[aria-label*="Stop"]','button[aria-label*="stop"]'),
        user_selectors=(
            '[data-message-author-role="user"]',
            '[data-message-author="user"]',
            '[data-testid*="user-message"]',
            '[class*="user-message"]',
        ),
        login_url_fragments=("/login", "/signin"),
        login_text_fragments=("sign in", "sign up"),
        enter_after_attempts=2,
    ),
    "Qwen": ServiceAdapter(
        input_selectors=(
            'textarea.message-input-textarea',
            'textarea[placeholder="How can I help you today?"]',
            'textarea[placeholder*="Ask Qwen"]',
            'textarea',
            '[contenteditable="true"][role="textbox"]',
        ),
        send_selectors=(
            'button.send-button',
            '.chat-prompt-send-button button',
            'div.message-input-right-button-send [role="button"]',
            'div.message-input-right-button-send button',
            'button[data-testid="send-button"]',
            'button[aria-label*="Send"]',
            'button[type="submit"]',
        ),
        response_selectors=(
            'div.response-message-content.phase-answer',
            'div.response-message-content',
            'div.chat-response-message',
            '[data-message-author-role="assistant"]',
            '[class*="markdown"]',
        ),
        completion_selectors=('button.stop-button',),
        user_selectors=(
            '[data-message-author-role="user"]',
            '[data-role="user"]',
            '[class*="user-message"]',
        ),
        login_url_fragments=("/login", "/signin", "/sign-in"),
        login_text_fragments=("log in", "sign up", "login"),
        enter_after_attempts=8,
    ),
    "Алиса": ServiceAdapter(
        input_selectors=(
            'textarea[data-testid="inputbase-textarea"]',
            'textarea[placeholder*="Введите"]',
            'textarea[placeholder*="Спросите"]',
            '[contenteditable="true"][role="textbox"]',
            'textarea',
        ),
        send_selectors=(
            '#oknyx-button',
            'button[data-testid="oknyx"]',
            'button[aria-label*="Отправ"]',
            '[role="button"][aria-label*="Отправ"]',
            'button[type="submit"]',
            '[role="button"][aria-label*="Send"]',
        ),
        response_selectors=(
            '[data-message-author-role="assistant"]',
            '[data-role="assistant"]',
            '[data-message-author="assistant"]',
            '[data-testid="message-bubble-container"]:not(.MessageBubble-Container_from-user)',
            '[data-testid="alice-chat-message"] .MessageBubble-Container:not(.MessageBubble-Container_from-user)',
            '[class*="MessageBubble-Container"]:not(.MessageBubble-Container_from-user)',
            '[class*="assistant"]',
            'main article',
            'main [role="article"]',
        ),
        completion_selectors=(
            'button[aria-label*="Останов"]',
            'button[aria-label*="Stop"]',
        ),
        user_selectors=(
            '[data-message-author-role="user"]',
            '[data-role="user"]',
            '[data-message-author="user"]',
            '[data-testid="message-bubble-container-from-user"]',
            '.MessageBubble-Container_from-user',
            '[class*="user-message"]',
        ),
        login_url_fragments=(
            "/login", "/signin", "/sign-in", "/auth", "/passport"
        ),
        login_text_fragments=(
            "войти", "войдите", "вход", "sign in", "log in"
        ),
        enter_after_attempts=4,
    ),

}

PromptFactory = Callable[[], str]
RequestCallback = Callable[[bool, str], None]


class ServicePopup(QDialog):
    def __init__(self, page: QWebEnginePage, parent=None) -> None:
        super().__init__(parent)
        self._page = page
        self.setAttribute(Qt.WidgetAttribute.WA_DeleteOnClose, True)
        self.setModal(False)
        self.setWindowFlag(Qt.WindowType.Window, True)
        self.resize(1000, 760)
        self.setWindowTitle("AI Council — окно входа")

        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        self.view = QWebEngineView(self)
        self.view.setPage(page)
        self.view.titleChanged.connect(self.setWindowTitle)
        layout.addWidget(self.view)

    def closeEvent(self, event) -> None:
        try:
            self.view.stop()
        except RuntimeError:
            logging.getLogger("AI-Council.service_browser").debug("Qt cleanup object is unavailable")
        super().closeEvent(event)


class PopupPage(QWebEnginePage):
    def __init__(self, profile: QWebEngineProfile, browser: "ServiceBrowser", parent=None) -> None:
        super().__init__(profile, parent)
        self.browser = browser
        self.settings().setAttribute(
            QWebEngineSettings.WebAttribute.JavascriptCanOpenWindows, True
        )
        self.settings().setAttribute(
            QWebEngineSettings.WebAttribute.JavascriptEnabled, True
        )

    def createWindow(self, _type):
        page = PopupPage(self.profile(), self.browser, self.profile())
        popup = ServicePopup(page, self.browser)
        page.windowCloseRequested.connect(popup.close)
        self.browser._register_popup(popup, page)
        popup.show()
        popup.raise_()
        popup.activateWindow()
        return page


class ServiceBrowser(QWidget):
    # Единственный поток результатов Совета:
    # service_name, success, text
    broadcast_result = Signal(str, bool, str)
    """Seven official web AI spaces plus a guarded asynchronous UI bridge.

    The bridge never treats a DOM click as proof that a message was accepted.
    Submission is confirmed from the page state before response polling begins,
    and every request has a bounded watchdog so a dead renderer cannot create a
    permanently busy participant queue.
    """

    status_changed = Signal(str)
    service_changed = Signal(str)
    request_state_changed = Signal(str, str)
    availability_changed = Signal(str, str, str)

    REQUEST_TIMEOUT_MS = 90_000
    SEND_CONFIRM_TIMEOUT_MS = 12_000
    DEFAULT_REQUEST_TIMEOUT_MS = 60_000
    IDLE_DISCARD_SECONDS = 60

    def __init__(self, parent=None, auto_open: bool = True, profile_root: Path | None = None) -> None:
        super().__init__(parent)
        self._autoload = auto_open
        self._profile_root_override = profile_root
        self.current_service = SERVICES[0]
        self._profiles: dict[str, QWebEngineProfile] = {}
        self._pages: dict[str, PopupPage] = {}
        self._popups: list[tuple[ServicePopup, PopupPage]] = []
        self._closing = False
        self._placeholder_page: QWebEnginePage | None = None
        # Dedicated visible WebEngine hosts keep all background service pages
        # active/rendered while the user works in the Council tab.
        self._render_views: dict[str, QWebEngineView] = {}
        self._render_placeholders: dict[str, QWebEnginePage] = {}
        self._render_host_parent: QWidget | None = None

        self._active_requests: dict[str, dict] = {}
        self._completed_requests: OrderedDict[str, dict] = OrderedDict()
        self._availability = {s.name: {"status": "unknown", "reason": ""} for s in SERVICES}
        self._status_pending: set[str] = set()
        self._refresh_started: dict[str, float] = {}
        self._last_page_used: dict[str, float] = {}
        self._discard_pending: set[str] = set()
        self._discard_terminations: dict[str, float] = {}
        self._resuming_pages: set[str] = set()
        self._discard_document_epochs: dict[str, str] = {}
        self._bridge_source = Path(__file__).with_name("dom_bridge.js").read_text(encoding="utf-8")
        self._logger = logging.getLogger("AI-Council.service_browser")
        self._logger.setLevel(logging.INFO)
        log_dir = Path(__file__).resolve().parents[1] / "logs"
        log_dir.mkdir(parents=True, exist_ok=True)
        log_path = log_dir / "service_browser.log"
        if not any(isinstance(h, RotatingFileHandler) and h.baseFilename == str(log_path)
                   for h in self._logger.handlers):
            handler = RotatingFileHandler(log_path, maxBytes=2_000_000, backupCount=3, encoding="utf-8")
            handler.setFormatter(logging.Formatter("%(message)s"))
            self._logger.addHandler(handler)
        self._busy_by_service: dict[str, str] = {}
        self._queues: dict[str, list[dict]] = {name: [] for name in self.service_names()}
        self._request_counter = 0
        self._request_started_at: dict[str, float] = {}
        self._poll_timer = QTimer(self)
        self._poll_timer.setInterval(500)
        self._poll_timer.timeout.connect(self._poll_active_requests)

        self._build_ui()
        if auto_open:
            self.open_service("ChatGPT")
        self._status_timer = QTimer(self)
        self._status_timer.setInterval(15_000)
        self._status_timer.timeout.connect(self._refresh_loaded_statuses)
        self._status_timer.start()
        self._idle_timer = QTimer(self)
        self._idle_timer.setInterval(10_000)
        self._idle_timer.timeout.connect(self._release_idle_pages)
        if auto_open:
            self._idle_timer.start()

    def _build_ui(self) -> None:
        root = QVBoxLayout(self)
        root.setContentsMargins(12, 12, 12, 12)

        toolbar = QHBoxLayout()
        toolbar.addWidget(QLabel("ИИ-сервисы"))
        self.service_combo = QComboBox()
        self.service_combo.addItems(self.service_names())
        self.service_combo.currentTextChanged.connect(self.open_service)
        toolbar.addWidget(self.service_combo, 1)

        self.reset_btn = QPushButton("🧹 Сбросить вход")
        self.reset_btn.clicked.connect(self.reset_current_session)
        toolbar.addWidget(self.reset_btn)
        refresh = QPushButton("Обновить статус")
        refresh.clicked.connect(lambda: self.refresh_service_status(self.current_service.name))
        toolbar.addWidget(refresh)

        back_btn = QPushButton("←")
        back_btn.clicked.connect(self._go_back)
        toolbar.addWidget(back_btn)
        forward_btn = QPushButton("→")
        forward_btn.clicked.connect(self._go_forward)
        toolbar.addWidget(forward_btn)
        reload_btn = QPushButton("⟳")
        reload_btn.clicked.connect(self._reload)
        toolbar.addWidget(reload_btn)
        root.addLayout(toolbar)

        self.status = QLabel()
        self.status.setWordWrap(True)
        self.status.setStyleSheet("color:#aeb6c2; padding:2px 4px;")
        root.addWidget(self.status)

        self.web = QWebEngineView()
        root.addWidget(self.web, 1)

    def service_names(self) -> list[str]:
        return [service.name for service in SERVICES]

    def _profile_root(self) -> Path:
        if self._profile_root_override is not None:
            self._profile_root_override.mkdir(parents=True, exist_ok=True)
            return self._profile_root_override
        app_data = Path(QStandardPaths.writableLocation(QStandardPaths.AppDataLocation))
        appdata_env = os.environ.get("APPDATA")
        expected = set(self.service_names())

        candidates: list[Path] = []
        if appdata_env:
            # Preserve the legacy profile root that contains the existing
            # authenticated web sessions. Newer QStandardPaths locations may
            # be empty and must never silently replace it.
            candidates.append(Path(appdata_env) / "python" / "web_profiles")
        candidates.extend([
            app_data / "web_profiles",
            app_data / "AI-Council" / "web_profiles",
        ])
        if appdata_env:
            candidates.extend(
                [
                    Path(appdata_env) / "AI Council" / "AI-Council" / "web_profiles",
                    Path(appdata_env) / "AI-Council" / "web_profiles",
                ]
            )

        seen: set[str] = set()
        for candidate in candidates:
            key = str(candidate).lower()
            if key in seen or not candidate.exists():
                continue
            seen.add(key)
            try:
                names = {p.name for p in candidate.iterdir() if p.is_dir()}
            except OSError:
                continue
            if names.intersection(expected):
                return candidate

        if appdata_env:
            path = Path(appdata_env) / "AI Council" / "AI-Council" / "web_profiles"
        else:
            path = app_data / "web_profiles"
        path.mkdir(parents=True, exist_ok=True)
        return path

    def _profile_for(self, service: AIService) -> QWebEngineProfile:
        existing = self._profiles.get(service.name)
        if existing is not None:
            return existing

        root = self._profile_root() / service.name
        root.mkdir(parents=True, exist_ok=True)

        isolated = not self._autoload and self._profile_root_override is not None
        profile = (QWebEngineProfile(self) if isolated else
                   QWebEngineProfile(f"AI-Council-{service.name}", self))
        if not isolated:
            profile.setPersistentStoragePath(str(root))
            profile.setPersistentCookiesPolicy(
                QWebEngineProfile.PersistentCookiesPolicy.ForcePersistentCookies
            )
        # Keep authentication/local-storage persistent, but keep the volatile
        # HTTP cache in memory. This avoids stale/locked Chromium cache files
        # preventing a profile from hydrating its live web application.
        profile.setHttpCacheType(QWebEngineProfile.HttpCacheType.MemoryHttpCache)
        profile.setHttpCacheMaximumSize(8 * 1024 * 1024)
        self._profiles[service.name] = profile
        return profile

    def enable_background_rendering(self, host: QWidget) -> None:
        """Attach each service page to a tiny off-screen WebEngine view."""
        if self._render_host_parent is host and self._render_views:
            return

        self._render_host_parent = host

        # The ServiceBrowser widget may be hidden inside the main stacked UI.
        # Detach the current user-facing page before assigning all service pages
        # to their dedicated always-visible automation views.
        try:
            current_page = self._pages.get(getattr(self.current_service, "name", ""))
            if current_page is not None and self.web.page() is current_page:
                placeholder = self._placeholder_page
                if placeholder is None:
                    placeholder = QWebEnginePage(self)
                    self._placeholder_page = placeholder
                self.web.setPage(placeholder)
        except RuntimeError:
            logging.getLogger("AI-Council.service_browser").debug("Qt cleanup object is unavailable")

        for index, service in enumerate(SERVICES):
            try:
                page = self._page_for(service)
                view = self._render_views.get(service.name)
                if view is None:
                    view = QWebEngineView(host)
                    view.setAttribute(Qt.WA_DontShowOnScreen, True)
                    view.setGeometry(0, 0, 1280, 800)
                    view.show()
                    self._render_views[service.name] = view

                if view.page() is not page:
                    old_placeholder = self._render_placeholders.get(service.name)
                    if old_placeholder is None:
                        old_placeholder = QWebEnginePage(host)
                        self._render_placeholders[service.name] = old_placeholder
                    view.setPage(page)

                try:
                    page.setVisible(True)
                    page.setLifecycleState(QWebEnginePage.LifecycleState.Active)
                except (AttributeError, RuntimeError):
                    logging.getLogger("AI-Council.service_browser").debug("Qt cleanup object is unavailable")

                # Pre-warm only two proven-good heavy clients. Preloading
                # ChatGPT currently burns resources on a Cloudflare challenge,
                # while loading all seven SPAs overwhelms QtWebEngine.
                if self._autoload and service.name in {"Claude", "Kimi"}:
                    warm_delay = 0 if service.name == "Claude" else 1_000
                    QTimer.singleShot(
                        warm_delay,
                        lambda p=page, url=service.url: self._load_page_if_blank(p, url),
                    )
            except Exception:
                self._logger.exception("Background page setup failed for %s", service.name)
                continue

    @staticmethod
    def _load_page_if_blank(page: QWebEnginePage, url: str) -> None:
        try:
            current = page.url().toString()
            if not current or current.rstrip("/") == "about:blank":
                page.setUrl(QUrl(url))
        except RuntimeError:
            logging.getLogger("AI-Council.service_browser").debug("Qt cleanup object is unavailable")

    def _attach_page_to_background(self, service_name: str, page: QWebEnginePage) -> None:
        view = self._render_views.get(service_name)
        if view is None:
            return
        try:
            if view.page() is not page:
                view.setPage(page)
            page.setVisible(True)
            page.setLifecycleState(QWebEnginePage.LifecycleState.Active)
        except (AttributeError, RuntimeError):
            logging.getLogger("AI-Council.service_browser").debug("Qt cleanup object is unavailable")

    def background_current_service(self) -> None:
        """Return the currently selected page to its background render host."""
        name = getattr(self.current_service, "name", "")
        page = self._pages.get(name)
        if page is None or not self._render_views.get(name):
            return
        try:
            if self.web.page() is page:
                placeholder = self._placeholder_page
                if placeholder is None:
                    placeholder = QWebEnginePage(self)
                    self._placeholder_page = placeholder
                self.web.setPage(placeholder)
            self._attach_page_to_background(name, page)
        except RuntimeError:
            logging.getLogger("AI-Council.service_browser").debug("Qt cleanup object is unavailable")

    def _detach_page_from_background(self, service_name: str) -> None:
        view = self._render_views.get(service_name)
        if view is None:
            return
        try:
            page = self._pages.get(service_name)
            if page is not None and view.page() is page:
                placeholder = self._render_placeholders.get(service_name)
                if placeholder is None:
                    placeholder = QWebEnginePage(self._render_host_parent or self)
                    self._render_placeholders[service_name] = placeholder
                view.setPage(placeholder)
        except RuntimeError:
            logging.getLogger("AI-Council.service_browser").debug("Qt cleanup object is unavailable")

    def _page_for(self, service: AIService) -> PopupPage:
        existing = self._pages.get(service.name)
        if existing is not None:
            if existing.lifecycleState() == QWebEnginePage.LifecycleState.Discarded:
                self._last_page_used[service.name] = time.monotonic()
                self._resuming_pages.add(service.name)
                existing.setLifecycleState(QWebEnginePage.LifecycleState.Active)
                view = self._render_views.get(service.name)
                if view is not None and self.web.page() is not existing:
                    view.show()
                    self._attach_page_to_background(service.name, existing)
                self._log(None, "page_resumed:" + service.name, existing.url().toString())
            return existing
        profile = self._profile_for(service)
        page = PopupPage(profile, self, profile)
        try:
            page.setVisible(True)
            page.setLifecycleState(QWebEnginePage.LifecycleState.Active)
        except (AttributeError, RuntimeError):
            logging.getLogger("AI-Council.service_browser").debug("Qt cleanup object is unavailable")
        self._pages[service.name] = page
        self._last_page_used[service.name] = time.monotonic()
        page.loadFinished.connect(
            lambda ok, name=service.name: self._page_loaded(name, ok)
        )
        page.renderProcessTerminated.connect(
            lambda status, code, name=service.name: self._renderer_stopped(name, status, code)
        )
        if service.name in self._render_views and self.web.page() is not page:
            self._attach_page_to_background(service.name, page)
        return page

    def _service(self, name: str) -> Optional[AIService]:
        return next((service for service in SERVICES if service.name == name), None)

    def _adapter(self, name: str) -> ServiceAdapter:
        return ADAPTERS.get(name, ADAPTERS["ChatGPT"])

    def _set_status(self, text: str) -> None:
        self.status.setText(text)
        self.status_changed.emit(text)

    def open_service(self, name: str) -> None:
        service = self._service(name)
        if service is None or self._closing:
            return

        old_name = getattr(self.current_service, "name", "")
        if old_name and old_name != service.name:
            old_page = self._pages.get(old_name)
            if old_page is not None and self.web.page() is old_page:
                try:
                    placeholder = self._placeholder_page
                    if placeholder is None:
                        placeholder = QWebEnginePage(self)
                        self._placeholder_page = placeholder
                    self.web.setPage(placeholder)
                except RuntimeError:
                    logging.getLogger("AI-Council.service_browser").debug("Qt cleanup object is unavailable")
                self._attach_page_to_background(old_name, old_page)

        self.current_service = service
        self.service_changed.emit(name)
        page = self._page_for(service)
        self._detach_page_from_background(service.name)
        self.web.setPage(page)

        try:
            page.setVisible(True)
            page.setLifecycleState(QWebEnginePage.LifecycleState.Active)
        except (AttributeError, RuntimeError):
            logging.getLogger("AI-Council.service_browser").debug("Qt cleanup object is unavailable")

        current = page.url().toString()
        if not current or current.rstrip("/") == "about:blank":
            page.setUrl(QUrl(service.url))
        self._set_status(f"{name}: проверка веб-интерфейса.")
        self.refresh_service_status(name)

    def _go_back(self) -> None:
        if self.web.page():
            self.web.back()

    def _go_forward(self) -> None:
        if self.web.page():
            self.web.forward()

    def _reload(self) -> None:
        if self.web.page():
            self.web.reload()

    # ---------------- Popup lifecycle ----------------
    def _register_popup(self, popup: ServicePopup, page: PopupPage) -> None:
        self._popups.append((popup, page))
        popup.destroyed.connect(
            lambda _obj=None, p=popup, pg=page: self._popup_destroyed(p, pg)
        )

    def _popup_destroyed(self, popup: ServicePopup, page: PopupPage) -> None:
        self._popups = [item for item in self._popups if item[0] is not popup]
        try:
            page.deleteLater()
        except RuntimeError:
            logging.getLogger("AI-Council.service_browser").debug("Qt cleanup object is unavailable")

    # ---------------- Session reset ----------------
    def reset_current_session(self) -> None:
        name = self.current_service.name
        answer = QMessageBox.question(
            self,
            "Сбросить вход",
            f"Удалить сохранённые данные веб-сессии {name}?\n\nПосле этого потребуется войти заново.",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
            QMessageBox.StandardButton.No,
        )
        if answer != QMessageBox.StandardButton.Yes:
            return

        self.cancel_service_request(name)
        self._queues[name] = []
        old_page = self._pages.pop(name, None)
        old_profile = self._profiles.pop(name, None)
        root = self._profile_root() / name
        if old_profile is None:
            self.open_service(name)
            return

        for popup, page in list(self._popups):
            try:
                same_profile = page.profile() is old_profile
            except RuntimeError:
                same_profile = False
            if same_profile:
                try:
                    popup.close()
                except RuntimeError:
                    logging.getLogger("AI-Council.service_browser").debug("Qt cleanup object is unavailable")

        try:
            self.web.stop()
            placeholder = QWebEnginePage(self)
            self._placeholder_page = placeholder
            self.web.setPage(placeholder)
        except RuntimeError:
            logging.getLogger("AI-Council.service_browser").debug("Qt cleanup object is unavailable")

        if old_page is not None:
            try:
                old_page.deleteLater()
            except RuntimeError:
                logging.getLogger("AI-Council.service_browser").debug("Qt cleanup object is unavailable")

        old_profile.destroyed.connect(
            lambda _obj=None, path=root: self._delete_profile_storage_later(path)
        )
        old_profile.deleteLater()

        def reopen() -> None:
            self._placeholder_page = None
            self._set_request_state(name, "готов")
            self._set_status(f"{name}: вход сброшен. Войдите заново.")
            self.open_service(name)

        QTimer.singleShot(700, reopen)

    def _delete_profile_storage_later(self, root: Path, attempt: int = 0) -> None:
        if not root.exists():
            return
        try:
            shutil.rmtree(root)
            return
        except OSError:
            if attempt >= 20:
                self._set_status(
                    "Старые данные веб-сессии пока заняты Windows/Qt; они останутся до следующего сброса."
                )
                return
            QTimer.singleShot(
                500,
                lambda path=root, n=attempt + 1: self._delete_profile_storage_later(path, n),
            )

    # ---------------- JavaScript bridge ----------------
    @staticmethod
    def _now() -> str:
        return datetime.now(timezone.utc).isoformat(timespec="milliseconds")

    def _log(self, state: dict | None, key: str, value) -> None:
        name = state["service"].name if state else "-"
        rid = state["id"] if state else "-"
        self._logger.info("%s | %s | %s | %s | %s", self._now(), rid, name, key,
                          json.dumps(value, ensure_ascii=False, default=str))

    def _change(self, state: dict, **changes) -> None:
        for key, value in changes.items():
            if state.get(key) != value:
                state[key] = value
                self._log(state, key, value)

    def _dom_js(self, name: str, operation: str, **args) -> str:
        config = {"service": name, "adapter": asdict(self._adapter(name)),
                  "operation": operation, "args": args}
        encoded = json.dumps(config, ensure_ascii=True, default=lambda x: sorted(x))
        return "(() => {\n" + self._bridge_source + "\nreturn councilBridge(" + encoded + ");\n})()"

    def _run_js_json(self, page: QWebEnginePage, script: str,
                     callback: Callable[[dict], None]) -> None:
        # Parse and dispatch separately: a callback exception must not invoke it twice.
        wrapped = "JSON.stringify((() => {try {return " + script + (
            ";} catch (e) {return {ok:false,bridge_error:String(e),stack:String(e.stack)};}})())"
        )
        delivered = {"value": False}
        watchdog = QTimer(self)
        watchdog.setSingleShot(True)

        def received(raw) -> None:
            if delivered["value"]:
                return
            delivered["value"] = True
            watchdog.stop()
            watchdog.deleteLater()
            if self._closing:
                return
            try:
                value = raw if isinstance(raw, dict) else json.loads(raw) if isinstance(raw, str) and raw else {}
                payload = value if isinstance(value, dict) else {}
            except (ValueError, TypeError) as exc:
                payload = {"ok": False, "bridge_error": str(exc)}
            try:
                callback(payload)
            except Exception:
                self._logger.exception("JavaScript result callback failed")

        watchdog.timeout.connect(lambda: received({"ok": False, "bridge_error": "JavaScript callback timeout"}))
        watchdog.start(30_000)
        try:
            page.runJavaScript(wrapped, QWebEngineScript.ScriptWorldId.ApplicationWorld, received)
        except (RuntimeError, TypeError, AttributeError) as exc:
            received({"ok": False, "bridge_error": str(exc)})

    def _request_js(self, state: dict, key: str, script: str, callback) -> None:
        if self._active_requests.get(state["id"]) is not state or state.get(key) or state.get("published"):
            return
        self._change(state, **{key: True})

        def done(payload) -> None:
            if self._active_requests.get(state["id"]) is not state:
                return
            self._change(state, **{key: False})
            self._change(state, last_js_result=json.dumps(payload, ensure_ascii=False))
            error = payload.get("bridge_error")
            if error:
                self._change(state, last_error=str(error))
                self._finish_async(state["id"], False, "Ошибка JavaScript: " + str(error))
                return
            callback(payload)

        self._run_js_json(state["page"], script, done)

    def _inspect_js(self, service_name: str) -> str:
        return self._dom_js(service_name, "inspect")

    def _fill_js(self, service_name: str, prompt: str, expected_draft: str = "") -> str:
        return self._dom_js(service_name, "fill", prompt=prompt, expected_draft=expected_draft)

    def _send_js(self, service_name: str, use_enter: bool = False,
                 request_id: str = "", prompt: str = "") -> str:
        if not prompt:
            state = self._active_requests.get(request_id or self._busy_by_service.get(service_name, ""), {})
            prompt = str(state.get("prompt", ""))
        return self._dom_js(service_name, "send", request_id=request_id,
                            prompt=prompt, use_enter=use_enter)

    def _confirm_submission_js(self, service_name: str, prompt: str,
                                before_user_count: int, before_user_ids: set[str]) -> str:
        state = self._active_requests.get(self._busy_by_service.get(service_name, ""), {})
        return self._dom_js(service_name, "confirm", prompt=prompt, request_id=state.get("id", ""),
                            before_user_count=before_user_count, before_user_ids=before_user_ids,
                            before_ids=state.get("before_ids", set()),
                            before_texts=state.get("before_texts", set()),
                            before_node_count=state.get("before_node_count", 0))

    def _extract_js(self, service_name: str) -> str:
        return self._dom_js(service_name, "snapshot")

    def _extract_new_response_js(self, service_name: str, before_ids: set[str],
                                 before_texts: set[str]) -> str:
        state = self._active_requests.get(self._busy_by_service.get(service_name, ""), {})
        return self._dom_js(service_name, "extract", before_ids=before_ids, before_texts=before_texts,
                            before_node_count=state.get("before_node_count", 0),
                            prompt=state.get("prompt", ""))

    def _install_response_observer_js(self, service_name: str, token: str,
                                      before_ids: set[str], before_texts: set[str],
                                      before_node_count: int) -> str:
        return self._dom_js(service_name, "observe", request_id=token,
                            before_ids=before_ids, before_texts=before_texts,
                            before_node_count=before_node_count)

    def _read_response_observer_js(self, service_name: str, request_id: str) -> str:
        return self._dom_js(service_name, "observer_read", request_id=request_id)

    def _stop_response_observer_js(self, request_id: str) -> str:
        return self._dom_js(request_id.split(":", 1)[0], "observer_stop", request_id=request_id)

    def _set_availability(self, name: str, status: str, reason: str = "") -> None:
        value = {"status": status, "reason": reason}
        if self._availability.get(name) == value:
            return
        self._availability[name] = value
        self._log(None, "availability:" + name, value)
        self.availability_changed.emit(name, status, reason)
        if name not in self._busy_by_service:
            self._set_request_state(name, status)

    def service_status(self, name: str) -> str:
        return self._availability.get(name, {}).get("status", "unavailable")

    def _page_loaded(self, name: str, ok: bool) -> None:
        if self._closing:
            return
        self._last_page_used[name] = time.monotonic()
        self._resuming_pages.discard(name)
        if not ok:
            self._set_availability(name, "unavailable", "Страница не загружена")
        else:
            self.refresh_service_status(name)

    def _renderer_stopped(self, name: str, status, code: int) -> None:
        if self._closing:
            return
        discarded_at = self._discard_terminations.pop(name, None)
        if (discarded_at is not None and time.monotonic() - discarded_at < 60
                and status == QWebEnginePage.RenderProcessTerminationStatus.NormalTerminationStatus
                and code == 0):
            self._log(None, "page_discard_completed:" + name, code)
            return
        self._set_availability(name, "unavailable", f"Процесс страницы завершён: {status}, {code}")
        rid = self._busy_by_service.get(name)
        if rid:
            self._finish_async(rid, False, "Процесс страницы завершён")

    def refresh_service_status(self, name: str, started: float | None = None) -> None:
        if self._closing or name in self._status_pending or name in self._busy_by_service:
            return
        service = self._service(name)
        if service is None:
            return
        page = self._page_for(service)
        started = started if started is not None else time.monotonic()
        if not page.url().toString() or page.url().toString() == "about:blank":
            page.setUrl(QUrl(service.url))
        self._status_pending.add(name)

        def inspected(result) -> None:
            self._status_pending.discard(name)
            if self._closing or name in self._busy_by_service:
                return
            if name in self._resuming_pages:
                epoch = result.get("document_epoch")
                if epoch and epoch != self._discard_document_epochs.get(name):
                    self._resuming_pages.discard(name)
                else:
                    if time.monotonic() - started < 25:
                        QTimer.singleShot(700, lambda: self.refresh_service_status(name, started))
                    else:
                        self._set_availability(name, "unavailable", "Страница не восстановилась после простоя")
                    return
            if result.get("login_required"):
                self._set_availability(name, "login_required", "Требуется авторизация")
            elif result.get("service_error"):
                self._set_availability(name, "unavailable", result["service_error"])
            elif result.get("blocked_reason"):
                self._set_availability(name, "unavailable", self._blocked_reason_text(result["blocked_reason"]))
            elif result.get("ready") and result.get("input_found"):
                self._set_availability(name, "online")
            elif time.monotonic() - started < 25:
                QTimer.singleShot(1000, lambda: self.refresh_service_status(name, started))
            else:
                self._set_availability(name, "unavailable",
                                       result.get("bridge_error") or "Поле чата не найдено")

        self._run_js_json(page, self._inspect_js(name), inspected)

    def _refresh_loaded_statuses(self) -> None:
        for name, page in list(self._pages.items()):
            if page.lifecycleState() == QWebEnginePage.LifecycleState.Discarded or name in self._discard_pending:
                continue
            if page.url().toString() and page.url().toString() != "about:blank":
                self.refresh_service_status(name)

    def _page_can_sleep(self, name: str, page: QWebEnginePage) -> bool:
        if self._closing or name in self._busy_by_service or self._queues[name]:
            return False
        if self.service_status(name) != "online":
            return False
        if page.isLoading() or page.recentlyAudible() or page.devToolsPage() is not None:
            return False
        if self.web.isVisible() and self.web.page() is page:
            return False
        if any(popup.isVisible() and popup_page.profile() is page.profile()
               for popup, popup_page in self._popups if isValid(popup_page)):
            return False
        return time.monotonic() - self._last_page_used.get(name, time.monotonic()) >= self.IDLE_DISCARD_SECONDS

    def _release_idle_pages(self) -> None:
        """Release unused renderers; profiles, cookies and server chats stay persistent."""
        if self._closing or self._active_requests or any(self._queues.values()):
            return
        for name, page in list(self._pages.items()):
            if name in self._discard_pending or page.lifecycleState() == QWebEnginePage.LifecycleState.Discarded:
                continue
            if not self._page_can_sleep(name, page):
                continue
            self._discard_pending.add(name)

            def checked(info, service_name=name, candidate=page) -> None:
                if not isValid(candidate) or not self._page_can_sleep(service_name, candidate):
                    self._discard_pending.discard(service_name)
                    return
                if (not info.get("ready") or not info.get("input_found") or info.get("input_text")
                        or info.get("generating") or info.get("login_required")
                        or info.get("blocked_reason") or info.get("service_error")):
                    self._discard_pending.discard(service_name)
                    return
                # Let Qt finish dispatching the JavaScript result before releasing
                # its renderer. Other participants must also be idle.
                QTimer.singleShot(0, lambda: self._discard_idle_page(service_name, candidate, info))

            self._run_js_json(page, self._inspect_js(name), checked)

    def _discard_idle_page(self, name: str, page: QWebEnginePage, info: dict) -> None:
        if (not isValid(page) or self._active_requests or any(self._queues.values())
                or not self._page_can_sleep(name, page)):
            self._discard_pending.discard(name)
            return
        if name in self._status_pending:
            QTimer.singleShot(100, lambda: self._discard_idle_page(name, page, info))
            return
        self._discard_pending.discard(name)
        view = self._render_views.get(name)
        if view is not None:
            view.hide()
        page.setVisible(False)
        self._discard_terminations[name] = time.monotonic()
        self._discard_document_epochs[name] = str(info.get("document_epoch", ""))
        page.setLifecycleState(QWebEnginePage.LifecycleState.Discarded)
        self._log(None, "page_discarded:" + name, page.url().toString())

    # ---------------- Async requests ----------------
    @staticmethod
    def _blocked_reason_text(reason: str) -> str:
        return {"age_confirmation": "Сервис запрашивает подтверждение возраста",
                "browser_challenge": "Проверка доступа на стороне сервиса"}.get(reason, str(reason))

    def _set_request_state(self, service_name: str, state: str) -> None:
        self.request_state_changed.emit(service_name, str(repair_mojibake(state)))

    def _next_request_id(self, service_name: str) -> str:
        self._request_counter += 1
        return f"{service_name}:{time.time_ns()}:{self._request_counter}"

    @staticmethod
    def _resolve_prompt(prompt: str | PromptFactory) -> str:
        value = str(prompt() if callable(prompt) else prompt).strip()
        if not value:
            raise ValueError("Пустой запрос Совета")
        return value

    def start_service_request(self, service_name: str, prompt: str, callback: RequestCallback,
                               timeout_ms: int | None = None,
                               queued_prompt_factory: PromptFactory | None = None,
                               request_kind: str = "user") -> None:
        service = self._service(service_name)
        if service is None:
            callback(False, f"Неизвестный сервис: {service_name}")
            return
        if self._closing:
            callback(False, "Приложение закрывается")
            return
        if self.service_status(service_name) in {"unavailable", "login_required"}:
            info = self._availability[service_name]
            callback(False, info["reason"] or info["status"])
            return
        timeout = max(5_000, int(timeout_ms or SERVICE_TIMEOUTS_MS.get(service_name, 60_000)))
        item = {"id": self._next_request_id(service_name),
                "prompt": queued_prompt_factory or (lambda value=prompt: value),
                "callback": callback, "kind": request_kind, "timeout_ms": timeout}
        if service_name in self._busy_by_service:
            self._queues[service_name].append(item)
            self._set_request_state(service_name, "очередь")
            return
        self._begin_async_request(service, item)

    def _begin_async_request(self, service: AIService, item: dict) -> None:
        try:
            prompt = self._resolve_prompt(item["prompt"])
        except Exception as exc:
            item["callback"](False, str(exc))
            QTimer.singleShot(0, lambda: self._start_next_queued(service.name))
            return
        state = {
            "id": item["id"], "service": service, "page": self._page_for(service),
            "callback": item["callback"], "prompt": prompt, "kind": item["kind"],
            "prepared": False, "submitted": False, "published": False, "publish_count": 0,
            "send_triggered": False, "send_attempts": 0, "fill_attempts": 0,
            "inspect_pending": False, "fill_pending": False, "send_pending": False,
            "send_confirm_pending": False, "poll_pending": False,
            "before_ids": set(), "before_texts": set(), "before_user_ids": set(),
            "before_user_count": 0, "before_node_count": 0,
            "last_candidate": "", "last_changed_at": 0.0, "stable": 0,
            "last_error": "", "last_js_result": "", "created_at": self._now(),
            "first_response_at": None, "completed_at": None, "saw_generating": False,
            "timeout_ms": item["timeout_ms"], "deadline": time.monotonic() + item["timeout_ms"] / 1000,
            "send_confirm_started_at": 0.0,
        }
        self._active_requests[state["id"]] = state
        self._last_page_used[service.name] = time.monotonic()
        self._busy_by_service[service.name] = state["id"]
        self._request_started_at[state["id"]] = time.monotonic()
        self._log(state, "created_at", state["created_at"])
        self._set_request_state(service.name, "запускается")
        self._load_page_if_blank(state["page"], service.url)
        self._inspect_until_ready(state)
        self._poll_timer.start()

    def _inspect_until_ready(self, state: dict) -> None:
        if state.get("prepared") or state.get("submitted") or state.get("send_triggered"):
            return
        name = state["service"].name

        def inspected(result) -> None:
            if name in self._resuming_pages:
                epoch = result.get("document_epoch")
                if epoch and epoch != self._discard_document_epochs.get(name):
                    self._resuming_pages.discard(name)
                else:
                    self._set_request_state(name, "ждёт загрузки страницы")
                    QTimer.singleShot(700, lambda: self._inspect_until_ready(state))
                    return
            if result.get("login_required"):
                self._set_availability(name, "login_required", "Требуется авторизация")
                self._finish_async(state["id"], False, "Требуется авторизация")
                return
            if result.get("service_error"):
                self._set_availability(name, "unavailable", result["service_error"])
                self._finish_async(state["id"], False, result["service_error"])
                return
            if result.get("blocked_reason"):
                reason = self._blocked_reason_text(result["blocked_reason"])
                self._set_availability(name, "unavailable", reason)
                self._finish_async(state["id"], False, reason)
                return
            if not result.get("ready") or not result.get("input_found") or result.get("generating"):
                self._set_request_state(name, "ждёт веб-интерфейс" if not result.get("generating") else "ждёт завершения")
                QTimer.singleShot(700, lambda: self._inspect_until_ready(state))
                return
            self._set_availability(name, "online")
            self._change(state, prepared=True,
                         before_texts=set(result.get("responses") or []),
                         before_ids=set(result.get("response_ids") or []),
                         before_node_count=int(result.get("response_node_count") or 0),
                         before_user_count=int(result.get("user_count") or 0),
                         before_user_ids=set(result.get("user_ids") or []),
                         expected_draft=str(result.get("input_text") or ""))
            if state["expected_draft"] and state["expected_draft"] != state["prompt"]:
                draft_log = Path(__file__).resolve().parents[1] / "logs" / "recovered_drafts.jsonl"
                with draft_log.open("a", encoding="utf-8") as stream:
                    stream.write(json.dumps({"time": self._now(), "service": name,
                                             "request_id": state["id"], "text": state["expected_draft"]},
                                            ensure_ascii=False) + "\n")
            self._fill_request(state)

        self._request_js(state, "inspect_pending", self._inspect_js(name), inspected)

    def _fill_request(self, state: dict) -> None:
        if state.get("submitted") or state.get("send_triggered") or state.get("fill_pending") or state.get("native_fill_pending"):
            return
        name = state["service"].name
        self._change(state, fill_attempts=state["fill_attempts"] + 1)
        if name in {"Kimi", "ChatGPT", "DeepSeek", "Алиса"}:
            self._native_fill_request(state)
            return

        def filled(result) -> None:
            if result.get("ok"):
                self._log(state, "fill_method", result.get("method"))
                self._wait_for_send(state)
            elif result.get("reason") == "user_draft_changed":
                self._finish_async(state["id"], False, "В поле сервиса сохранён другой черновик")
            elif state["fill_attempts"] <= 2:
                self._change(state, prepared=False)
                QTimer.singleShot(500, lambda: self._inspect_until_ready(state))
            else:
                self._finish_async(state["id"], False, "Ввод текста не подтверждён: " + str(result.get("reason")))

        self._request_js(state, "fill_pending", self._fill_js(name, state["prompt"], state.get("expected_draft", "")), filled)

    def _native_fill_request(self, state: dict) -> None:
        """Native events update ProseMirror/Lexical state and preserve multiline text."""
        name = state["service"].name
        self._change(state, native_fill_pending=True)

        def focused(result) -> None:
            if not result.get("ok"):
                self._finish_async(state["id"], False, name + ": редактор не готов (" + str(result.get("reason")) + ")")
                return
            page = state["page"]
            view = self.web if self.web.page() is page else self._render_views.get(name)
            if view is None:
                view = QWebEngineView(self)
                view.setAttribute(Qt.WA_DontShowOnScreen, True)
                view.resize(1280, 800)
                view.setPage(page)
                view.show()
                self._render_views[name] = view
            view.setFocus(Qt.FocusReason.OtherFocusReason)
            target = view.focusProxy() or view
            QTest.mouseClick(target, Qt.MouseButton.LeftButton, Qt.KeyboardModifier.NoModifier,
                             QPoint(int(result["x"]), int(result["y"])))
            QTest.keyClick(target, Qt.Key.Key_A, Qt.KeyboardModifier.ControlModifier)
            event = QInputMethodEvent()
            event.setCommitString(state["prompt"])
            QApplication.sendEvent(target, event)
            QTimer.singleShot(600, lambda: self._verify_native_fill(state))

        self._request_js(state, "fill_pending", self._dom_js(name, "focus",
                         expected_draft=state.get("expected_draft", ""), prompt=state["prompt"]), focused)

    def _verify_native_fill(self, state: dict) -> None:
        if self._active_requests.get(state["id"]) is not state:
            return
        name = state["service"].name

        def verified(result) -> None:
            self._change(state, native_fill_pending=False)
            if str(result.get("input_text") or "").strip() == state["prompt"]:
                self._log(state, "fill_method", "Qt native text input")
                self._wait_for_send(state)
            elif state["fill_attempts"] <= 2:
                self._fill_request(state)
            else:
                self._finish_async(state["id"], False, name + " не подтвердил вставку текста")

        self._request_js(state, "fill_pending", self._inspect_js(name), verified)

    def _wait_for_send(self, state: dict) -> None:
        if self._active_requests.get(state['id']) is not state:
            return
        if state.get("submitted") or state.get("send_triggered") or state.get("send_pending"):
            return
        name = state["service"].name
        self._change(state, send_attempts=state["send_attempts"] + 1)
        use_enter = state["send_attempts"] >= self._adapter(name).enter_after_attempts

        def sent(result) -> None:
            if result.get("service_error"):
                self._set_availability(name, "unavailable", result["service_error"])
                self._finish_async(state["id"], False, result["service_error"])
                return
            if not result.get("triggered"):
                if result.get("reason") == "user_draft_changed":
                    self._finish_async(state["id"], False, "Черновик сервиса изменился")
                    return
                self._set_request_state(name, "ждёт кнопку отправки")
                QTimer.singleShot(500, lambda: self._wait_for_send(state))
                return
            self._change(state, send_triggered=True, send_confirm_started_at=time.monotonic())
            self._log(state, "send_method", result)
            self._set_request_state(name, "подтверждает отправку")
            self._confirm_submission(state)

        self._request_js(state, "send_pending",
                         self._send_js(name, use_enter, state["id"], state["prompt"]), sent)

    def _confirm_submission(self, state: dict) -> None:
        if self._active_requests.get(state['id']) is not state:
            return
        if state.get("submitted"):
            return
        name = state["service"].name

        def confirmed(result) -> None:
            if result.get("accepted"):
                self._change(state, submitted=True, submitted_at=time.monotonic(),
                             deadline=time.monotonic() + state["timeout_ms"] / 1000,
                             saw_generating=bool(result.get("generating")))
                self._set_request_state(name, "ожидает ответа")
                return
            if result.get("login_required") or result.get("blocked_reason"):
                self._set_availability(name, "login_required" if result.get("login_required") else "unavailable",
                                       self._blocked_reason_text(result["blocked_reason"]) if result.get("blocked_reason") else "Требуется авторизация")
                self._finish_async(state["id"], False, self._availability[name]["reason"])
                return
            # Ambiguous acceptance never retries a click: delayed acknowledgements
            # must not submit a second copy. The fallback is extraction evidence.
            if time.monotonic() - state["send_confirm_started_at"] >= self.SEND_CONFIRM_TIMEOUT_MS / 1000:
                self._finish_async(state["id"], False, "Сервис не подтвердил отправку сообщения")
                return
            QTimer.singleShot(300, lambda: self._confirm_submission(state))

        self._request_js(state, "send_confirm_pending",
                         self._confirm_submission_js(name, state["prompt"],
                                                     state["before_user_count"], state["before_user_ids"]),
                         confirmed)

    def _accept_candidate(self, state: dict, payload: dict) -> None:
        if payload.get("service_error"):
            self._set_availability(state["service"].name, "unavailable", payload["service_error"])
            self._finish_async(state["id"], False, payload["service_error"])
            return
        generating = bool(payload.get("generating"))
        if generating:
            self._change(state, saw_generating=True)
        candidate = clean_response(repair_mojibake(str(payload.get("text") or "")))
        if not candidate or candidate == state["prompt"]:
            return
        now = time.monotonic()
        if candidate != state["last_candidate"]:
            self._change(state, last_candidate=candidate, last_changed_at=now, stable=1)
            if not state["first_response_at"]:
                self._change(state, first_response_at=self._now())
            self._log(state, "response_selector", payload.get("selector"))
            self._set_request_state(state["service"].name, "отвечает")
            return
        self._change(state, stable=state["stable"] + 1)
        if response_complete(candidate, state["stable"], now-state["last_changed_at"],
                             generating, state["saw_generating"]):
            self._finish_async(state["id"], True, candidate)

    def _poll_active_requests(self) -> None:
        if not self._active_requests:
            self._poll_timer.stop()
            return
        now = time.monotonic()
        for rid, state in list(self._active_requests.items()):
            if state.get("published"):
                continue
            if now >= state["deadline"]:
                partial = clean_response(state["last_candidate"])
                if partial and state["submitted"]:
                    self._log(state, "truncated", f"Response truncated for {state['service'].name}: {rid}")
                    self._finish_async(rid, True, partial + "\n[обрезано]")
                else:
                    self._set_availability(state["service"].name, "unavailable", "Ответ не получен")
                    self._finish_async(rid, False, "Ответ не получен до истечения времени ожидания")
                continue
            if state["submitted"]:
                self._request_js(state, "poll_pending",
                                 self._extract_new_response_js(state["service"].name,
                                                               state["before_ids"], state["before_texts"]),
                                 lambda payload, current=state: self._accept_candidate(current, payload))

    def _start_next_queued(self, service_name: str) -> None:
        if self._closing or service_name in self._busy_by_service:
            return
        queue = self._queues[service_name]
        if not queue:
            self._set_request_state(service_name, self.service_status(service_name))
            return
        item = queue.pop(0)
        if self.service_status(service_name) in {"unavailable", "login_required"}:
            try:
                item["callback"](False, self._availability[service_name]["reason"])
            finally:
                QTimer.singleShot(0, lambda: self._start_next_queued(service_name))
            return
        self._begin_async_request(self._service(service_name), item)

    def _finish_async(self, request_id: str, success: bool, text: str) -> None:
        state = self._active_requests.get(request_id)
        if state is None or state.get("published"):
            return
        safe_text = clean_response(repair_mojibake(str(text))) if success else str(text)
        if success and not safe_text:
            return
        name = state["service"].name
        self._change(state, published=bool(success), publish_count=int(bool(success)),
                     completed_at=self._now(), last_error="" if success else safe_text)
        for key in list(state):
            if key.endswith("_pending"):
                self._change(state, **{key: False})
        state["success"] = bool(success)
        state["response"] = safe_text
        state["response_hash"] = hashlib.sha256(safe_text.encode("utf-8")).hexdigest() if success else ""
        self._active_requests.pop(request_id, None)
        self._last_page_used[name] = time.monotonic()
        self._busy_by_service.pop(name, None)
        self._request_started_at.pop(request_id, None)
        snapshot = {key: value for key, value in state.items() if key not in {"callback", "page"}}
        self._completed_requests[request_id] = snapshot
        while len(self._completed_requests) > 100:
            self._completed_requests.popitem(last=False)
        self._log(state, "publication" if success else "failure",
                  {"author": name, "role": "assistant", "publish_count": state["publish_count"],
                   "hash": state["response_hash"], "text": safe_text})
        self._run_js_json(state["page"], self._stop_response_observer_js(request_id), lambda _result: None)
        try:
            state["callback"](bool(success), safe_text)
        except Exception:
            self._logger.exception("Result consumer failed for %s", request_id)
            self._set_status(name + ": ошибка сохранения результата")
        finally:
            self._set_request_state(name, self.service_status(name))
            QTimer.singleShot(0, lambda: self._start_next_queued(name))

    def cancel_service_request(self, service_name: str) -> None:
        queue, self._queues[service_name] = self._queues[service_name], []
        rid = self._busy_by_service.get(service_name)
        if rid:
            self._finish_async(rid, False, "Запрос остановлен")
        for item in queue:
            try:
                item["callback"](False, "Запрос в очереди остановлен")
            except Exception:
                self._logger.exception("Queued cancellation callback failed")
        self._set_request_state(service_name, self.service_status(service_name))

    def cancel_all_requests(self) -> None:
        for name in self.service_names():
            self.cancel_service_request(name)
        self._poll_timer.stop()
        self._set_status("Текущие ожидания остановлены. Общая переписка сохранена.")

    def ask_service_blocking(self, service_name: str, prompt: str, timeout_ms: int | None = None) -> str:
        """Compatibility wrapper: waits until the service returns or is cancelled."""
        loop = QEventLoop()
        result = {"success": False, "text": ""}

        def callback(success: bool, text: str) -> None:
            result["success"] = success
            result["text"] = text
            loop.quit()

        effective_timeout = SERVICE_TIMEOUTS_MS.get(service_name, self.DEFAULT_REQUEST_TIMEOUT_MS) if timeout_ms is None else max(5_000, int(timeout_ms))
        self.start_service_request(service_name, prompt, callback, timeout_ms=effective_timeout)
        QTimer.singleShot(
            effective_timeout + 1_000,
            lambda: (loop.quit() if not result["success"] and not result["text"] else None),
        )
        loop.exec()
        if not result["success"]:
            raise RuntimeError(f"{service_name}: {result['text']}")
        return result["text"]

    def close_profiles(self) -> None:
        if self._closing:
            return

        self._closing = True
        self._status_timer.stop()
        self._idle_timer.stop()

        try:
            self.cancel_all_requests()
        except Exception:
            logging.getLogger("AI-Council.service_browser").debug("Qt cleanup object is unavailable")

        try:
            self._poll_timer.stop()
        except Exception:
            logging.getLogger("AI-Council.service_browser").debug("Qt cleanup object is unavailable")

        # Detach the visible page before shutting the widget down. The actual
        # QWebEnginePage/QWebEngineProfile objects remain parented to this widget
        # until Qt destroys the object tree, avoiding premature profile release.
        try:
            self.web.stop()
        except Exception:
            logging.getLogger("AI-Council.service_browser").debug("Qt cleanup object is unavailable")

        try:
            placeholder = QWebEnginePage(self)
            self._placeholder_page = placeholder
            self.web.setPage(placeholder)
        except Exception:
            logging.getLogger("AI-Council.service_browser").debug("Qt cleanup object is unavailable")

        for popup, page in list(self._popups):
            try:
                popup.close()
            except Exception:
                logging.getLogger("AI-Council.service_browser").debug("Qt cleanup object is unavailable")
            try:
                page.stop()
            except Exception:
                logging.getLogger("AI-Council.service_browser").debug("Qt cleanup object is unavailable")

        self._popups.clear()

        for page in list(self._pages.values()):
            try:
                page.stop()
            except Exception:
                logging.getLogger("AI-Council.service_browser").debug("Qt cleanup object is unavailable")

        # Release page objects before their profiles. This also releases Windows
        # LevelDB locks immediately when closing a diagnostic or a workspace.
        for view in self._render_views.values():
            if isValid(view):
                view.setPage(QWebEnginePage(view))
        for page in list(self._pages.values()):
            if isValid(page):
                delete_qt(page)
        self._pages.clear()
        for profile in list(self._profiles.values()):
            if isValid(profile):
                delete_qt(profile)
        self._profiles.clear()
        self._status_pending.clear()
