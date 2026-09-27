package com.financetracker.ai.importing

import java.util.Calendar

/**
 * Date recognition for statement text, across the formats banks actually emit.
 *
 * Split out of [StatementParser] because it is the most format-dependent part of the import
 * and the one most likely to need per-bank adjustments later.
 */
internal object DateReader {

    private val MONTHS = listOf(
        0 to listOf("jan", "january"), 1 to listOf("feb", "february"),
        2 to listOf("mar", "march"), 3 to listOf("apr", "april"),
        4 to listOf("may"), 5 to listOf("jun", "june"),
        6 to listOf("jul", "july"), 7 to listOf("aug", "august"),
        8 to listOf("sep", "sept", "september"), 9 to listOf("oct", "october"),
        10 to listOf("nov", "november"), 11 to listOf("dec", "december")
    )

    /** True when the line is shaped like a date, whether or not it resolves to one. */
    fun looksLikeDate(line: String): Boolean {
        val lower = line.trim().lowercase()
        // A record header begins with its date. Rejecting lines that start with a letter is
        // what stops a mangled amount ("Zb sep, 202b") from being read as a header.
        if (lower.firstOrNull()?.isLetter() == true) return false
        val monthNamed = Regex("^\\d{1,2}\\s*[a-z]{3,}").containsMatchIn(lower)
        val separated = Regex("^\\d{1,4}\\s*[/\\-.]\\s*\\d{1,4}").containsMatchIn(lower)
        val compact = Regex("^\\d{6}$").containsMatchIn(lower)
        return monthNamed || separated || compact
    }

    /**
     * Parses a date from [line], or null. Tolerates the letter-for-digit damage OCR
     * introduces ("O" for 0, "l" for 1).
     *
     * Where a numeric date is genuinely ambiguous — both parts 12 or lower — the month-first
     * reading wins, matching the majority of statements that use slashes. A first part above
     * 12 can only be a day, so that case is unambiguous.
     */
    fun parse(line: String, now: Long = System.currentTimeMillis()): Long? {
        if (!looksLikeDate(line)) return null

        val cleaned = line.replace("O", "0").replace("o", "0").replace("l", "1")
        val lower = cleaned.lowercase()

        // "26 Sep, 2026" / "25 September 2026"
        MONTHS.forEach { (index, names) ->
            names.forEach { name ->
                val pattern = Regex(
                    "(\\d{1,2})\\s*[\\-/.]?\\s*$name[\\s,\\-./]*(\\d{4})",
                    RegexOption.IGNORE_CASE
                )
                val m = pattern.find(lower) ?: return@forEach
                val day = m.groupValues[1].toIntOrNull() ?: return@forEach
                val year = m.groupValues[2].toIntOrNull() ?: return@forEach
                toMillis(year, index, day, now)?.let { return it }
            }
        }

        // "13/04/2026", "04/13/2026", "13-04-26", "12.09.2026"
        Regex("(\\d{1,2})[/\\-.](\\d{1,2})[/\\-.](\\d{2,4})").find(cleaned)?.let { m ->
            val a = m.groupValues[1].toIntOrNull()
            val b = m.groupValues[2].toIntOrNull()
            var year = m.groupValues[3].toIntOrNull()
            if (a != null && b != null && year != null && a in 1..31 && b in 1..31) {
                if (year < 100) year += 2000
                val (month, day) = if (a > 12) b to a else a to b
                toMillis(year, month - 1, day, now)?.let { return it }
            }
        }

        // "091226" — compact month-first, used by several US banking apps.
        Regex("^(\\d{2})(\\d{2})(\\d{2,4})$").find(cleaned.trim())?.let { m ->
            val month = m.groupValues[1].toIntOrNull()
            val day = m.groupValues[2].toIntOrNull()
            var year = m.groupValues[3].toIntOrNull()
            if (month != null && day != null && year != null) {
                if (year < 100) year += 2000
                toMillis(year, month - 1, day, now)?.let { return it }
            }
        }

        // "2026-04-13" (ISO)
        Regex("(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})").find(cleaned)?.let { m ->
            val y = m.groupValues[1].toIntOrNull()
            val mo = m.groupValues[2].toIntOrNull()
            val d = m.groupValues[3].toIntOrNull()
            if (y != null && mo != null && d != null) {
                toMillis(y, mo - 1, d, now)?.let { return it }
            }
        }

        return null
    }

    private fun toMillis(year: Int, month: Int, day: Int, now: Long): Long? {
        if (month !in 0..11 || day !in 1..31) return null
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // Reject impossible dates like 31 Feb, which Calendar would roll over silently.
        if (cal.get(Calendar.DAY_OF_MONTH) != day) return null
        return cal.timeInMillis
    }
}
