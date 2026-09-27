package com.financetracker.ai.importing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cases taken from a real statement export, including the OCR damage that turned the rupee
 * symbol into "8", "생", "제", "₴", "{", "«", "*" and "?", and the row that read "Zb sep, 202b".
 */
class StatementParserTest {

    private val sample = """
        Date
        Payment Type
        Transaction Name
        Category
        Amount
        26 Sep, 2026
        Others
        Blinkit, Gurgoan
        Grocery
        Zb sep, 202b
        Card Transaction
        Jeetendrai
        Food and Drinks
        생105.00
        25 Sep, 2026
        Card Transaction
        Golden Bakery And Genral
        Food and Drinks
        제175.00
        25 Sep, 2026
        Card Transaction
        Vikas Pathak
        Grocery
        제130.00
        24 Sep, 2026 Card Transaction
        Vikas Pathak
        Grocery
        8130.00
        24 Sep, 2026 Card Transaction
        Pyu Zepto Marketplace, Bangalore
        Grocery
        8329.85
        23 Sep, 2026 Card Transaction
        7 Eleven Pune T2xk
        Food and Drinks
        22 Sep, 2026
        Card I ransacuon
        Mukesh
        Grocery
        22 Sep, 2026
        Card Transaction
        Dominos, Gurgaon
        Food and Drinks
        8478.80
        21 Sep, 2026 Card Transaction
        Amazon Pay India Privatet, Bang alore
        Shopping
        *1064.00
        21 Sep, 2026 Card Transaction
        Blinkit
        Grocery
        18.O0
    """.trimIndent()

    @Test
    fun `finds every dated row and ignores the header block`() {
        val results = StatementParser.parse(sample)
        // 11 dated rows in the sample; the 5-line header block must not become a row.
        assertEquals(11, results.size)
    }

    @Test
    fun `a mangled amount is not mistaken for a record header`() {
        // "Zb sep, 202b" is OCR damage on an amount cell, not a date. Treating it as a date
        // splits one transaction into two and loses the real merchant.
        val results = StatementParser.parse(sample)
        val blinkit = results.firstOrNull { it.merchant.contains("Blinkit", true) }
        assertNotNull("Blinkit row should survive the mangled line", blinkit)
    }

    @Test
    fun `reads the rupee-misread amounts`() {
        val results = StatementParser.parse(sample)
        val amounts = results.mapNotNull { it.amount }

        assertTrue("expected 105.00 from 생105.00", amounts.contains(105.00))
        assertTrue("expected 175.00 from 제175.00", amounts.contains(175.00))
        assertTrue("expected 130.00 from 제130.00", amounts.contains(130.00))
        assertTrue("expected 1064.00 from *1064.00", amounts.contains(1064.00))
        // 18.O0 has a letter O for zero and must still parse.
        assertTrue("expected 18.00 from 18.O0", amounts.contains(18.00))
    }

    @Test
    fun `flags the 8-prefixed amounts as suspect rather than trusting them`() {
        val results = StatementParser.parse(sample)
        val suspects = results.filter { it.rawAmount.startsWith("8") }

        assertTrue("8-prefixed rows should be flagged for review", suspects.isNotEmpty())
        assertTrue("all suspect rows need review", suspects.all { it.needsReview })
    }

    @Test
    fun `recovers the category column without needing the model`() {
        val results = StatementParser.parse(sample)
        val categories = results.mapNotNull { it.categoryName }

        assertTrue("expected Groceries", categories.contains("Groceries"))
        assertTrue("expected Food & Dining", categories.contains("Food & Dining"))
        assertTrue("expected Shopping", categories.contains("Shopping"))
    }

    @Test
    fun `rejects the mangled 'Zb sep, 202b' as a date`() {
        // It looks date-shaped but is really a corrupted rupee amount, so it must not start a
        // record.
        val results = StatementParser.parse(sample)
        assertTrue(
            "mangled line should not be treated as a date",
            results.none { it.rawDate.contains("Zb", ignoreCase = true) }
        )
    }

    @Test
    fun `marks rows with no amount for review`() {
        val results = StatementParser.parse(sample)
        val noAmount = results.filter { it.amount == null }

        assertTrue("7-Eleven and Tamilnadu have no amount", noAmount.isNotEmpty())
        assertTrue("rows without an amount must need review", noAmount.all { it.needsReview })
    }

    @Test
    fun `extracts merchants`() {
        val results = StatementParser.parse(sample)
        val merchants = results.map { it.merchant }

        assertTrue("expected Blinkit", merchants.any { it.contains("Blinkit", true) })
        assertTrue("expected Golden Bakery", merchants.any { it.contains("Golden Bakery", true) })
    }

    @Test
    fun `handles dd-mm-yyyy with ambiguous components`() {
        val results = StatementParser.parse("13/04/2026\nCard Transaction\nTEST SHOP\nFood and Drinks\n130.00")
        assertNotNull(results.firstOrNull()?.dateMillis)
        assertEquals(1, results.size)
    }

    @Test
    fun `handles iso dates`() {
        val results = StatementParser.parse("2026-04-13\nUPI\nAMAZON\nShopping\n129.99")
        assertEquals(1, results.size)
        assertNotNull(results.first().dateMillis)
        assertEquals(129.99, results.first().amount!!, 0.001)
    }

    @Test
    fun `returns nothing for text with no transactions`() {
        assertTrue(StatementParser.parse("Page 1 of 3\nAccount Number 1234\nStatement").isEmpty())
    }

    @Test
    fun `detects income rows`() {
        val results = StatementParser.parse(
            "26 Sep, 2026\nOthers\nSALARY CREDIT\nIncome\n50000.00"
        )
        assertTrue(results.first().isIncome)
    }

    @Test
    fun `fingerprints are stable so re-import is a no-op`() {
        val first = StatementParser.parse(sample)
        val second = StatementParser.parse(sample)
        assertEquals(first.map { it.fingerprint }, second.map { it.fingerprint })
    }
}
