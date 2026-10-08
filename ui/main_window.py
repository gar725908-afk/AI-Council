from __future__ import annotations

from datetime import datetime
import json
from uuid import uuid4
from pathlib import Path
from html import escape

from PySide6.QtCore import Qt, QSize, QSignalBlocker
from PySide6.QtWidgets import (
    QApplication,
    QFrame,
    QFileDialog,
    QHBoxLayout,
    QLabel,
    QListWidget,
    QListWidgetItem,
    QLineEdit,
    QMainWindow,
    QMessageBox,
    QPushButton,
    QInputDialog,
    QStackedWidget,
    QScrollArea,
    QTextBrowser,
    QVBoxLayout,
    QWidget,
)

from core.council import CouncilSession, PARTICIPANTS
from core.materials import MaterialLibrary
from core.storage import LocalStorage
from core.web_council import CouncilWebOrchestrator
from core.text_utils import repair_mojibake, repair_mojibake_tree
from core.version import APP_VERSION
from ui.service_browser import ServiceBrowser
from ui.theme import ACCENTS, apply_theme, icon, logo_icon


class CouncilWorkspace(QWidget):
    """The single live shared-chat workspace for Council and 44-FZ/223-FZ."""

    def __init__(self, storage: LocalStorage, service_browser: ServiceBrowser, workspace_name: str):
        super().__init__()
        self.storage = storage
        self.browser = service_browser
        self.workspace_name = workspace_name
        self.session = CouncilSession(workspace=workspace_name)
        self.attachment_paths: list[str] = []
        material_root = self.storage.workspace_attachment_dir(workspace_name) / "indexed"
        self.materials = MaterialLibrary(material_root)
        self.orchestrator = CouncilWebOrchestrator(self.browser)
        self._service_status: dict[str, QLabel] = {}
        self._build_ui()
        self._load_runtime()
        self._refresh_materials()
        self.browser.request_state_changed.connect(self._on_service_state)
        self.browser.availability_changed.connect(self._on_availability)
        for name in PARTICIPANTS:
            info = self.browser._availability.get(name, {})
            self._on_availability(name, info.get("status", "unknown"), info.get("reason", ""))

    def _build_ui(self) -> None:
        self.setObjectName("workspace")
        root = QVBoxLayout(self)
        root.setContentsMargins(26, 24, 24, 20)
        root.setSpacing(18)

        header = QHBoxLayout()
        heading = QVBoxLayout()
        heading.setSpacing(4)
        self.title = QLabel(self.workspace_name)
        self.title.setObjectName("pageTitle")
        heading.addWidget(self.title)
        subtitle = QLabel("Все мнения — в одной переписке")
        subtitle.setObjectName("muted")
        heading.addWidget(subtitle)
        header.addLayout(heading)
        header.addStretch()
        self.add_btn = QPushButton("Добавить")
        self.add_btn.setIcon(icon("attach"))
        self.add_btn.clicked.connect(self.add_materials)
        self.index_btn = QPushButton("Индексировать")
        self.index_btn.setIcon(icon("index"))
        self.index_btn.setToolTip("Подготовить содержимое материалов для общего контекста")
        self.index_btn.clicked.connect(self.index_materials)
        self.save_btn = QPushButton("Сохранить проект")
        self.save_btn.setIcon(icon("save"))
        self.save_btn.clicked.connect(self.save_project)
        header.addWidget(self.save_btn)
        self.delete_btn = QPushButton()
        self.delete_btn.setObjectName("dangerButton")
        self.delete_btn.setIcon(icon("trash", "#e2a5b2"))
        self.delete_btn.setFixedSize(38, 38)
        self.delete_btn.setToolTip("Удалить переписку — с подтверждением")
        self.delete_btn.clicked.connect(self.clear_conversation)
        header.addWidget(self.delete_btn)
        root.addLayout(header)

        main_split = QHBoxLayout()
        main_split.setSpacing(20)
        root.addLayout(main_split, 1)

        main_col = QVBoxLayout()
        main_col.setSpacing(14)
        conversation = QFrame()
        conversation.setObjectName("conversation")
        conversation_layout = QVBoxLayout(conversation)
        conversation_layout.setContentsMargins(0, 0, 0, 8)
        conversation_layout.setSpacing(0)
        conversation_bar = QFrame()
        conversation_bar.setObjectName("conversationBar")
        bar_layout = QHBoxLayout(conversation_bar)
        bar_layout.setContentsMargins(20, 14, 20, 14)
        chat_title = QLabel("Общая переписка")
        chat_title.setObjectName("cardTitle")
        bar_layout.addWidget(chat_title)
        bar_layout.addStretch()
        shared = QLabel("7 участников")
        shared.setObjectName("pill")
        bar_layout.addWidget(shared)
        conversation_layout.addWidget(conversation_bar)
        self.chat = QTextBrowser()
        self.chat.setObjectName("chat")
        self.chat.setOpenExternalLinks(False)
        self.chat.document().setDefaultStyleSheet(
            "body {color:#e4eaf5;font-family:'Segoe UI';font-size:14px;}"
            "p {margin-top:0;margin-bottom:8px;line-height:145%;}"
            "h3 {font-size:16px;color:#a6b6d2;margin-bottom:18px;}"
        )
        conversation_layout.addWidget(self.chat, 1)
        main_col.addWidget(conversation, 1)

        composer = QFrame()
        composer.setObjectName("composer")
        composer_layout = QVBoxLayout(composer)
        composer_layout.setContentsMargins(16, 10, 12, 10)
        composer_layout.setSpacing(5)
        input_row = QHBoxLayout()
        input_row.setSpacing(8)
        self.input = QLineEdit()
        self.input.setObjectName("messageInput")
        self.input.setMinimumHeight(44)
        self.input.setPlaceholderText("Задайте вопрос или предложите тему…")
        self.input.returnPressed.connect(self.send_message)
        input_row.addWidget(self.input, 1)
        self.send_btn = QPushButton("Отправить")
        self.send_btn.setObjectName("primaryButton")
        self.send_btn.setIcon(icon("arrow", "#0c1532"))
        self.send_btn.setMinimumHeight(44)
        self.send_btn.setToolTip("Отправить всем доступным участникам · Enter")
        self.send_btn.clicked.connect(self.send_message)
        input_row.addWidget(self.send_btn)
        self.stop_btn = QPushButton()
        self.stop_btn.setObjectName("quietButton")
        self.stop_btn.setIcon(icon("stop"))
        self.stop_btn.setFixedSize(40, 44)
        self.stop_btn.setToolTip("Остановить текущие ожидания и очередь")
        self.stop_btn.clicked.connect(self.stop_requests)
        input_row.addWidget(self.stop_btn)
        composer_layout.addLayout(input_row)
        composer_hint = QLabel("Enter — отправить  ·  История сохраняется автоматически")
        composer_hint.setObjectName("eyebrow")
        composer_layout.addWidget(composer_hint)
        main_col.addWidget(composer)
        main_split.addLayout(main_col, 3)

        side_column = QVBoxLayout()
        side_column.setSpacing(14)
        side = QFrame()
        side.setObjectName("participants")
        side_layout = QVBoxLayout(side)
        side_layout.setContentsMargins(16, 16, 16, 12)
        side_layout.setSpacing(4)
        participant_title = QLabel("Участники Совета")
        participant_title.setObjectName("cardTitle")
        side_layout.addWidget(participant_title)
        participant_hint = QLabel("Нажмите на имя, чтобы открыть сервис")
        participant_hint.setObjectName("eyebrow")
        side_layout.addWidget(participant_hint)
        side_layout.addSpacing(8)
        initials = {"ChatGPT":"GPT", "Claude":"C", "Kimi":"K", "DeepSeek":"DS", "Grok":"G", "Qwen":"Q", "Алиса":"А"}
        for name in PARTICIPANTS:
            clean_name = str(repair_mojibake(name))
            row_frame = QFrame()
            row_frame.setObjectName("participantRow")
            row_frame.setMinimumHeight(54)
            row = QHBoxLayout(row_frame)
            row.setContentsMargins(0, 8, 0, 8)
            row.setSpacing(10)
            avatar = QLabel(initials.get(clean_name, clean_name[:1]))
            avatar.setObjectName("avatar")
            avatar.setAlignment(Qt.AlignCenter)
            avatar.setFixedSize(34, 34)
            accent = ACCENTS.get(clean_name, "#77b9ef")
            avatar.setStyleSheet(f"background:#25334a;color:{accent};border:1px solid #3a4860;border-radius:10px;")
            row.addWidget(avatar)
            identity = QVBoxLayout()
            identity.setSpacing(3)
            name_label = QPushButton(clean_name)
            name_label.setObjectName("participantName")
            name_label.setCursor(Qt.PointingHandCursor)
            name_label.setFlat(True)
            name_label.clicked.connect(lambda checked=False, n=clean_name: self._open_participant(n))
            identity.addWidget(name_label)
            status = QLabel("не проверен")
            status.setWordWrap(True)
            status.setStyleSheet("color:#8f9db5;font-size:11px;")
            identity.addWidget(status)
            row.addLayout(identity, 1)
            refresh = QPushButton()
            refresh.setObjectName("iconButton")
            refresh.setIcon(icon("refresh", size=18))
            refresh.setFixedSize(28, 30)
            refresh.setToolTip("Обновить статус " + clean_name)
            refresh.clicked.connect(lambda checked=False, n=clean_name: self.browser.refresh_service_status(n))
            row.addWidget(refresh)
            side_layout.addWidget(row_frame)
            self._service_status[clean_name] = status
        side_column.addWidget(side)

        materials_card = QFrame()
        materials_card.setObjectName("materials")
        materials_layout = QVBoxLayout(materials_card)
        materials_layout.setContentsMargins(16, 14, 16, 14)
        materials_layout.setSpacing(8)
        material_title = QLabel("Материалы")
        material_title.setObjectName("cardTitle")
        materials_layout.addWidget(material_title)
        material_controls = QHBoxLayout()
        material_controls.setSpacing(6)
        material_controls.addWidget(self.add_btn)
        material_controls.addWidget(self.index_btn)
        materials_layout.addLayout(material_controls)
        self.material_list = QListWidget()
        self.material_list.setObjectName("materialList")
        self.material_list.setMinimumHeight(42)
        materials_layout.addWidget(self.material_list, 1)
        self.material_hint = QLabel("Документы и изображения для общего контекста")
        self.material_hint.setWordWrap(True)
        self.material_hint.setObjectName("eyebrow")
        materials_layout.addWidget(self.material_hint)
        side_column.addWidget(materials_card, 1)
        side_container = QWidget()
        side_container.setMinimumWidth(288)
        side_container.setLayout(side_column)
        side_column.setContentsMargins(0, 0, 0, 0)
        side_scroll = QScrollArea()
        side_scroll.setObjectName("rightColumn")
        side_scroll.setFrameShape(QFrame.NoFrame)
        side_scroll.setWidgetResizable(True)
        side_scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        side_scroll.setFixedWidth(306)
        side_scroll.setWidget(side_container)
        main_split.addWidget(side_scroll)

        self.status = QLabel("Готов к работе")
        self.status.setObjectName("statusLine")
        self.status.setWordWrap(True)
        root.addWidget(self.status)
        self._show_welcome()

    def _show_welcome(self) -> None:
        self._chat_empty = True
        self.chat.setHtml(
            '<br><br><p align="center" style="color:#a4b8ff;font-size:30px;">✦</p>'
            '<p align="center" style="font-size:22px;color:#edf1ff;"><b>Одна тема. Семь точек зрения.</b></p>'
            '<p align="center" style="color:#9dacc4;font-size:14px;">Задайте вопрос, приложите материалы<br>'
            'и соберите ответы в общей переписке.</p>'
        )

    def _load_runtime(self) -> None:
        payload = self.storage.load_active_for(self.workspace_name)
        if not payload:
            return
        session_data = repair_mojibake_tree(payload.get("session") or {})
        attachments = repair_mojibake_tree(payload.get("attachments") or [])
        try:
            self.session = CouncilSession.from_dict(session_data)
            self.attachment_paths = list(attachments)
            self._render_session()
            # Persist the repaired transcript so mojibake is removed permanently.
            self._save_runtime()
        except Exception:
            self.session = CouncilSession(workspace=self.workspace_name)

    def _render_session(self) -> None:
        self.chat.clear()
        self._chat_empty = False
        if not self.session.messages:
            self._show_welcome()
            return
        if self.session.title and self.session.title != "Новый Совет":
            self.chat.append(f"<h3>{escape(self.session.title)}</h3>")
        for message in self.session.messages:
            self._append_chat_message(message.author, message.text, message.sender_type)

    def _append_chat_message(self, author: str, text: str, sender_type: str) -> None:
        author = str(repair_mojibake(author))
        text = str(repair_mojibake(text))
        safe_author = escape(author)
        safe_text = escape(text).replace("\n", "<br>")
        if self._chat_empty:
            self.chat.clear()
            self._chat_empty = False
        accent = ACCENTS.get(author, "#a4b8ff")
        role = "Вы" if sender_type == "user" else "Участник Совета"
        background = "#243453" if sender_type == "user" else "#1b283e"
        self.chat.append(
            f'<table width="100%" cellspacing="0" cellpadding="14" bgcolor="{background}">'
            f'<tr><td><p style="font-size:12px;color:{accent};"><b>{safe_author}</b>'
            f' &nbsp; <span style="color:#8595af;">{role}</span></p>'
            f'<p style="font-size:14px;color:#e4eaf5;">{safe_text}</p></td></tr></table>'
            '<p style="font-size:6px;">&nbsp;</p>'
        )
        scrollbar = self.chat.verticalScrollBar()
        scrollbar.setValue(scrollbar.maximum())

    def _save_runtime(self) -> None:
        self.storage.save_active(self.session.to_dict(), self.attachment_paths)

    def add_materials(self) -> None:
        files, _ = QFileDialog.getOpenFileNames(
            self,
            "Добавить материалы",
            str(Path.home()),
            "Материалы (*.pdf *.png *.jpg *.jpeg *.webp *.bmp *.gif *.mp4 *.mov *.avi *.mkv *.webm *.docx *.doc *.xlsx *.xls *.pptx *.ppt *.txt *.md *.json *.csv);;Все файлы (*.*)",
        )
        if not files:
            return
        copied: list[str] = []
        for file_path in files:
            try:
                copied.append(self.storage.copy_attachment_to_runtime(file_path, self.workspace_name))
            except OSError as exc:
                QMessageBox.warning(self, "Материал", f"Не удалось добавить {file_path}: {exc}")
        self.attachment_paths.extend(copied)
        self.materials.add_files(copied)
        self._save_runtime()
        self._refresh_materials()
        self.status.setText(f"Добавлено материалов: {len(copied)}")

    def index_materials(self) -> None:
        if not self.materials.items:
            QMessageBox.information(self, "Материалы", "Сначала добавь материалы.")
            return
        self.index_btn.setEnabled(False)
        try:
            self.status.setText("Индексирую материалы локально…")
            self.materials.index_all()
            self.status.setText(f"Индексирование завершено. Материалов: {len(self.materials.items)}")
        finally:
            self.index_btn.setEnabled(True)
            self._refresh_materials()

    def _refresh_materials(self) -> None:
        self.material_list.clear()
        for item in self.materials.items:
            status = "✓" if item.index_status.startswith("indexed") else "•"
            self.material_list.addItem(f"{status} {item.name}")
        self.material_hint.setText(
            f"Материалов: {len(self.materials.items)} · Хранятся локально"
            if self.materials.items else "Файлов пока нет. Добавьте документы или изображения."
        )
        self.material_list.setVisible(bool(self.materials.items))

    def _open_participant(self, name: str) -> None:
        window = self.window()
        if hasattr(window, "pages"):
            window.pages.setCurrentWidget(self.browser)
        self.browser.service_combo.setCurrentText(name)
        self.browser.open_service(name)

    def _on_availability(self, name: str, state: str, reason: str) -> None:
        label = self._service_status.get(name)
        if label is not None:
            label.setToolTip(reason)
        if name not in self.browser._busy_by_service:
            self._on_service_state(name, state)

    def context_builder(self, query: str, origin_id: str = "") -> str:
        # Queued requests include late replies to earlier turns, excluding future turns.
        origin_index = next((i for i, m in enumerate(self.session.messages)
                             if m.request_id == origin_id), len(self.session.messages))
        prior_users = {m.request_id for m in self.session.messages[:origin_index]
                       if m.sender_type == "user"}
        messages = []
        for index, message in enumerate(self.session.messages):
            if message.sender_type == "user":
                if index >= origin_index:
                    continue
            elif message.parent_message_id:
                if message.parent_message_id not in prior_users:
                    continue
            elif index >= origin_index:
                continue
            messages.append({"author": message.author, "role": message.role, "text": message.text})
        messages = messages[-50:]
        while messages and len(json.dumps(messages, ensure_ascii=False)) > 24_000:
            messages.pop(0)
        materials = self.materials.relevant_context(query, max_chars=2_500)
        return json.dumps({"messages": messages, "materials": materials}, ensure_ascii=False)

    def send_message(self) -> None:
        prompt = self.input.text().strip()
        if not prompt:
            return

        timestamp = datetime.now().isoformat(timespec="seconds")
        self.session.title = self.session.title if self.session.title != "Новый Совет" else prompt[:80]
        origin_id = uuid4().hex
        self.session.add("Ты", prompt, timestamp=timestamp, sender_type="user", request_id=origin_id)
        self._append_chat_message("Ты", prompt, "user")
        self._save_runtime()
        self.input.clear()

        self.status.setText(
            "Сообщение принято. Все доступные участники запускаются параллельно; "
            "медленные или недоступные ИИ не блокируют остальных."
        )

        try:
            batch = {"id": ""}
            batch["id"] = self.orchestrator.send_message(
                prompt=prompt,
                participants=PARTICIPANTS,
                context_builder=lambda query: self.context_builder(query, origin_id),
                on_message=lambda name, ok, text: self._receive_message(
                    name, ok, text, batch["id"] + ":" + name, origin_id),
                on_status=self._update_status,
            )
        except Exception as exc:
            self.status.setText(f"Ошибка запуска Совета: {exc}")
            QMessageBox.warning(self, "Совет ИИ", str(exc))

    def stop_requests(self) -> None:
        self.orchestrator.stop_all()
        self.status.setText("Текущие ожидания остановлены. История Совета сохранена.")

    def _update_status(self, text: str) -> None:
        self.status.setText(text)

    def _receive_message(self, service: str, success: bool, text: str,
                         request_id: str = "", parent_message_id: str = "") -> None:
        if not success:
            # Errors belong in the participant status, not in the AI transcript.
            return
        if service not in PARTICIPANTS:
            return
        if request_id and any(m.author == service and m.request_id == request_id for m in self.session.messages):
            return
        text = str(repair_mojibake(text))
        self.session.add(
            str(repair_mojibake(service)),
            text,
            timestamp=datetime.now().isoformat(timespec="seconds"),
            sender_type="ai",
            request_id=request_id,
            parent_message_id=parent_message_id,
        )
        self._append_chat_message(service, text, "ai")
        self._save_runtime()

    def _on_service_state(self, service: str, state: str) -> None:
        service = str(repair_mojibake(service)).strip()
        state = str(repair_mojibake(state)).strip()
        label = self._service_status.get(service)
        if label is None:
            return
        labels = {"online": "доступен", "unavailable": "недоступен",
                  "login_required": "требуется вход", "unknown": "не проверен"}
        label.setText("● " + labels.get(state, state))
        color = "#98adcf"
        if state in {"ошибка", "unavailable"}:
            color = "#ed9eac"
        elif state in ("online", "готов"):
            color = "#69d5b4"
        elif state in ("отвечает", "анализирует"):
            color = "#a6b8ff"
        elif state in ("долго отвечает", "долго запускается", "login_required"):
            color = "#e6be7b"
        label.setStyleSheet(f"color:{color};font-size:11px;")

    def clear_conversation(self) -> None:
        ans = QMessageBox.question(
            self,
            "Удалить переписку",
            f"Удалить всю переписку рабочего пространства «{self.workspace_name}» и его временные материалы?\n\nАккаунты ИИ и сохранённые проекты не затронутся.",
            QMessageBox.Yes | QMessageBox.No,
            QMessageBox.No,
        )
        if ans != QMessageBox.Yes:
            return
        self.orchestrator.stop_all()
        self.session = CouncilSession(workspace=self.workspace_name)
        self.attachment_paths = []
        self.storage.clear_active_for(self.workspace_name, True)
        self.materials = MaterialLibrary(self.storage.workspace_attachment_dir(self.workspace_name) / "indexed")
        self._show_welcome()
        self._refresh_materials()
        self.status.setText("Переписка очищена.")

    def save_project(self) -> None:
        if not self.session.messages:
            QMessageBox.information(self, "Проект", "В текущем Совете пока нет переписки.")
            return
        name, ok = QInputDialog.getText(
            self,
            "Сохранить проект",
            "Название проекта:",
            text=self.session.title[:80],
        )
        if not ok or not name.strip():
            return
        try:
            project = self.storage.save_project(
                name=name.strip(),
                session=self.session.to_dict(),
                attachment_paths=self.attachment_paths
                + [m.stored_path for m in self.materials.items if m.stored_path],
                kind=self.workspace_name,
            )
            self.status.setText(f"Проект сохранён: {project}")
            QMessageBox.information(self, "Проект сохранён", f"Проект сохранён локально:\n{project}")
        except Exception as exc:
            QMessageBox.warning(self, "Проект", f"Не удалось сохранить проект: {exc}")


