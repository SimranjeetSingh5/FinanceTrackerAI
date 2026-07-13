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

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _streamingReply = MutableStateFlow("")
    val streamingReply: StateFlow<String> = _streamingReply.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    init {
        viewModelScope.launch {
            repo.chatHistory.collect { list -> _messages.value = list }
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank() || _isGenerating.value) return
        viewModelScope.launch {
            repo.saveChatMessage("user", text)
            _isGenerating.value = true
            _streamingReply.value = ""

            val history = _messages.value.map { it.role to it.content }

            // Pull a one-shot snapshot of transactions/categories to ground the answer
            val latestTxs = repo.allTransactions.first()
            val latestCats = repo.allCategories.first()

            aiEngine.chatStream(text, history, latestTxs, latestCats).collect { chunk ->
                _streamingReply.value += chunk
            }

            repo.saveChatMessage("assistant", _streamingReply.value)
            _streamingReply.value = ""
            _isGenerating.value = false
        }
    }

    fun clearChat() {
        viewModelScope.launch { repo.clearChat() }
    }
}
