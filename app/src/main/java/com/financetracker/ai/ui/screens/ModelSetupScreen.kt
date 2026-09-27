package com.financetracker.ai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.financetracker.ai.util.Constants
import com.financetracker.ai.viewmodel.FinanceViewModel
import com.financetracker.ai.viewmodel.ModelState

@Composable
fun ModelSetupScreen(viewModel: FinanceViewModel, onBack: () -> Unit = {}) {
    val state by viewModel.modelState.collectAsState()

    Box(Modifier.fillMaxSize()) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back"
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
        val iconColor = if (state is ModelState.Ready) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

        Icon(
            imageVector = if (state is ModelState.Ready) Icons.Filled.CheckCircle else Icons.Filled.CloudDownload,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = iconColor
        )
        Spacer(Modifier.height(16.dp))

        Text(
            text = "Set up your on-device AI",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))

        Text(
            text = "FinanceTracker AI runs Gemma entirely locally on your phone. Nothing you type " +
                    "ever leaves the device, there are no cloud API keys required, and inference is completely free. " +
                    "Tap download below to sync the model assets to your secure storage directory.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(32.dp))

        when (val currentState = state) {
            is ModelState.NotDownloaded -> {
                Button(
                    onClick = { viewModel.downloadAndInstallModel() },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text("Download AI Model from Cloud")
                }
            }
            is ModelState.Downloading -> {
                val pct = (currentState.progress * 100).toInt().coerceIn(0, 100)
                val resuming = currentState.resumedFrom > 0
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        progress = { currentState.progress },
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = when {
                            resuming ->
                                "Resuming download… $pct% (${Constants.formatSize(currentState.resumedFrom)} already saved)"
                            currentState.progress > 0f ->
                                "Downloading model… $pct%${currentState.source.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""}"
                            else ->
                                "Downloading model payload${currentState.source.takeIf { it.isNotBlank() }?.let { " from $it" } ?: ""}..."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                    if (!resuming && currentState.progress <= 0f) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "About 800 MB — Wi-Fi recommended. You can pause and resume later.",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            is ModelState.Loading -> {
                CircularProgressIndicator(modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Initializing machine learning environment into local memory...",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
            is ModelState.Ready -> {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                ) {
                    Text(
                        text = "Local Model Operational ✓",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
            }
            is ModelState.Error -> {
                Text(
                    text = currentState.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                if (viewModel.hasPartialDownload()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Progress saved — retrying picks up where it left off.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { viewModel.downloadAndInstallModel() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
                ) {
                    Text("Retry Download Pipeline")
                }
            }
        }
        }
    }
}