"""Native desktop theme and vector icons for AI Council."""
from __future__ import annotations

from PySide6.QtCore import Qt, QByteArray
from PySide6.QtGui import QColor, QFont, QIcon, QPainter, QPalette, QPixmap
from PySide6.QtSvg import QSvgRenderer

ACCENTS = {
    "ChatGPT": "#52d6b0", "Claude": "#e7ae89", "Kimi": "#a4b8ff",
    "DeepSeek": "#69bcff", "Grok": "#e0e8f7", "Qwen": "#c4a1ff",
    "Алиса": "#dba2ff", "Ты": "#a4b8ff",
}

_PATHS = {
    "chat": '<path d="M21 11.5a8.4 8.4 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.4 8.4 0 0 1-3.8-.9L3 21l1.9-5.7a8.4 8.4 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.4 8.4 0 0 1 3.8-.9h.5a8.5 8.5 0 0 1 8 8v.5Z"/><path d="M8 10h8M8 14h5"/>',
    "folder": '<path d="M3 7V5a2 2 0 0 1 2-2h5l2 3h7a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7Z"/>',
    "scale": '<path d="M12 3v18M6 21h12M5 6h14M5 6 2 13h6L5 6Zm14 0-3 7h6l-3-7Z"/>',
    "cpu": '<rect x="5" y="5" width="14" height="14" rx="3"/><rect x="9" y="9" width="6" height="6" rx="1"/><path d="M9 2v3m6-3v3M9 19v3m6-3v3M2 9h3m-3 6h3m14-6h3m-3 6h3"/>',
    "settings": '<path d="m9 3-1 3-3 1-2 3 2 2-1 3 3 2 3-1 2 2 3-1 1-3 3-1 2-3-2-2 1-3-3-2-3 1-2-2-3 1Z"/><circle cx="12" cy="11" r="3"/>',
    "attach": '<path d="m21 11-9 9a6 6 0 0 1-8.5-8.5l9-9a4 4 0 0 1 5.7 5.7l-9 9a2 2 0 0 1-2.8-2.8L14 7"/>',
    "save": '<path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h12l4 4v12a2 2 0 0 1-2 2Z"/><path d="M7 3v6h10V3M7 21v-8h10v8"/>',
    "trash": '<path d="M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7m4-7v7"/>',
    "refresh": '<path d="M20 7V3l-3 3a8 8 0 1 0 3 10M20 7h-5"/>',
    "arrow": '<path d="M4 12h16m-6-6 6 6-6 6"/>',
    "stop": '<rect x="6" y="6" width="12" height="12" rx="2"/>',
    "index": '<path d="M5 3h10l4 4v14H5V3Zm10 0v5h4M8 12h8m-8 4h6"/>',
}


def _svg_icon(svg: str, size: int) -> QIcon:
    pixmap = QPixmap(size, size)
    pixmap.fill(Qt.GlobalColor.transparent)
    painter = QPainter(pixmap)
    painter.setRenderHint(QPainter.RenderHint.Antialiasing)
    QSvgRenderer(QByteArray(svg.encode("utf-8"))).render(painter)
    painter.end()
    return QIcon(pixmap)


def icon(name: str, color: str = "#aebbd1", size: int = 24) -> QIcon:
    svg = f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="{color}" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">{_PATHS[name]}</svg>'
    return _svg_icon(svg, size)


def logo_icon(size: int = 128) -> QIcon:
    svg = '''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">
      <defs><linearGradient id="g" x2="1" y2="1"><stop stop-color="#7285ff"/><stop offset="1" stop-color="#5158d9"/></linearGradient></defs>
      <rect x="2" y="2" width="60" height="60" rx="17" fill="url(#g)"/>
      <path d="M18 22 32 15 46 22 46 40 32 48 18 40 18 22 46 40M46 22 18 40M32 15v33" fill="none" stroke="#dfe5ff" stroke-opacity=".65" stroke-width="1.8"/>
      <g fill="#fff"><circle cx="18" cy="22" r="3.5"/><circle cx="32" cy="15" r="3.5"/><circle cx="46" cy="22" r="3.5"/><circle cx="46" cy="40" r="3.5"/><circle cx="32" cy="48" r="3.5"/><circle cx="18" cy="40" r="3.5"/><circle cx="32" cy="32" r="5"/></g>
    </svg>'''
    return _svg_icon(svg, size)