class ProjectsPage(QWidget):
    def __init__(self, storage: LocalStorage):
        super().__init__()
        self.storage = storage
        self.setObjectName("contentPage")
        root = QVBoxLayout(self)
        root.setContentsMargins(28, 26, 28, 24)
        root.setSpacing(18)
        title = QLabel("Проекты")
        title.setObjectName("pageTitle")
        root.addWidget(title)
        hint = QLabel(
            "Проекты — отдельные локальные копии переписки и материалов. "
            "Рабочая история сохраняется автоматически независимо от кнопки сохранения."
        )
        hint.setWordWrap(True)
        hint.setObjectName("muted")
        root.addWidget(hint)
        self.list = QListWidget()
        self.list.setObjectName("projectList")
        root.addWidget(self.list, 1)
        controls = QHBoxLayout()
        open_btn = QPushButton("Открыть папку")
        open_btn.setIcon(icon("folder"))
        open_btn.clicked.connect(self.open_selected)
        controls.addWidget(open_btn)
        delete_btn = QPushButton("Удалить проект")
        delete_btn.setObjectName("dangerButton")
        delete_btn.setIcon(icon("trash", "#e2a5b2"))
        delete_btn.clicked.connect(self.delete_selected)
        controls.addWidget(delete_btn)
        refresh = QPushButton("Обновить")
        refresh.setIcon(icon("refresh"))
        refresh.clicked.connect(self.refresh)
        controls.addWidget(refresh)
        root.addLayout(controls)
        self.refresh()

    def refresh(self) -> None:
        self.list.clear()
        for path in self.storage.list_projects():
            self.list.addItem(QListWidgetItem(path.name))

    def _selected_path(self) -> Path | None:
        item = self.list.currentItem()
        if not item:
            return None
        return self.storage.projects / item.text()

    def open_selected(self) -> None:
        path = self._selected_path()
        if path:
            self.storage.open_in_explorer(path)

    def delete_selected(self) -> None:
        path = self._selected_path()
        if not path:
            return
        ans = QMessageBox.question(
            self,
            "Удалить проект",
            f"Удалить проект «{path.name}» безвозвратно?",
            QMessageBox.Yes | QMessageBox.No,
        )
        if ans == QMessageBox.Yes:
            self.storage.delete_project(path)
            self.refresh()


