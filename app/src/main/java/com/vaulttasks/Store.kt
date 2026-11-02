package com.vaulttasks

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/** App-private state: saved filters, which filter each widget shows, the task index cache and the last scan status. */
object Store {
    const val STATUS = "status"

    /** Shown until the user saves their own; the ids are fixed so widgets can point at them. */
    private val PRESETS = listOf(
        Filter("Due today or overdue", due = Due.ON_OR_BEFORE_TODAY, id = "due-by-today"),
        Filter("Due within 7 days or high priority", due = Due.next(7), priority = Priority.HIGH..Priority.HIGHEST, anyOf = true, id = "soon-or-high"),
        Filter("High priority", priority = Priority.HIGH..Priority.HIGHEST, sort = Sort.PRIORITY, id = "high"),
        Filter("All open tasks", id = "all"),
    )

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("store", Context.MODE_PRIVATE)

    fun filters(context: Context): List<Filter> = prefs(context).getString("filters", null)?.let(::decodeFilters) ?: PRESETS

    fun saveFilters(context: Context, filters: List<Filter>) = prefs(context).edit().putString("filters", encodeFilters(filters)).apply()

    /** Null when the widget isn't configured or its filter was deleted. */
    fun widgetFilter(context: Context, widgetId: Int): Filter? =
        prefs(context).getString("widget.$widgetId", null)?.let { id -> filters(context).find { it.id == id } }

    fun setWidgetFilter(context: Context, widgetId: Int, filterId: String) =
        prefs(context).edit().putString("widget.$widgetId", filterId).apply()

    fun forgetWidgets(context: Context, widgetIds: IntArray) =
        prefs(context).edit().apply { widgetIds.forEach { remove("widget.$it") } }.apply()

    fun status(context: Context): String? = prefs(context).getString(STATUS, null)

    fun setStatus(context: Context, status: String) = prefs(context).edit().putString(STATUS, status).apply()

    private fun indexFile(context: Context) = File(context.filesDir, "index.bin")

    // ponytail: Java serialization; any class change fails the read and costs one full rescan, which is fine for a cache.
    @Suppress("UNCHECKED_CAST")
    fun index(context: Context): Map<String, IndexedNote> =
        runCatching { ObjectInputStream(indexFile(context).inputStream()).use { it.readObject() as Map<String, IndexedNote> } }
            .getOrDefault(emptyMap())

    fun saveIndex(context: Context, index: Map<String, IndexedNote>) {
        val temp = File(context.filesDir, "index.tmp")
        ObjectOutputStream(temp.outputStream()).use { it.writeObject(HashMap(index)) }
        temp.renameTo(indexFile(context))
    }
}
