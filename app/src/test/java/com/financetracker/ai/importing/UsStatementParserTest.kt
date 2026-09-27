package com.financetracker.ai.importing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * US / international statement shapes. These differ from the Indian export in ways that broke
 * the parser: month-first numeric dates, a reference number and a running balance sharing the
 * row with the amount, and the merchant sitting on the date line itself.
 */
class UsStatementParserTest {

    @Test
    fun `reads a us row with trailing running balance`() {
        val text = """
            Date Description Amount Balance
            09/12/2026 NETSPORTS 1234 -45.99 1204.56
            09/13/2026 PAYROLL DEPOSIT 2500.00 3704.56
        """.trimIndent()

        val rows = StatementParser.parse(text)
        assertEquals(2, rows.size)
        // Rows come back newest-first.
        val payroll = rows.first { it.merchant.contains("PAYROLL", true) }
        val netsports = rows.first { it.merchant.contains("NETSPORTS", true) }

        // The trailing balance must not be mistaken for the amount.
        assertEquals(45.99, netsports.amount!!, 0.001)
        assertEquals(2500.00, payroll.amount!!, 0.001)
        assertTrue("payroll should be income", payroll.isIncome)
    }

    @Test
    fun `treats ambiguous numeric dates as us month-first`() {
        val millis = StatementParser.parse("09/12/2026\nAMAZON\n45.99").first().dateMillis
        assertNotNull(millis)
        val cal = Calendar.getInstance().apply { timeInMillis = millis!! }
        // Calendar months are zero-indexed, so September is 8 — not 11.
        assertEquals("September 12th in US order", Calendar.SEPTEMBER, cal.get(Calendar.MONTH))
        assertEquals(12, cal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `still accepts an unambiguous day-first date`() {
        // 25 cannot be a month, so this is day-first and must still resolve.
        val millis = StatementParser.parse("25/09/2026\nSHOP\n10.00").first().dateMillis
        assertNotNull(millis)
        val cal = Calendar.getInstance().apply { timeInMillis = millis!! }
        assertEquals(8, cal.get(Calendar.MONTH))
        assertEquals(25, cal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `detects a payroll deposit as income`() {
        val rows = StatementParser.parse("09/13/2026\nPAYROLL DEPOSIT\n2500.00")
        assertTrue("payroll should be income", rows.first().isIncome)
    }

    @Test
    fun `handles a dollar sign before the amount`() {
        val rows = StatementParser.parse("09/12/2026\nSTARBUCKS\n\$45.99")
        assertEquals(45.99, rows.first().amount!!, 0.001)
    }

    @Test
    fun `handles european decimal comma and thousands separator`() {
        val rows = StatementParser.parse("12.09.2026\nSUPERMARKT\n1.234,56")
        assertEquals(1, rows.size)
        assertEquals(1234.56, rows.first().amount!!, 0.001)
    }

    @Test
    fun `handles us thousands separator`() {
        val rows = StatementParser.parse("09/12/2026\nBIG PURCHASE\n1,234.56")
        assertEquals(1234.56, rows.first().amount!!, 0.001)
    }

    @Test
    fun `parses a compact mmddyy date used by some us apps`() {
        val millis = StatementParser.parse("091226\nCARD PURCHASE\n22.40").first().dateMillis
        assertNotNull("compact date should parse", millis)
        val cal = Calendar.getInstance().apply { timeInMillis = millis!! }
        assertEquals(8, cal.get(Calendar.MONTH))
        assertEquals(12, cal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `keeps a negative amount as a positive value with the sign recorded`() {
        val rows = StatementParser.parse("09/12/2026\nREFUND\n-20.00")
        assertEquals(20.0, rows.first().amount!!, 0.001)
    }
}
