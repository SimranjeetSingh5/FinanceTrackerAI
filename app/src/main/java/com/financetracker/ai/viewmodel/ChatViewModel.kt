package com.financetracker.ai.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.financetracker.ai.FinanceApp
import com.financetracker.ai.data.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as FinanceApp
    private val repo = app.repository
    private val aiEngine = repo.aiEngineRef

    /**
     * The Gemma engine lives on the shared FinanceApp helper, and readiness is owned by
     * FinanceViewModel. We mirror it here so the chat screen can disable its input rather
     * than accepting a message that will never be answered.
     */
    val isModelReady: StateFlow<Boolean> = app.modelReady

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    /**
     * The in-progress reply. Held after the stream ends until Room's flow delivers the saved
     * assistant message, so the bubble never blinks empty between "finished streaming" and
     * "persisted and re-read".
     */
    private val _streamingReply = MutableStateFlow("")
    val streamingReply: StateFlow<String> = _streamingReply.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    init {
        viewModelScope.launch {
            repo.chatHistory.collect { list ->
                _messages.value = list
                // Once the saved reply has been delivered, retire the streaming copy so the
                // bubble doesn't render twice.
                if (list.lastOrNull()?.role == "assistant" && _streamingReply.value.isNotBlank()) {
                    _streamingReply.value = ""
                }
            }
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank() || _isGenerating.value) return
        if (!isModelReady.value) return
        viewModelScope.launch {
            repo.saveChatMessage("user", text)
            _isGenerating.value = true
            _streamingReply.value = ""

            val history = _messages.value.map { it.role to it.content }

            // Pull a one-shot snapshot of transactions/categories to ground the answer
            val latestTxs = repo.allTransactions.first()
            val latestCats = repo.allCategories.first()

            // Guard the streaming loop: if generation throws, clear the spinner instead of
            // leaving the screen stuck on "…".
            runCatching {
                aiEngine.chatStream(text, history, latestTxs, latestCats).collect { chunk ->
                    // MediaPipe emits the whole accumulated response each time, so assign
                    // rather than append — appending duplicates the text once per token.
                    _streamingReply.value = chunk
                }
            }.onFailure {
                _streamingReply.value = "Couldn't reach the model: ${it.message}"
            }

            // Save, then keep the streamed text on screen until Room's flow delivers the saved
            // message. Clearing immediately left a window where the bubble was empty and the
            // assistant message hadn't arrived yet — visible as a blank reply.
            val reply = _streamingReply.value
            repo.saveChatMessage("assistant", reply)
            _isGenerating.value = false
        }
    }

    fun clearChat() {
        viewModelScope.launch { repo.clearChat() }
    }
}
