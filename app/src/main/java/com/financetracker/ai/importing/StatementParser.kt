package com.financetracker.ai.importing

import java.util.Calendar
import java.util.Locale

/**
 * One row recovered from a statement, before the user confirms it.
 *
 * [needsReview] marks anything the parser is not confident about. Those rows are shown
 * highlighted and must be corrected before saving — an import that silently guesses is worse
 * than one that asks.
 */
data class ParsedTransaction(
    val rawText: String,
    val dateMillis: Long?,
    val rawDate: String,
    val merchant: String,
    val amount: Double?,
    val rawAmount: String,
    val categoryName: String?,
    val paymentType: String?,
    val isIncome: Boolean,
    val confidence: Confidence,
    val needsReview: Boolean,
    val fingerprint: String,
    val isDuplicate: Boolean = false
) {
    enum class Confidence { HIGH, MEDIUM, LOW }
}

/**
 * Best-effort parser for bank / payments-app statement text.
 *
 * Deliberately conservative: a row it cannot read confidently is flagged rather than guessed,
 * because a wrong amount or date silently corrupts the user's budget. Every recovery is local
 * and deterministic — no network, no model.
 *
 * The parser handles the shape most statements share — a date, then fields that follow it —
 * rather than any one bank's layout. Multi-column OCR output is common, so fields are matched by
 * content (does this look like a date? a money value? a known category?) instead of by column
 * position.
 */
object StatementParser {

    /**
     * Currency symbols and OCR debris that may sit next to a number. Deliberately
     * symbol-agnostic: statements come in any currency, and OCR renders a mangled symbol as
     * anything from '?' to a Hangul syllable. Anything non-numeric before the digits is
     * discarded when reading an amount, so this is only used for diagnostics.
     */
    private val currencyNoise = setOf(
        '₹', '₨', '₩', '₪', '₦', '₱', '₴', '₡', '₫', '₭', '฿', '₺', '₽', '₿',
        '$', '€', '£', '¥', '₤', '¢', 'R', 'C', 'H', 'F', 'k',
        '?', '{', '«', '*', '~', '^', '|', ':', ';', '·', '°'
    )

    /** Category names as they appear in common statement exports, mapped to app categories. */
    private val categoryAliases = mapOf(
        "grocery" to "Groceries", "groceries" to "Groceries", "supermarket" to "Groceries",
        "food and drinks" to "Food & Dining", "food & drinks" to "Food & Dining",
        "food" to "Food & Dining", "dining" to "Food & Dining", "restaurant" to "Food & Dining",
        "shopping" to "Shopping", "retail" to "Shopping", "apparel" to "Shopping",
        "transport" to "Transport", "travel" to "Transport", "fuel" to "Transport",
        "housing" to "Housing", "rent" to "Housing",
        "utilities" to "Utilities", "utility" to "Utilities", "electricity" to "Utilities",
        "entertainment" to "Entertainment", "health" to "Health", "medical" to "Health",
        "subscription" to "Subscriptions", "income" to "Income", "salary" to "Income",
        "others" to "Other", "other" to "Other", "misc" to "Other"
    )

    /**
     * Lines that are header/footer furniture rather than transactions.
     *
     * Matched as whole words against a known set of column labels. Substring matching is unsafe
     * here — "upi" is contained in merchant names like "Supplied" and "card transaction" in
     * "Card Factory" — so anything that isn't an exact label is left alone.
     */
    private val noiseLabels = setOf(
        "date", "payment type", "transaction name", "category", "amount", "balance",
        "transaction date", "particulars", "description", "debit", "credit", "withdrawal",
        "deposit", "page", "statement", "account number", "account no", "generated on",
        "opening balance", "closing balance", "total", "total transactions"
    )

