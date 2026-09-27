package com.financetracker.ai.importing

import com.financetracker.ai.data.Transaction
import com.financetracker.ai.data.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Duplicate detection matters most for image and PDF import, where the same statement gets
 * photographed twice or re-uploaded. Rows are matched on day + amount + a merchant prefix, so
 * OCR noise in the name doesn't hide a genuine repeat.
 */
class DuplicateDetectionTest {

    @Test
    fun `flags a row repeated within the same statement`() {
        val rows = StatementParser.markDuplicates(
            StatementParser.parse(
                """
                09/12/2026 NETSPORTS 1234 45.99
                09/13/2026 STARBUCKS 8891 12.50
                09/13/2026 STARBUCKS 8891 12.50
                """.trimIndent()
            )
        )
        val starbucks = rows.filter { it.merchant.contains("STARBUCKS", true) }

        assertEquals(2, starbucks.size)
        // The first occurrence stands; only the repeat is flagged.
        assertFalse("first occurrence should not be flagged", starbucks[0].isDuplicate)
        assertTrue("repeat should be flagged", starbucks[1].isDuplicate)
        assertEquals(
            ParsedTransaction.DuplicateReason.REPEATED_IN_FILE,
            starbucks[1].duplicateOf
        )
    }

    @Test
    fun `flags a transaction already present in the app`() {
        val parsed = StatementParser.parse("09/12/2026 NETSPORTS 1234 45.99")
        assertEquals(
            "fixture should parse to one row, got " + parsed.map { it.rawText },
            1, parsed.size
        )
        val existing = StatementParser.existingKeysFor(
            listOf(
                Transaction(
                    amount = 45.99,
                    note = "NETSPORTS 1234",
                    categoryId = 1,
                    type = TransactionType.EXPENSE,
                    timestamp = parsed.first().dateMillis!!
                )
            )
        )
        val rows = StatementParser.markDuplicates(parsed, existing)

        assertTrue(rows.first().isDuplicate)
        assertEquals(
            ParsedTransaction.DuplicateReason.ALREADY_IMPORTED,
            rows.first().duplicateOf
        )
    }

    @Test
    fun `tolerates OCR noise in the merchant name`() {
        // Same purchase, recognised two different ways across two photos. A US row keeps the
        // merchant on the date line, so each row is a single line here.
        val parsed = StatementParser.parse(
            "09/12/2026 STARBUCKS #4471 45.99\n09/12/2026 STARBUKS 4471 45.99"
        )
        assertEquals(
            "fixture should parse to two rows, got " + parsed.map { it.rawText },
            2, parsed.size
        )
        val rows = StatementParser.markDuplicates(parsed)
        assertTrue("OCR variant should still match", rows[1].isDuplicate)
    }

    @Test
    fun `does not flag genuinely different transactions`() {
        val rows = StatementParser.markDuplicates(
            StatementParser.parse(
                "09/12/2026\nSTARBUCKS\n45.99\n09/12/2026\nPETSMART\n45.99"
            )
        )
        assertFalse(rows[0].isDuplicate)
        assertFalse(rows[1].isDuplicate)
    }

    @Test
    fun `does not flag the same amount on a different day`() {
        val rows = StatementParser.markDuplicates(
            StatementParser.parse("09/12/2026\nCOFFEE\n4.50\n09/13/2026\nCOFFEE\n4.50")
        )
        assertFalse(rows[0].isDuplicate)
        assertFalse(rows[1].isDuplicate)
    }

    @Test
    fun `an unreadable row never collapses with anything`() {
        // No amount means no key, so a row that couldn't be read can't be called a duplicate
        // of an otherwise identical one.
        val rows = StatementParser.markDuplicates(
            StatementParser.parse("09/12/2026\nSOMETHING\n09/12/2026\nSOMETHING\n")
        )
        assertTrue("both rows should parse", rows.size >= 2)
        assertTrue("unreadable rows must not be flagged", rows.none { it.isDuplicate })
    }
}
