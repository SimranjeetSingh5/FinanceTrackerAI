package com.financetracker.ai.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.financetracker.ai.viewmodel.FinanceViewModel
import com.financetracker.ai.viewmodel.ModelState

@Composable
fun ModelSetupScreen(viewModel: FinanceViewModel) {
    val state by viewModel.modelState.collectAsState()
    val context = LocalContext.current

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            context.contentResolver.openInputStream(it)?.use { stream ->
                viewModel.installModelFromUri(stream)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(16.dp))
        Text("Set up your on-device AI", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "FinanceTracker AI runs Gemma entirely on your phone — nothing you type ever " +
                "leaves the device, and there's no per-message quota since it's not a cloud API. " +
                "You'll need to load a Gemma .task model file once.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))

        when (state) {
            is ModelState.NotDownloaded -> {
                Button(onClick = { filePicker.launch("*/*") }) {
                    Text("Choose downloaded .task file")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Get a model from Kaggle's Gemma page (search \"Gemma 3 MediaPipe\") " +
                        "and download the int4 .task variant sized for mobile, then pick it here.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            is ModelState.Loading -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("Loading model into memory…")
            }
            is ModelState.Ready -> {
                Text("Model ready ✓", color = MaterialTheme.colorScheme.primary)
            }
            is ModelState.Error -> {
                Text(
                    (state as ModelState.Error).message,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { filePicker.launch("*/*") }) {
                    Text("Try a different file")
                }
            }
        }
    }
}
