package com.example.scraper.engine

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale

/** URL normalisation, content hashing and chapter-number heuristics shared by the scraper. */
object UrlIdentity {

    private val TRACKING_PARAMS = setOf(
        "fbclid", "gclid", "dclid", "msclkid", "igshid", "mc_cid", "mc_eid", "spm", "_ga", "yclid"
    )

    /**
     * Identity key for a page URL: lowercase scheme/host, no fragment, no default port, no trailing
     * slash, tracking parameters removed and the remaining query parameters sorted.
     * Query parameters are kept because many sites identify chapters with them (`read.php?id=12`).
     */
    fun normalize(raw: String?): String {
        val input = raw?.trim().orEmpty()
        if (input.isEmpty()) return ""
        return try {
            val uri = URI(input)
            val scheme = (uri.scheme ?: "https").lowercase(Locale.ROOT)
            val host = uri.host?.lowercase(Locale.ROOT)
            if (host.isNullOrEmpty()) return fallbackNormalize(input)
            val port = if (uri.port == -1 || uri.port == 80 || uri.port == 443) "" else ":${uri.port}"
            var path = uri.rawPath.orEmpty()
            while (path.length > 1 && path.endsWith("/")) path = path.dropLast(1)
            if (path == "/") path = ""
            val query = normalizeQuery(uri.rawQuery)
            buildString {
                append(scheme).append("://").append(host).append(port).append(path)
                if (query.isNotEmpty()) append('?').append(query)
            }
        } catch (_: Exception) {
            fallbackNormalize(input)
        }
    }

    private fun normalizeQuery(rawQuery: String?): String {
        if (rawQuery.isNullOrBlank()) return ""
        return rawQuery.split('&')
            .filter { it.isNotBlank() }
            .filterNot { param ->
                val name = param.substringBefore('=').lowercase(Locale.ROOT)
                name.startsWith("utm_") || name in TRACKING_PARAMS
            }
            .sorted()
            .joinToString("&")
    }

    private fun fallbackNormalize(input: String): String {
        val noFragment = input.substringBefore('#')
        val schemeEnd = noFragment.indexOf("://")
        val lowered = if (schemeEnd > 0) {
            val afterScheme = noFragment.substring(schemeEnd + 3)
            val hostEnd = afterScheme.indexOfFirst { it == '/' || it == '?' }.let { if (it < 0) afterScheme.length else it }
            noFragment.substring(0, schemeEnd).lowercase(Locale.ROOT) + "://" +
                afterScheme.substring(0, hostEnd).lowercase(Locale.ROOT) + afterScheme.substring(hostEnd)
        } else noFragment
        return lowered.trimEnd('/')
    }

    /** Host without `www.`/`m.` prefixes, used to group projects and cached selectors per site. */
    fun siteHost(raw: String?): String {
        val host = try {
            URI(raw?.trim().orEmpty()).host?.lowercase(Locale.ROOT)
        } catch (_: Exception) {
            null
        } ?: return "unknown"
        return host.removePrefix("www.").removePrefix("m.").ifBlank { "unknown" }
    }

    fun isSameSite(a: String?, b: String?): Boolean {
        val ha = siteHost(a)
        val hb = siteHost(b)
        if (ha == "unknown" || hb == "unknown") return true
        return ha == hb || ha.endsWith(".$hb") || hb.endsWith(".$ha")
    }

    fun contentHash(paragraphs: List<String>): String {
        val normalized = paragraphs.joinToString("\n") { it.replace(Regex("\\s+"), " ").trim() }.trim()
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Stable 32-hex-char id for a chapter of a project (same URL -> same id). */
    fun chapterId(projectId: String, urlKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$projectId|$urlKey".toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    private const val MAX_CHAPTER_NUMBER = 99_999.0

    private val TITLE_PATTERNS = listOf(
        Regex("(?i)\\b(?:chapter|chap|ch|episode|ep|part)\\s*[.:#-]?\\s*(\\d{1,5}(?:\\.\\d{1,2})?)\\b"),
        Regex("第\\s*(\\d{1,5})\\s*[章节回話话]"),
        Regex("^\\s*(\\d{1,5}(?:\\.\\d{1,2})?)\\s*[-:–—.、]")
    )
    private val CHINESE_TITLE_PATTERN = Regex("第\\s*([零〇一二两三四五六七八九十百千万]+)\\s*[章节回話话]")
    private val URL_PATTERNS = listOf(
        Regex("(?i)[/_-](?:chapter|chap|ch|c|episode|ep)[-_]?(\\d{1,5}(?:\\.\\d{1,2})?)(?=$|[/._?#-])"),
        Regex("(?i)/(\\d{1,5})(?:\\.html?)?/?(?=$|[?#])")
    )

    /** Chapter number from a title ("Chapter 12", "第12章", "第十二章", "12 - ...") or the URL. */
    fun chapterNumber(title: String?, url: String? = null): Double? {
        val t = title.orEmpty()
        for (pattern in TITLE_PATTERNS) {
            pattern.find(t)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                ?.takeIf { it in 0.0..MAX_CHAPTER_NUMBER }
                ?.let { return it }
        }
        CHINESE_TITLE_PATTERN.find(t)?.groupValues?.getOrNull(1)?.let { parseChineseNumber(it) }
            ?.takeIf { it in 1..MAX_CHAPTER_NUMBER.toInt() }
            ?.let { return it.toDouble() }
        val path = try {
            URLDecoder.decode(URI(url?.trim().orEmpty()).rawPath.orEmpty(), "UTF-8")
        } catch (_: Exception) {
            url.orEmpty().substringBefore('?')
        }
        for (pattern in URL_PATTERNS) {
            pattern.find(path)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                ?.takeIf { it in 0.0..MAX_CHAPTER_NUMBER }
                ?.let { return it }
        }
        return null
    }

    fun parseChineseNumber(text: String): Int? {
        val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        val units = mapOf('十' to 10, '百' to 100, '千' to 1000, '万' to 10_000)
        if (text.isEmpty()) return null
        var total = 0
        var section = 0
        var number = 0
        for (ch in text) {
            val d = digits[ch]
            if (d != null) {
                number = d
                continue
            }
            val unit = units[ch] ?: return null
            if (unit == 10_000) {
                section = (section + number) * unit
                total += section
                section = 0
            } else {
                section += (if (number == 0) 1 else number) * unit
            }
            number = 0
        }
        return total + section + number
    }

    /** Share of CJK ideographs among letters in [text]; used to tell untranslated text apart. */
    fun cjkRatio(text: String, sampleLength: Int = 1500): Double {
        var cjk = 0
        var letters = 0
        for (ch in text.take(sampleLength)) {
            if (isCjk(ch)) {
                cjk++
                letters++
            } else if (ch.isLetter()) {
                letters++
            }
        }
        return if (letters == 0) 0.0 else cjk.toDouble() / letters
    }

    fun isCjk(ch: Char): Boolean {
        val block = Character.UnicodeBlock.of(ch)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
    }
}
