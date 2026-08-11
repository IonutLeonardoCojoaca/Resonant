package com.example.resonant.aria

import android.content.Context
import com.example.resonant.data.network.ApiClient
import com.example.resonant.managers.SessionIdManager
import com.example.resonant.managers.SessionManager
import com.example.resonant.ui.viewmodels.AriaViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sends prompts from any Activity-owned Aria surface using the shared conversation ViewModel. */
object AriaPromptDispatcher {
    const val MAX_PROMPT_LENGTH = 500

    fun submit(
        context: Context,
        scope: CoroutineScope,
        viewModel: AriaViewModel,
        rawPrompt: String,
        onError: (String) -> Unit = {}
    ) {
        val prompt = rawPrompt.trim()
        if (prompt.isEmpty()) return
        if (prompt.length > MAX_PROMPT_LENGTH) {
            onError("El mensaje es demasiado largo (máx. $MAX_PROMPT_LENGTH caracteres)")
            return
        }

        viewModel.addUserMessage(prompt)
        val appContext = context.applicationContext
        scope.launch(Dispatchers.IO) {
            val session = SessionManager(appContext, ApiClient.baseUrl())
            val token = session.getValidAccessToken()
            if (token == null) {
                withContext(Dispatchers.Main) {
                    val message = "No se pudo autenticar. Reinicia la app e inténtalo de nuevo."
                    viewModel.addAriaMessage(message, isComplete = true)
                    onError(message)
                }
                return@launch
            }

            viewModel.sendPrompt(
                prompt = prompt,
                sessionToken = token,
                baseUrl = ApiClient.baseUrl(),
                sessionId = SessionIdManager.getOrCreateSessionId(appContext)
            )
        }
    }
}