    private fun isNoise(line: String): Boolean {
        val normalized = line.trim().lowercase()
            .replace(Regex("[^a-z0-9 ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        return normalized in noiseLabels
    }

    /**
     * Payment-type values as they appear in exports. "Others" is deliberately absent: statements
     * use it as a *category* (Blinkit → "Others"), and treating it as a payment type would
     * steal that cell and leave the row uncategorised.
     */
    private val paymentTypeHints = listOf(
        "card transaction", "upi", "neft", "imps", "cash withdrawal", "auto debit", "net banking"
    )

    /**
     * A line that is a payment-type label. OCR mangles these badly ("Card I ransacuon" for
     * "Card Transaction", "Cash widthrawal"), so we match on the leading keyword rather than
     * the whole phrase, and keep the line short enough to be a label rather than a company.
     */
    private fun isPaymentTypeLine(line: String): Boolean {
        val words = line.trim().lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 4) return false
        return paymentTypeHints.any { hint ->
            val head = hint.split(" ")[0]
            words.first() == head || words.first().startsWith(head)
        }
    }

    fun parse(rawText: String, now: Long = System.currentTimeMillis()): List<ParsedTransaction> {
        val lines = rawText.lines()
            .map { it.replace(' ', ' ').trim() }
            .filter { it.isNotBlank() }

        if (lines.isEmpty()) return emptyList()

        val transactions = mutableListOf<ParsedTransaction>()
        var current = mutableListOf<String>()

        for (line in lines) {
            if (isNoise(line)) continue

            val dateMillis = parseDate(line, now)
            if (dateMillis != null) {
                // A new date starts a new record; flush what we've accumulated.
                if (current.isNotEmpty()) {
                    parseRecord(current, now)?.let(transactions::add)
                }
                current = mutableListOf(line)
                continue
            }

            if (current.isEmpty()) {
                // Preamble before the first date — column headers, bank branding.
                continue
            }

            // Rows run date + payment type + merchant + category + amount. Once the amount has
            // been seen, the next bare line starts a *different* row whose date was lost in
            // OCR — which is what a mangled amount cell ("Zb sep, 202b") causes: it is
            // unreadable as a date, so the parser keeps appending to the previous row and the
            // following transaction disappears into it. Close the record, carry the date
            // forward, and keep the merchant.
            //
            // The date line is excluded from the amount check because "13/04/2026" contains
            // digits and would make every row look complete the moment it starts.
            val alreadyHasAmount = current.drop(1).any { line ->
                parseAmount(line) != null || looksLikeMangledAmount(line)
            }
            val looksLikeField = line.length > 3 &&
                    !isPaymentTypeLine(line) &&
                    categoryAliases[line.trim().lowercase()] == null
            // A payment type belongs to the row being built, not a new one.
            val startsNewRecord = alreadyHasAmount && looksLikeField

            if (startsNewRecord) {
                parseRecord(current, now)?.let(transactions::add)
                current = mutableListOf(current.first(), line)
            } else {
                current.add(line)
            }
        }
        if (current.isNotEmpty()) {
            parseRecord(current, now)?.let(transactions::add)
        }

        return transactions.sortedByDescending { it.dateMillis ?: 0L }
    }

    private fun parseRecord(lines: List<String>, now: Long): ParsedTransaction? {
        val rawText = lines.joinToString(" | ")
        val dateMillis = lines.firstNotNullOfOrNull { parseDate(it, now) }
        val rawDate = lines.firstOrNull { parseDate(it, now) != null }.orEmpty()

        // A US row often puts the merchant, a reference number and the amount all on the date
        // line ("09/12/2026 NETSPORTS 1234 -45.99 1204.56"), so the date line has to be mined
        // for fields too — not just the lines that follow it.
        val dateLine = lines.firstOrNull { parseDate(it, now) != null }.orEmpty()
        val dateLineWithoutDate = dateLine
            .replace(
                Regex("\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}"),
                " "
            )
            .replace(Regex("\\d{1,2}\\s*[a-z]{3,}\\s*,?\\s*\\d{4}", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\d{4}-\\d{1,2}-\\d{1,2}"), " ")
            .trim()
        // The remaining words on the date line are the merchant.
        val merchantFromDateLine = dateLineWithoutDate
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && parseAmount(it) == null }
            .joinToString(" ")
            .trim()
            .takeIf { it.length in 2..60 }

        // Fields are pulled out in reverse order of position. The amount is the last value in
        // a row, the payment type and category are bare labels near the start, and whatever is
        // left over is the merchant. Working backwards means an earlier match can't consume a
        // line that a later, more specific rule needed.
        // The amount can sit on the date line (US rows) or on its own line (column exports).
        // Prefer a line that holds *only* a value, since a US row's date line also carries a
        // running balance that would otherwise win the "last" position.
        val amountLine = lines.lastOrNull { line ->
            parseAmount(line) != null && line != dateLine
        } ?: lines.lastOrNull { parseAmount(it) != null }
        val amountResult = amountLine?.let { parseAmount(it) }

        val paymentType = lines.drop(1)
            .firstOrNull { l ->
                l !== amountLine && parseAmount(l) == null && isPaymentTypeLine(l)
            }?.trim()

        val category = lines.drop(1)
            .firstOrNull { l ->
                l !== amountLine && l !== paymentType &&
                        parseAmount(l) == null &&
                        categoryAliases.containsKey(l.trim().lowercase())
            }
            ?.let { categoryAliases[it.trim().lowercase()] }

        val merchant = lines
            .drop(1) // the date line is handled separately below
            .filter { it !== paymentType }
            .filter { it !== amountLine }
            // Drop the cell the category came from, whatever it read as.
            .filterNot { it !== null && categoryAliases.containsKey(it.trim().lowercase()) }
            .map { cleanMerchant(it) }
            .firstOrNull { it.isNotBlank() && it.length > 1 }
            // Fall back to the words sharing the date line, which is where a US row puts them.
            ?: merchantFromDateLine
            .orEmpty()

        // --- Confidence assessment -------------------------------------------------
        val problems = mutableListOf<String>()

        if (dateMillis == null) problems += "unreadable date"
        if (amountResult?.value == null) problems += "no amount found"
        if (merchant.isBlank()) problems += "no merchant"
        if (amountResult?.suspectPrefix == true) {
            problems += "amount may be misread (symbol detected as a digit)"
        }

        val confidence = when {
            problems.size >= 2 -> ParsedTransaction.Confidence.LOW
            problems.size == 1 -> ParsedTransaction.Confidence.MEDIUM
            else -> ParsedTransaction.Confidence.HIGH
        }

        return ParsedTransaction(
            rawText = rawText,
            dateMillis = dateMillis,
            rawDate = rawDate,
            merchant = merchant.ifBlank { "Unknown merchant" },
            amount = amountResult?.value,
            rawAmount = amountLine?.trim().orEmpty(),
            categoryName = category,
            paymentType = paymentType,
            isIncome = detectIncome(lines, amountResult?.value),
            confidence = confidence,
            needsReview = problems.isNotEmpty(),
            fingerprint = fingerprint(dateMillis, amountResult?.value, merchant)
        )
    }

    /**
     * Reads a money value from a line, tolerating the junk OCR puts around it.
     *
     * Returns [suspectPrefix] when the character immediately before the digits is one OCR
     * commonly produces for a currency symbol — in those cases the leading digit may actually
     * be part of the mangled symbol, so the row is flagged for review.
     */
    /**
     * Reads a money value from a line, tolerating the junk OCR puts around it.
     *
     * US statements commonly put several numbers on one row — a reference number, the amount,
     * and a running balance — so we prefer the value that is formatted like currency (has a
     * decimal part) and fall back to the first bare number only if there is no such value.
     */
    private fun parseAmount(line: String): AmountResult? {
        val trimmed = line.trim()

        // A line that is purely words is never an amount. Testing that first stops "Others"
        // becoming "0th3r5" and every merchant line looking like it carries a value, which
        // silently swallowed the category column.
        //
        // A US row puts merchant, reference and amount on one line, so we can't reject on the
        // mere presence of letters — "NETSPORTS 1234 -45.99" has plenty. Instead we require at
        // least one decimal value to be present, which separates a real amount from a reference
        // number or a merchant name.
        if (trimmed.none { it.isDigit() }) return null
        if (trimmed.all { it.isLetter() || it.isWhitespace() || it in "-.,()#" }) return null

        val cleaned = normalizeNumeric(trimmed)

        val all = Regex("([0-9]+(?:\\.[0-9]{1,2})?)").findAll(cleaned).toList()
        if (all.isEmpty()) return null

        // Prefer a decimal value — that's the amount rather than a reference number or a
        // whole-dollar balance. The first decimal is the amount; anything after it is a
        // running balance.
        //
        // With no decimal anywhere, only accept the number if the line looks like a bare
        // amount rather than text that happens to contain digits: "7 Eleven Pune T2xk" and
        // "Store #405" must not be read as transactions.
        val chosen = all.firstOrNull { it.value.contains('.') }
            ?: all.firstOrNull { candidate ->
                val onlyValue = trimmed.replace(candidate.value, "").trim()
                onlyValue.isEmpty() || onlyValue.all { it in "$ -+#()" }
            }
            ?: return null
        val value = chosen.value.toDoubleOrNull() ?: return null

        // A rupee sign is the single most mis-recognised glyph in these scans, and "8" is its
        // most frequent misreading — so a value that begins with 8 is far more likely to be
        // "130.00" with a mangled symbol than a genuine 8130. We can't tell for certain, so we
        // keep the value but mark the row for review rather than silently importing it.
        val suspectPrefix = chosen.range.first == 0 &&
                cleaned.startsWith("8") &&
                chosen.value.length > 3 &&
                cleaned.indexOf('.') > 0

        return AmountResult(value, suspectPrefix = suspectPrefix)
    }

    private data class AmountResult(val value: Double?, val suspectPrefix: Boolean)

    /**
     * Normalises a numeric cell to plain `1234.56`.
     *
     * Handles the letter-for-digit confusions OCR makes ("18.O0") and both separator
     * conventions: "1,234.56" and "1.234,56" become the same number, so a European statement
     * isn't silently read as 1.23 or 1234.56. The last separator is taken as the decimal mark
     * when it is followed by exactly two digits, which is the standard disambiguation.
     */
    private fun normalizeNumeric(raw: String): String {
        var text = raw.replace("O", "0").replace("o", "0")
            .replace("l", "1").replace("I", "1")
            .replace(" ", "")

        val lastComma = text.lastIndexOf(',')
        val lastDot = text.lastIndexOf('.')

        if (lastComma >= 0 && lastDot >= 0) {
            // Whichever comes last is the decimal mark.
            if (lastComma > lastDot) {
                text = text.replace(".", "").replace(',', '.')
            } else {
                text = text.replace(",", "")
            }
        } else if (lastComma >= 0) {
            // A single comma is a decimal mark only when two digits follow it; otherwise it
            // groups thousands ("1,234").
            val decimals = text.length - lastComma - 1
            text = if (decimals == 2) text.replace(',', '.') else text.replace(",", "")
        }

        return text
    }

    /**
     * True for an amount cell so mangled that no digits survive, but which still looks like a
     * value — "Zb sep, 202b" is OCR damage on a rupee amount. These still terminate their row,
     * so the parser must treat them as the amount rather than carrying on and absorbing the
     * following transaction.
     */
    private fun looksLikeMangledAmount(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || parseDateOrNull(trimmed) == null) return false
        // Date-shaped but already rejected as a real header, and not a plain amount.
        return parseAmount(trimmed) == null
    }

    /** Wrapper so [looksLikeMangledAmount] can test date-shape without the rejection guard. */
    private fun parseDateOrNull(line: String): Long? {
        val lower = line.lowercase()
        val monthNamed = Regex("^\\d{1,2}\\s*[a-z]{3,}").containsMatchIn(lower)
        val numeric = Regex("^\\d{1,4}\\s*[/\\-.]\\s*\\d{1,4}").containsMatchIn(lower)
        return if (monthNamed || numeric) System.currentTimeMillis() else null
    }

    /**
     * Whether a row is money coming in.
     *
     * US statements usually separate debits and credits into their own columns, or mark
     * credits with a leading '+', so both the wording and the sign are checked.
     */
    private fun detectIncome(lines: List<String>, amount: Double?): Boolean {
        val joined = lines.joinToString(" ").lowercase()
        val byWording = listOf(
            "credit", "salary", "refund", "cashback", "reversal", "deposit",
            "interest paid", "direct deposit", "payment received", "dividend"
        ).any { joined.contains(it) }
        if (byWording) return true
        // A signed amount in a debit/credit pair: positive is a credit, negative a debit.
        return amount != null && amount > 0 && joined.contains("debit") && !joined.contains("credit")
    }

    private fun cleanMerchant(value: String): String =
        value.replace(Regex("\\s+"), " ").trim(' ', ',', ';', '|')

    /**
     * Stable id for duplicate detection: same day, same amount, same merchant. Deliberately
     * coarse — a user importing the same statement twice should import nothing the second time.
     */
    private fun fingerprint(dateMillis: Long?, amount: Double?, merchant: String): String {
        val day = dateMillis?.let {
            val cal = Calendar.getInstance().apply { timeInMillis = it }
            "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}-${cal.get(Calendar.DAY_OF_MONTH)}"
        } ?: "?"
        val amt = amount?.let { String.format(Locale.US, "%.2f", it) } ?: "?"
        val who = merchant.lowercase().replace(Regex("[^a-z0-9]"), "").take(24)
        return "$day|$amt|$who"
    }

    /**
     * Recognises the date formats seen in Indian statements and exports, tolerating the OCR
     * damage that turns "0" into "O" and "1" into "l".
     */
    private fun parseDate(line: String, now: Long): Long? {
        val cleaned = line.replace("O", "0").replace("o", "0").replace("l", "1")
        val lower = cleaned.lowercase()

        // A record header always *begins* with the date. Rejecting lines that start with a
        // letter is what stops a mangled amount like "Zb sep, 202b" — OCR damage on a rupee
        // symbol — from splitting one transaction into two. Numeric formats (13/04/2026,
        // 2026-04-13) start with a digit and are unaffected.
        if (lower.firstOrNull()?.isLetter() == true) return null

        // The line must look date-shaped: a month name, digits with a separator, or a compact
        // run of digits. "Vikas Pathak" and "Blinkit" are not dates.
        val monthNamed = Regex("^\\d{1,2}\\s*[a-z]{3,}").containsMatchIn(lower)
        val separated = Regex("^\\d{1,4}\\s*[/\\-.]\\s*\\d{1,4}").containsMatchIn(lower)
        val compact = Regex("^\\d{6}$").containsMatchIn(lower)
        if (!monthNamed && !separated && !compact) return null

        // "26 Sep, 2026" / "26 Sep. 2026" / "25 September 2026"
        MONTHS.forEach { (index, names) ->
            names.forEach { name ->
                val pattern = Regex("(\\d{1,2})\\s*[\\-/.]?\\s*$name[\\s,\\-./]*(\\d{4})", RegexOption.IGNORE_CASE)
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
            if (a != null && b != null && year != null) {
                if (year < 100) year += 2000
                if (a in 1..31 && b in 1..31) {
                    // Both readings are valid when both parts are <= 12, so the order is
                    // genuinely ambiguous. Statements that reach here in that state are
                    // overwhelmingly month-first (US convention); a first part above 12 can
                    // only be a day, so that case resolves to day-first unambiguously.
                    val (month, day) = if (a > 12) b to a else a to b
                    toMillis(year, month - 1, day, now)?.let { return it }
                }
            }
        }

        // "091226" / "09122026" — compact month-first, used by several US banking apps.
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

    private val MONTHS = listOf(
        0 to listOf("jan", "january"), 1 to listOf("feb", "february"),
        2 to listOf("mar", "march"), 3 to listOf("apr", "april"),
        4 to listOf("may"), 5 to listOf("jun", "june"),
        6 to listOf("jul", "july"), 7 to listOf("aug", "august"),
        8 to listOf("sep", "sept", "september"), 9 to listOf("oct", "october"),
        10 to listOf("nov", "november"), 11 to listOf("dec", "december")
    )

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