class SettingsPage(QWidget):
    def __init__(self, storage: LocalStorage, service_browser: ServiceBrowser):
        super().__init__()
        self.service_browser = service_browser
        self.setObjectName("contentPage")
        root = QVBoxLayout(self)
        root.setContentsMargins(28, 26, 28, 24)
        root.setSpacing(18)
        title = QLabel("Настройки")
        title.setObjectName("pageTitle")
        root.addWidget(title)
        card = QFrame()
        card.setObjectName("settingsCard")
        details = QVBoxLayout(card)
        details.setContentsMargins(24, 22, 24, 22)
        details.setSpacing(16)
        app_name = QLabel(f"АИ Консилиум · AI Council {APP_VERSION}")
        app_name.setObjectName("cardTitle")
        details.addWidget(app_name)
        path_label = QLabel(f"Локальное хранилище:\n{storage.root}")
        path_label.setWordWrap(True)
        path_label.setTextInteractionFlags(Qt.TextSelectableByMouse)
        path_label.setObjectName("muted")
        details.addWidget(path_label)
        note = QLabel(
            "Пароли аккаунтов ИИ программа не хранит. Каждый веб-сервис использует "
            "отдельный постоянный профиль. Сетевые настройки программой не изменяются.\n\n"
            "Общая история Совета автоматически сохраняется локально."
        )
        note.setWordWrap(True)
        note.setObjectName("muted")
        details.addWidget(note)
        root.addWidget(card)
        add_card = QFrame()
        add_layout = QVBoxLayout(add_card)
        add_layout.addWidget(QLabel("Добавить ИИ по адресу сайта"))
        self.ai_name = QLineEdit()
        self.ai_name.setPlaceholderText("Название ИИ")
        self.ai_url = QLineEdit()
        self.ai_url.setPlaceholderText("https://example.com/chat")
        add_layout.addWidget(self.ai_name)
        add_layout.addWidget(self.ai_url)
        add_button = QPushButton("Добавить ИИ (после перезапуска)")
        add_button.clicked.connect(self.add_custom_ai)
        add_layout.addWidget(add_button)
        root.addWidget(add_card)
        root.addStretch()

    def add_custom_ai(self):
        from urllib.parse import urlparse
        from ui.service_browser import CUSTOM_SERVICES_FILE, SERVICES, load_custom_services
        name = self.ai_name.text().strip()
        url = self.ai_url.text().strip()
        parsed = urlparse(url)
        if not name or not parsed.hostname or parsed.scheme != "https" or any(c in name for c in "\\/:*?\"<>|"):
            QMessageBox.warning(self, "Проверка", "Укажите название без спецсимволов и корректный HTTPS-адрес.")
            return
        if any(item.name.casefold() == name.casefold() for item in SERVICES):
            QMessageBox.warning(self, "Проверка", "Участник с таким названием уже существует.")
            return
        entries = [{"name": item.name, "url": item.url} for item in load_custom_services()]
        entries.append({"name": name, "url": url})
        CUSTOM_SERVICES_FILE.parent.mkdir(parents=True, exist_ok=True)
        CUSTOM_SERVICES_FILE.write_text(json.dumps(entries, ensure_ascii=False, indent=2), encoding="utf-8")
        QMessageBox.information(self, "ИИ добавлен", "Сохранено. Перезапустите AI Консилиум. Вход будет храниться в отдельном профиле. Совместимость отправки и чтения ответов зависит от сайта.")
        self.ai_name.clear()
        self.ai_url.clear()


