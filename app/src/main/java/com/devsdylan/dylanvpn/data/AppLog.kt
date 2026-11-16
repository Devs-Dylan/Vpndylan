package com.devsdylan.dylanvpn.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Journal en mémoire affiché dans l'écran « Journaux ». */
object AppLog {
    @Volatile
    var limit: Int = 1000

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun i(message: String) = add("INFO", message)
    fun w(message: String) = add("WARN", message)
    fun e(message: String) = add("ERREUR", message)

    private fun add(level: String, message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "$time [$level] $message"
        _lines.update { old ->
            val next = old + line
            if (next.size > limit) next.takeLast(limit) else next
        }
    }

    fun clear() {
        _lines.value = emptyList()
    }

    fun text(): String = _lines.value.joinToString("\n")
}
