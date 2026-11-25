package com.vaulttasks

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

enum class Theme { SYSTEM, LIGHT, DARK }

/** How a widget shows its tasks (W-07): [dates] is priority and dates, [path] the note's path, [group] its headings. */
data class Look(
    val dates: Boolean = true,
    val path: Boolean = true,
    val compact: Boolean = false,
    val theme: Theme = Theme.SYSTEM,
    val group: GroupBy = GroupBy.NONE,
)

/** App-private state: saved filters, which filter each widget shows and how, the task index cache and the last scan status. */
object Store {
    const val STATUS = "status"

    /** Shown until the user saves their own; the ids are fixed so widgets can point at them. */
    private val PRESETS = listOf(
        Filter("Due today or overdue", due = Due.ON_OR_BEFORE_TODAY, id = "due-by-today"),
        Filter("Due within 7 days or high priority", due = Due.next(7), priority = Priority.HIGH..Priority.HIGHEST, anyOf = true, id = "soon-or-high"),
        Filter("High priority", priority = Priority.HIGH..Priority.HIGHEST, sort = Sort.PRIORITY, id = "high"),
        Filter("All open tasks", id = "all"),
        Filter("All open tasks with IDs", query = "has id", id = "with-ids"),
    )

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("store", Context.MODE_PRIVATE)

    fun filters(context: Context): List<Filter> = prefs(context).getString("filters", null)?.let(::decodeFilters) ?: PRESETS

    fun saveFilters(context: Context, filters: List<Filter>) = prefs(context).edit().putString("filters", encodeFilters(filters)).apply()

    /** Null when the widget isn't configured or its filter was deleted. */
    fun widgetFilter(context: Context, widgetId: Int): Filter? =
        prefs(context).getString("widget.$widgetId", null)?.let { id -> filters(context).find { it.id == id } }

    fun hasWidgetSettings(context: Context, widgetId: Int) = prefs(context).contains("look.$widgetId")

    fun look(context: Context, widgetId: Int): Look =
        prefs(context).getString("look.$widgetId", null)?.split(",")?.let { fields ->
            val (dates, path, compact, theme) = fields
            Look(dates.toBoolean(), path.toBoolean(), compact.toBoolean(), Theme.valueOf(theme), GroupBy.valueOf(fields.getOrElse(4) { "NONE" }))
        } ?: Look()

    fun saveWidgetSettings(context: Context, widgetId: Int, filterId: String?, look: Look) = with(look) {
        prefs(context).edit()
            .putString("look.$widgetId", "$dates,$path,$compact,$theme,$group")
            .apply { if (filterId != null) putString("widget.$widgetId", filterId) }
            .apply()
    }

    fun forgetWidgets(context: Context, widgetIds: IntArray) =
        prefs(context).edit().apply { widgetIds.forEach { remove("widget.$it"); remove("look.$it") } }.apply()

    fun status(context: Context): String? = prefs(context).getString(STATUS, null)

    fun setStatus(context: Context, status: String) = prefs(context).edit().putString(STATUS, status).apply()

    private fun indexFile(context: Context) = File(context.filesDir, "index.bin")

    // Java serialization. A changed Task class can still deserialize, leaving new fields null, so the cache is only
    // trusted from the build that wrote it: an app update costs one full rescan, which is fine for a cache.
    private fun build(context: Context) = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime

    @Suppress("UNCHECKED_CAST")
    fun index(context: Context): Map<String, IndexedNote> = runCatching {
        ObjectInputStream(indexFile(context).inputStream()).use {
            if (it.readLong() == build(context)) it.readObject() as Map<String, IndexedNote> else emptyMap()
        }
    }.getOrDefault(emptyMap())

    fun saveIndex(context: Context, index: Map<String, IndexedNote>) {
        val temp = File(context.filesDir, "index.tmp")
        ObjectOutputStream(temp.outputStream()).use {
            it.writeLong(build(context))
            it.writeObject(HashMap(index))
        }
        temp.renameTo(indexFile(context))
    }
}