class MainWindow(QMainWindow):
    def __init__(self, storage: LocalStorage | None = None,
                 service_browser: ServiceBrowser | None = None):
        super().__init__()
        app = QApplication.instance()
        apply_theme(app)
        self.setWindowTitle(f"AI Council {APP_VERSION} · АИ Консилиум")
        self.setWindowIcon(logo_icon())
        screen = app.primaryScreen().availableGeometry()
        self.resize(min(1560, screen.width() - 48), min(940, screen.height() - 48))
        self.setMinimumSize(1100, 700)
        self.storage = storage if storage is not None else LocalStorage()
        self.service_browser = service_browser if service_browser is not None else ServiceBrowser()
        self._render_host = QWidget(self)
        self._render_host.setAttribute(Qt.WA_DontShowOnScreen, True)
        self._render_host.resize(1280, 800)
        self._render_host.show()
        self.service_browser.enable_background_rendering(self._render_host)
        self._build_ui()

    def _build_ui(self) -> None:
        root = QWidget()
        root.setObjectName("appRoot")
        self.setCentralWidget(root)
        layout = QHBoxLayout(root)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)

        sidebar = QFrame()
        sidebar.setObjectName("sidebar")
        sidebar.setFixedWidth(224)
        sidebar_layout = QVBoxLayout(sidebar)
        sidebar_layout.setContentsMargins(16, 26, 16, 20)
        sidebar_layout.setSpacing(24)
        brand = QHBoxLayout()
        brand.setSpacing(10)
        mark = QLabel()
        mark.setPixmap(logo_icon().pixmap(38, 38))
        brand.addWidget(mark)
        brand_text = QVBoxLayout()
        brand_text.setSpacing(2)
        title = QLabel("АИ Консилиум")
        title.setObjectName("brandTitle")
        brand_text.addWidget(title)
        tagline = QLabel("НЕСКОЛЬКО МНЕНИЙ. ОДИН СОВЕТ.")
        tagline.setObjectName("brandSubtitle")
        tagline.setStyleSheet("font-size:8px;")
        brand_text.addWidget(tagline)
        brand.addLayout(brand_text, 1)
        sidebar_layout.addLayout(brand)

        nav_label = QLabel("РАБОЧЕЕ ПРОСТРАНСТВО")
        nav_label.setObjectName("eyebrow")
        sidebar_layout.addWidget(nav_label)
        self.nav = QListWidget()
        self.nav.setObjectName("navigation")
        self.nav.setSpacing(2)
        self.nav.setIconSize(QSize(20, 20))
        self.nav.setCursor(Qt.PointingHandCursor)
        labels = [
            ("Совет ИИ", "chat"),
            ("Проекты", "folder"),
            ("44-ФЗ / 223-ФЗ", "scale"),
            ("ИИ-сервисы", "cpu"),
            ("Настройки", "settings"),
        ]
        for label, symbol in labels:
            self.nav.addItem(QListWidgetItem(icon(symbol), label))
        self.nav.setCurrentRow(0)
        sidebar_layout.addWidget(self.nav, 1)

        footer = QFrame()
        footer.setObjectName("sidebarFooter")
        footer_layout = QVBoxLayout(footer)
        footer_layout.setContentsMargins(14, 14, 14, 14)
        footer_layout.setSpacing(8)
        local = QLabel("●  История на вашем ПК")
        local.setStyleSheet("color:#7ed9be;font-size:12px;font-weight:600;")
        footer_layout.addWidget(local)
        note = QLabel("Общая переписка сохраняется автоматически.")
        note.setWordWrap(True)
        note.setObjectName("eyebrow")
        footer_layout.addWidget(note)
        sidebar_layout.addWidget(footer)
        version = QLabel(f"AI Council {APP_VERSION}")
        version.setObjectName("eyebrow")
        sidebar_layout.addWidget(version)
        layout.addWidget(sidebar)

        self.pages = QStackedWidget()
        self.council_page = CouncilWorkspace(self.storage, self.service_browser, "Совет ИИ")
        self.projects_page = ProjectsPage(self.storage)
        self.fz_page = CouncilWorkspace(self.storage, self.service_browser, "44-ФЗ / 223-ФЗ")
        self.settings_page = SettingsPage(self.storage, self.service_browser)
        for page in [
            self.council_page,
            self.projects_page,
            self.fz_page,
            self.service_browser,
            self.settings_page,
        ]:
            self.pages.addWidget(page)
        layout.addWidget(self.pages, 1)
        self.nav.currentRowChanged.connect(self._navigate)
        self.pages.currentChanged.connect(self._sync_navigation)

    def _sync_navigation(self, row: int) -> None:
        with QSignalBlocker(self.nav):
            self.nav.setCurrentRow(row)

    def _navigate(self, row: int) -> None:
        self.pages.setCurrentIndex(row)
        if row == 1:
            self.projects_page.refresh()
        if row == 3:
            self.service_browser.open_service(self.service_browser.current_service.name)
        else:
            self.service_browser.background_current_service()

    def closeEvent(self, event) -> None:
        try:
            self.council_page._save_runtime()
            self.fz_page._save_runtime()
            self.service_browser.close_profiles()
        finally:
            event.accept()
