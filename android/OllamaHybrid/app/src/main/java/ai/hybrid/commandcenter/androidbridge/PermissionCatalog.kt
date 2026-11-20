package ai.hybrid.commandcenter.androidbridge

/**
 * Permission Center source of truth (handoff section 7): what NEXUS
 * declares, why, and what it deliberately does not request. Mirrors the
 * manifest — if the manifest changes, this list must change with it.
 */
object PermissionCatalog {

    enum class Grant { INSTALL_TIME, USER_SETTING, RUNTIME }

    data class Entry(
        val permission: String,
        val title: String,
        val purpose: String,
        val grant: Grant
    )

    val declared: List<Entry> = listOf(
        Entry(
            "android.permission.INTERNET",
            "Интернет",
            "Запросы к настроенным провайдерам, Ollama в локальной сети и HTTPS-загрузка моделей.",
            Grant.INSTALL_TIME
        ),
        Entry(
            "android.permission.ACCESS_NETWORK_STATE",
            "Состояние сети",
            "Честный показ типа соединения на Device Core и Command Deck.",
            Grant.INSTALL_TIME
        ),
        Entry(
            "android.permission.REQUEST_INSTALL_PACKAGES",
            "Установка обновлений",
            "Установка APK-обновлений NEXUS только по явному действию пользователя.",
            Grant.USER_SETTING
        ),
        Entry(
            "android.permission.RECORD_AUDIO",
            "Микрофон",
            "NEXUS Voice: распознавание речи только после нажатия кнопки; звук обрабатывает системный распознаватель, постоянного прослушивания нет.",
            Grant.RUNTIME
        )
    )

    /** Capabilities NEXUS does NOT ask for yet; each appears only with a real feature. */
    val notRequested: List<Pair<String, String>> = listOf(
        "Хранилище целиком" to "не нужно: файлы выбираются через системный диалог (SAF)",
        "Контакты, геолокация, камера" to "не используются ни одной функцией",
        "Уведомления" to "будут запрошены, когда появятся фоновые миссии с уведомлениями",
        "Accessibility / захват экрана" to "только после реализации соответствующих инструментов Bridge"
    )
}
