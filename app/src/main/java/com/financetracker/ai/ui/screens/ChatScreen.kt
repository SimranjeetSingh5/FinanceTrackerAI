package com.financetracker.ai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.financetracker.ai.ui.components.ScreenHeader
import com.financetracker.ai.viewmodel.ChatViewModel

@Composable
fun ChatScreen(viewModel: ChatViewModel, onBack: () -> Unit = {}) {
    val messages by viewModel.messages.collectAsState()
    val streaming by viewModel.streamingReply.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val modelReady by viewModel.isModelReady.collectAsState()
    var input by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ScreenHeader("Ask your finances", onBack = onBack, modifier = Modifier.weight(1f))
            if (messages.isNotEmpty()) {
                IconButton(onClick = { confirmClear = true }) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = "Clear chat")
                }
            }
        }
        Text(
            "Runs fully offline on Gemma — ask things like \"how much did I spend on food this month?\"",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))

        if (!modelReady) {
            // The AI engine can't answer without the model file, so say so rather than
            // silently swallowing the message.
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Set up the on-device model first — head to More → AI model setup.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { msg ->
                ChatBubble(role = msg.role, content = msg.content)
            }
            if (isGenerating) {
                item { ChatBubble(role = "assistant", content = streaming.ifEmpty { "…" }) }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask a question…") },
                enabled = !isGenerating && modelReady
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = {
                    viewModel.sendMessage(input)
                    input = ""
                },
                enabled = !isGenerating && modelReady && input.isNotBlank()
            ) {
                Icon(Icons.Filled.Send, contentDescription = "Send")
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear conversation?") },
            text = { Text("This deletes the saved chat history from this device.") },
            confirmButton = {
                TextButton(onClick = { viewModel.clearChat(); confirmClear = false }) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ChatBubble(role: String, content: String) {
    val isUser = role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(0.85f)
        ) {
            Text(content, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
