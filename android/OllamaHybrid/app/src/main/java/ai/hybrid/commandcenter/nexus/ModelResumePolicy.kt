package ai.hybrid.commandcenter.nexus

import org.json.JSONObject

/**
 * Pure decisions for resuming an interrupted model download (handoff
 * section 9 named the missing resume support explicitly).
 *
 * Safety invariants:
 * - a partial file is resumed only when its stored metadata matches the
 *   verified manifest (same URL, SHA-256 and size) — otherwise start over;
 * - the full SHA-256 is always recomputed over the complete file, so a
 *   resumed download gets exactly the same integrity check as a fresh one;
 * - a server that ignores Range gets a clean restart, never a corrupt append.
 */
object ModelResumePolicy {

    data class Meta(val url: String, val sha256: String, val sizeBytes: Long)

    sealed class Plan {
        /** Continue from offsetBytes with a Range request. */
        data class Resume(val offsetBytes: Long) : Plan()

        /** All bytes are present: skip the network, verify and promote. */
        object FinalizeOnly : Plan()

        data class StartOver(val reason: String) : Plan()
    }

    fun plan(stored: Meta?, expected: Meta, partialBytes: Long): Plan = when {
        partialBytes <= 0L -> Plan.StartOver("Частичного файла нет.")
        stored == null -> Plan.StartOver("Нет метаданных прежней загрузки.")
        stored.url != expected.url -> Plan.StartOver("Источник файла изменился.")
        !stored.sha256.equals(expected.sha256, ignoreCase = true) ->
            Plan.StartOver("Контрольная сумма манифеста изменилась.")
        stored.sizeBytes != expected.sizeBytes -> Plan.StartOver("Размер файла изменился.")
        partialBytes > expected.sizeBytes -> Plan.StartOver("Частичный файл больше манифеста.")
        partialBytes == expected.sizeBytes -> Plan.FinalizeOnly
        else -> Plan.Resume(partialBytes)
    }

    sealed class RangeVerdict {
        object Append : RangeVerdict()
        data class Restart(val reason: String) : RangeVerdict()
        data class Fail(val reason: String) : RangeVerdict()
    }

    private val contentRangePattern = Regex("""^bytes (\d+)-(\d+)/(\d+|\*)$""")

    /** Verdict for the HTTP answer to a Range request from [offset]. */
    fun rangeVerdict(
        httpCode: Int,
        contentRange: String?,
        offset: Long,
        totalBytes: Long
    ): RangeVerdict = when (httpCode) {
        206 -> {
            val match = contentRange?.trim()?.let { contentRangePattern.matchEntire(it) }
            val start = match?.groupValues?.get(1)?.toLongOrNull()
            val total = match?.groupValues?.get(3)?.takeIf { it != "*" }?.toLongOrNull()
            when {
                match == null -> RangeVerdict.Fail("Сервер вернул 206 без корректного Content-Range.")
                start != offset -> RangeVerdict.Fail(
                    "Сервер продолжил с $start, ожидалось $offset."
                )
                total != null && total != totalBytes -> RangeVerdict.Fail(
                    "Полный размер по Content-Range не совпадает с манифестом."
                )
                else -> RangeVerdict.Append
            }
        }
        200 -> RangeVerdict.Restart("Сервер не поддерживает докачку — загрузка начнётся заново.")
        416 -> RangeVerdict.Restart("Сервер отверг диапазон — загрузка начнётся заново.")
        else -> RangeVerdict.Fail("Сервер загрузки вернул HTTP $httpCode.")
    }

    fun metaJson(meta: Meta): String = JSONObject()
        .put("url", meta.url)
        .put("sha256", meta.sha256)
        .put("sizeBytes", meta.sizeBytes)
        .toString()

    fun parseMeta(json: String?): Meta? {
        if (json.isNullOrBlank()) return null
        return try {
            val parsed = JSONObject(json)
            val url = parsed.optString("url")
            val sha256 = parsed.optString("sha256")
            val sizeBytes = parsed.optLong("sizeBytes", -1L)
            if (url.isBlank() || sha256.isBlank() || sizeBytes <= 0L) null
            else Meta(url, sha256, sizeBytes)
        } catch (_: Exception) {
            null
        }
    }
}
