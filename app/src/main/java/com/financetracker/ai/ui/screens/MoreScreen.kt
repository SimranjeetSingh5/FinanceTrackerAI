package com.financetracker.ai.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class MoreItem(val label: String, val subtitle: String, val icon: ImageVector, val route: String)

@Composable
fun MoreScreen(onNavigate: (String) -> Unit) {
    val items = listOf(
        MoreItem("Accounts", "Balances and net worth", Icons.Filled.AccountBalanceWallet, "accounts"),
        MoreItem("Recurring & bills", "Subscriptions and scheduled payments", Icons.Filled.Autorenew, "recurring"),
        MoreItem("Savings goals", "Track progress toward targets", Icons.Filled.Flag, "goals"),
        MoreItem("Ask your finances", "Chat with the on-device AI assistant", Icons.Filled.Chat, "chat"),
        MoreItem("AI model setup", "Manage the offline Gemma model", Icons.Filled.SmartToy, "setup"),
        MoreItem("Settings", "Currency, security, export", Icons.Filled.Settings, "settings")
    )

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item { Text("More", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { Spacer(Modifier.height(12.dp)) }
        items(items) { item ->
            ListItem(
                headlineContent = { Text(item.label) },
                supportingContent = { Text(item.subtitle) },
                leadingContent = { Icon(item.icon, contentDescription = null) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null, modifier = Modifier.size(16.dp)) },
                modifier = Modifier.clickable { onNavigate(item.route) }
            )
            HorizontalDivider()
        }
    }
}
