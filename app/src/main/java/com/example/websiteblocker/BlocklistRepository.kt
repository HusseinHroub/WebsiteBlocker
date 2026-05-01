package com.example.websiteblocker

import android.content.Context

class BlocklistRepository(context: Context) {

    private val prefs = context.getSharedPreferences("blocklist", Context.MODE_PRIVATE)

    fun getAll(): Set<String> = prefs.getStringSet("domains", emptySet()) ?: emptySet()

    fun add(domain: String) {
        val normalized = normalize(domain)
        if (normalized.isEmpty()) return
        val updated = getAll().toMutableSet().apply { add(normalized) }
        prefs.edit().putStringSet("domains", updated).apply()
    }

    fun remove(domain: String) {
        val updated = getAll().toMutableSet().apply { remove(domain) }
        prefs.edit().putStringSet("domains", updated).apply()
    }

    // Strip protocol, www prefix, paths, and lowercase
    private fun normalize(input: String): String =
        input.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .substringBefore("/")
            .lowercase()
}