STYLESHEET = """
QWidget { color:#e4eaf5; font-family:'Segoe UI'; font-size:13px; }
QMainWindow, QWidget#appRoot, QStackedWidget { background:#0d1320; }
QWidget#workspace, QWidget#contentPage { background:#0d1320; }
QFrame#sidebar { background:#101827; border-right:1px solid #202c40; }
QLabel#brandTitle { font-size:18px; font-weight:700; color:#f5f7ff; }
QLabel#brandSubtitle, QLabel#eyebrow { color:#8998b3; font-size:11px; }
QLabel#pageTitle { font-size:25px; font-weight:700; color:#f5f7ff; }
QLabel#muted, QLabel#statusLine { color:#8f9db5; font-size:12px; }
QLabel#cardTitle { color:#e9eef9; font-size:14px; font-weight:600; }
QLabel#pill { background:#202a49; color:#aebeff; border:1px solid #34436c; border-radius:10px; padding:4px 10px; font-size:11px; }
QFrame#sidebarFooter { background:#162133; border:1px solid #24324b; border-radius:12px; }
QListWidget#navigation { background:transparent; border:0; outline:0; padding:0; }
QListWidget#navigation::item { height:46px; padding:0 14px; margin:3px 0; border-radius:10px; color:#97a6c0; }
QListWidget#navigation::item:hover { background:#1a263b; color:#e4eaf5; }
QListWidget#navigation::item:selected { background:#26345b; color:#eef1ff; border:1px solid #3a4b7b; }
QFrame#conversation, QFrame#participants, QFrame#materials, QFrame#settingsCard { background:#131d2e; border:1px solid #26334a; border-radius:14px; }
QFrame#conversationBar { background:transparent; border-bottom:1px solid #25334a; }
QTextBrowser#chat { background:#131d2e; border:0; padding:16px; selection-background-color:#425b98; selection-color:#ffffff; }
QFrame#composer { background:#172237; border:1px solid #344567; border-radius:14px; }
QLineEdit#messageInput { background:transparent; border:0; padding:10px 2px; font-size:14px; selection-background-color:#425b98; }
QLineEdit { background:#101a2a; border:1px solid #33435f; border-radius:8px; padding:9px 12px; }
QLineEdit:focus { border-color:#8195ff; }
QPushButton { background:#202d43; border:1px solid #34445f; border-radius:8px; padding:9px 13px; color:#d9e3f5; }
QPushButton:hover { background:#2b3a57; border-color:#647baa; }
QPushButton:pressed { background:#344769; }
QPushButton:disabled { background:#1b2435; color:#667590; border-color:#28364c; }
QPushButton#primaryButton { background:#7489f8; border:1px solid #8d9eff; color:#0c1532; font-weight:700; padding:12px 17px; }
QPushButton#primaryButton:hover { background:#94a4ff; }
QPushButton#primaryButton:pressed { background:#6478df; }
QPushButton#primaryButton:disabled { background:#34446b; color:#8290b5; border-color:#42547f; }
QPushButton#quietButton, QPushButton#iconButton { background:transparent; border:1px solid transparent; }
QPushButton#quietButton:hover, QPushButton#iconButton:hover { background:#263652; border-color:#3c5179; }
QPushButton#dangerButton { background:transparent; border:1px solid #403449; color:#e2a5b2; }
QPushButton#dangerButton:hover { background:#442935; border-color:#ad657d; }
QPushButton#participantName { background:transparent; border:0; padding:0; text-align:left; color:#e8eef9; font-size:13px; font-weight:600; }
QPushButton#participantName:hover { color:#a8bbff; }
QFrame#participantRow { background:transparent; border-bottom:1px solid #223049; }
QScrollArea#rightColumn { background:transparent; border:0; }
QLabel#avatar { border-radius:10px; font-size:12px; font-weight:700; }
QListWidget#materialList, QListWidget#projectList { background:transparent; border:0; outline:0; }
QListWidget#materialList::item, QListWidget#projectList::item { padding:10px 8px; border-radius:7px; }
QListWidget#materialList::item:selected, QListWidget#projectList::item:selected { background:#2b3c60; }
QComboBox { background:#172338; border:1px solid #344866; border-radius:8px; padding:8px 12px; }
QComboBox QAbstractItemView { background:#18243a; selection-background-color:#344a78; }
QScrollBar:vertical { background:transparent; width:9px; margin:3px; }
QScrollBar::handle:vertical { background:#354764; min-height:36px; border-radius:3px; }
QScrollBar::handle:vertical:hover { background:#5b719b; }
QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical { height:0; }
QScrollBar::add-page:vertical, QScrollBar::sub-page:vertical { background:transparent; }
QToolTip { background:#23334f; color:#eef2ff; border:1px solid #536789; padding:7px; }
QMessageBox, QInputDialog, QFileDialog { background:#131d2e; }
"""


def apply_theme(app) -> None:
    app.setStyle("Fusion")
    app.setFont(QFont("Segoe UI", 10))
    palette = QPalette()
    for role, color in {
        QPalette.ColorRole.Window:"#0d1320", QPalette.ColorRole.WindowText:"#e4eaf5",
        QPalette.ColorRole.Base:"#131d2e", QPalette.ColorRole.AlternateBase:"#19253a",
        QPalette.ColorRole.Text:"#e4eaf5", QPalette.ColorRole.Button:"#202d43",
        QPalette.ColorRole.ButtonText:"#e4eaf5", QPalette.ColorRole.Highlight:"#425b98",
        QPalette.ColorRole.HighlightedText:"#ffffff", QPalette.ColorRole.Link:"#a4b8ff",
        QPalette.ColorRole.ToolTipBase:"#23334f", QPalette.ColorRole.ToolTipText:"#eef2ff",
    }.items():
        palette.setColor(role, QColor(color))
    palette.setColor(QPalette.ColorGroup.Disabled, QPalette.ColorRole.Text, QColor("#667590"))
    palette.setColor(QPalette.ColorGroup.Disabled, QPalette.ColorRole.ButtonText, QColor("#667590"))
    app.setPalette(palette)
    app.setStyleSheet(STYLESHEET)
    app.setWindowIcon(logo_icon())
