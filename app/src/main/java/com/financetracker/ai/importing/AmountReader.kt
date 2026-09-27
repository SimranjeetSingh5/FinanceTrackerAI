package com.financetracker.ai.importing

/**
 * Money-value recognition for statement text, across currency and separator conventions.
 *
 * Split out of [StatementParser] because it carries the most subtle rules in the import — the
 * ones that decide whether a row is trusted or flagged for review.
 */
internal object AmountReader {

    /**
     * A value that was read, plus whether it should be shown to the user before saving.
     *
     * [suspect] means the leading digit may actually be a misread currency symbol rather than
     * part of the number. Those are surfaced for review instead of imported silently.
     */
    data class Result(val value: Double?, val suspect: Boolean = false)

    /**
     * Reads a money value from a line, or null if the line holds no amount.
     *
     * Handles:
     *  - both separator conventions ("1,234.56" and "1.234,56" are the same number),
     *  - the letter-for-digit damage OCR produces ("18.O0"),
     *  - a leading sign, and
     *  - a US row carrying a reference number and a running balance alongside the amount.
     */
    fun parse(line: String): Result? {
        val trimmed = line.trim()

        // A line that is purely words is never an amount. Testing that first stops "Others"
        // becoming "0th3r5" and every merchant line looking like it carries a value, which
        // silently swallowed the category column.
        if (trimmed.none { it.isDigit() }) return null
        if (trimmed.all { it.isLetter() || it.isWhitespace() || it in "-.,()#/" }) return null

        val cleaned = normalize(trimmed)
        val numbers = Regex("([0-9]+(?:\\.[0-9]{1,2})?)").findAll(cleaned).toList()
        if (numbers.isEmpty()) return null

        // Prefer a decimal value — that is the amount rather than a reference number or a
        // whole-dollar balance. The first decimal wins; anything after it is a balance.
        //
        // With no decimal anywhere, only accept a number if the line looks like a bare amount
        // rather than text that merely contains digits: "7 Eleven Pune T2xk" and "Store #405"
        // must not be read as transactions.
        val chosen = numbers.firstOrNull { it.value.contains('.') }
            ?: numbers.firstOrNull { candidate ->
                val rest = trimmed.replace(candidate.value, "").trim()
                rest.isEmpty() || rest.all { it in "$ -+#()" }
            }
            ?: return null

        val value = chosen.value.toDoubleOrNull() ?: return null

        // A rupee sign is the most mis-recognised glyph in these scans and "8" is its most
        // frequent misreading, so a value starting with 8 is more likely "130.00" with a
        // mangled symbol than a genuine 8130. We can't be certain, so it is flagged, not
        // silently trusted.
        val suspect = chosen.range.first == 0 &&
                cleaned.startsWith("8") &&
                chosen.value.length > 3 &&
                cleaned.indexOf('.') > 0

        return Result(value, suspect)
    }

    /** Normalises a numeric cell to plain `1234.56`. */
    private fun normalize(raw: String): String {
        var text = raw.replace("O", "0").replace("o", "0")
            .replace("l", "1").replace("I", "1")
            .replace(" ", "")

        val lastComma = text.lastIndexOf(',')
        val lastDot = text.lastIndexOf('.')

        if (lastComma >= 0 && lastDot >= 0) {
            // Whichever comes last is the decimal mark.
            text = if (lastComma > lastDot) {
                text.replace(".", "").replace(',', '.')
            } else {
                text.replace(",", "")
            }
        } else if (lastComma >= 0) {
            // A lone comma is a decimal mark only when exactly two digits follow; otherwise
            // it groups thousands ("1,234").
            text = if (text.length - lastComma - 1 == 2) {
                text.replace(',', '.')
            } else {
                text.replace(",", "")
            }
        }
        return text
    }
}
