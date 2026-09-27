package com.financetracker.ai.importing

import org.junit.Test

/** Temporary diagnostic. */
class DumpTest {
    @Test
    fun dump() {
        listOf(
            "09/12/2026\nAMAZON\n45.99",
            "09/12/2026\nSTARBUCKS\n\$45.99",
            "25/09/2026\nSHOP\n10.00",
            "12.09.2026\nSUPERMARKT\n1.234,56",
            "091226\nCARD PURCHASE\n22.40",
            "09/12/2026\nREFUND\n-20.00"
        ).forEach { t ->
            val rows = StatementParser.parse(t)
            println("IN='${t.replace("\n", "|")}' COUNT=${rows.size} " +
                    rows.joinToString { "m='${it.merchant}' a=${it.amount} d=${it.dateMillis != null}" })
        }
    }
}
