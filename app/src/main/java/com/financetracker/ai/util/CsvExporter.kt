package com.financetracker.ai.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.financetracker.ai.data.Account
import com.financetracker.ai.data.Category
import com.financetracker.ai.data.Transaction
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object CsvExporter {

    fun export(
        context: Context,
        transactions: List<Transaction>,
        categories: List<Category>,
        accounts: List<Account>
    ): Intent {
        val categoryMap = categories.associateBy { it.id }
        val accountMap = accounts.associateBy { it.id }
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "transactions_${System.currentTimeMillis()}.csv")

        file.bufferedWriter().use { writer ->
            writer.write("Date,Type,Amount,Category,Account,Merchant,Note,AI Categorized\n")
            transactions.forEach { t ->
                val row = listOf(
                    df.format(Date(t.timestamp)),
                    t.type.name,
                    t.amount.toString(),
                    categoryMap[t.categoryId]?.name ?: "Uncategorized",
                    accountMap[t.accountId]?.name ?: "",
                    t.merchant ?: "",
                    csvEscape(t.note),
                    if (t.aiCategorized) "Yes" else "No"
                ).joinToString(",")
                writer.write(row)
                writer.write("\n")
            }
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun csvEscape(value: String): String =
        if (value.contains(",") || value.contains("\"")) "\"${value.replace("\"", "\"\"")}\"" else value
}
