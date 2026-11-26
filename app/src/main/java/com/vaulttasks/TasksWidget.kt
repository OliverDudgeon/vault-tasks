package com.vaulttasks

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.concurrent.thread

/** A list of the vault's tasks matching the saved filter chosen for each widget instance. */
class TasksWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        RefreshJob.request(context)
    }

    override fun onDeleted(context: Context, ids: IntArray) = Store.forgetWidgets(context, ids)

    companion object {
        private val settingsLock = Any()
        private var refreshGeneration = 0L

        fun refreshGeneration(): Long = synchronized(settingsLock) { refreshGeneration }

        fun applySettings(context: Context, id: Int, filterId: String?, look: Look) = synchronized(settingsLock) {
            val existing = Store.hasWidgetSettings(context, id)
            val previousLook = if (existing) Store.look(context, id) else look
            Store.saveWidgetSettings(context, id, filterId, look)
            val manager = AppWidgetManager.getInstance(context)
            val filter = Store.widgetFilter(context, id)
            if (existing) {
                manager.partiallyUpdateAppWidget(id, headerProgressViews(context, previousLook, R.string.widget_applying_settings))
            } else {
                manager.updateAppWidget(id, settingsStatus(context, filter, look, loading = true))
            }
            if (RefreshJob.request(context, expedited = true)) {
                refreshGeneration++
            } else {
                manager.updateAppWidget(id, settingsStatus(context, filter, look, loading = false))
            }
        }

        /** Immediately redraws widgets from the cached index so a sync-permission change shows up at once, then scans. */
        fun refreshSyncStatus(context: Context) {
            val app = context.applicationContext
            thread {
                val cached = Store.index(app)
                if (Vault.tree(app) != null && cached.isNotEmpty()) {
                    renderAll(app, Result.success(cached))
                    RefreshJob.request(app, expedited = true)
                } else {
                    requestRefresh(app)
                }
            }
        }

        fun requestRefresh(context: Context) = synchronized(settingsLock) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TasksWidget::class.java))
            for (id in ids) {
                manager.partiallyUpdateAppWidget(id, headerProgressViews(context, Store.look(context, id), R.string.widget_refreshing))
            }
            if (RefreshJob.request(context, expedited = true)) {
                refreshGeneration++
            } else {
                for (id in ids) {
                    val paint = Painter(context, Store.look(context, id).theme)
                    val views = RemoteViews(context.packageName, R.layout.widget).apply {
                        setTextViewText(R.id.sync, context.getText(R.string.widget_refresh_failed))
                        setViewVisibility(R.id.sync, View.VISIBLE)
                        setViewVisibility(R.id.refresh, View.VISIBLE)
                        setViewVisibility(R.id.header_progress, View.GONE)
                        paint.color(this, R.id.sync, "setTextColor", R.color.widget_error)
                    }
                    manager.partiallyUpdateAppWidget(id, views)
                }
            }
        }

        private fun headerProgressViews(context: Context, look: Look, statusRes: Int): RemoteViews {
            val paint = Painter(context, look.theme)
            return RemoteViews(context.packageName, R.layout.widget).apply {
                setTextViewText(R.id.sync, context.getText(statusRes))
                setViewVisibility(R.id.sync, View.VISIBLE)
                setViewVisibility(R.id.refresh, View.GONE)
                setViewVisibility(R.id.header_progress, View.VISIBLE)
                paint.color(this, R.id.sync, "setTextColor", R.color.widget_primary)
                paint.tint(this, R.id.header_progress, "setIndeterminateTintList", R.color.widget_primary)
            }
        }

        private fun widgetViews(context: Context, filter: Filter?, paint: Painter) =
            RemoteViews(context.packageName, R.layout.widget).apply {
                paint.tint(this, android.R.id.background, "setBackgroundTintList", R.color.widget_background)
                paint.color(this, R.id.title, "setTextColor", R.color.widget_primary)
                paint.color(this, R.id.refresh, "setColorFilter", R.color.widget_on_surface_variant)
                paint.color(this, R.id.empty_icon, "setColorFilter", R.color.widget_primary)
                paint.color(this, R.id.empty_text, "setTextColor", R.color.widget_on_surface_variant)
                setTextViewText(R.id.title, filter?.name ?: context.getString(R.string.app_name))
                setOnClickPendingIntent(R.id.refresh, refreshIntent(context))
                setViewVisibility(R.id.header_progress, View.GONE)
            }

        private fun settingsStatus(context: Context, filter: Filter?, look: Look, loading: Boolean): RemoteViews {
            val paint = Painter(context, look.theme)
            return widgetViews(context, filter, paint).apply {
                setViewVisibility(R.id.sync, View.GONE)
                setViewVisibility(R.id.refresh, if (loading) View.INVISIBLE else View.VISIBLE)
                setViewVisibility(R.id.list, View.GONE)
                setViewVisibility(R.id.empty, View.VISIBLE)
                setViewVisibility(R.id.empty_progress, if (loading) View.VISIBLE else View.GONE)
                setViewVisibility(R.id.empty_icon, if (loading) View.GONE else View.VISIBLE)
                val message = if (loading) R.string.widget_applying_settings else R.string.widget_refresh_failed
                setTextViewText(R.id.empty_text, context.getText(message))
                if (loading) {
                    paint.tint(this, R.id.empty_progress, "setIndeterminateTintList", R.color.widget_primary)
                    setOnClickPendingIntent(R.id.empty, null)
                } else {
                    setImageViewResource(R.id.empty_icon, R.drawable.ic_warning)
                    paint.color(this, R.id.empty_icon, "setColorFilter", R.color.widget_error)
                    setOnClickPendingIntent(R.id.empty, refreshIntent(context))
                }
            }
        }

        /** Draws every widget from [index], or its error. */
        fun renderAll(context: Context, index: Result<Map<String, IndexedNote>>, generation: Long = refreshGeneration()) {
            val manager = AppWidgetManager.getInstance(context)
            val notes = index.getOrDefault(emptyMap())
            val tasks = notes.values.flatMap { it.tasks }
            val today = LocalDate.now()
            val tree = Vault.tree(context)
            val sync = runCatching {
                syncState(Obsidian.hasUsageAccess(context), Obsidian.lastUsed(context), notes.values.map { it.modified })
            }.getOrDefault(Sync.NoAccess)

            fun draw(id: Int, filter: Filter?, look: Look): RemoteViews {
                val paint = Painter(context, look.theme)
                val views = widgetViews(context, filter, paint)
                views.setViewVisibility(R.id.refresh, View.VISIBLE)
                views.setViewVisibility(R.id.empty_progress, View.GONE)
                views.setViewVisibility(R.id.empty_icon, View.VISIBLE)
                syncLine(context, views, sync, tree, paint, today)

                val path = { t: Task -> notes[t.file]?.path ?: t.file }
                val selected = runCatching { filter?.select(tasks, today, path).orEmpty() }
                val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(2)
                for (group in groupTasks(selected.getOrDefault(emptyList()), look.group, today, path)) {
                    if (group.title != null) items.addItem(
                        "group:${group.title}:${group.detail}".hashCode().toLong(),
                        header(context, group, paint, today),
                    )
                    for (task in group.tasks) items.addItem(
                        "${task.file}#${task.line}".hashCode().toLong(),
                        row(context, task, path(task), look, paint, today),
                    )
                }
                views.setRemoteAdapter(R.id.list, items.build())
                views.setPendingIntentTemplate(
                    R.id.list,
                    broadcast(context, Intent(), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                )

                // Empty states say what's wrong and tapping them goes where it can be fixed (W-05).
                views.setEmptyView(R.id.list, R.id.empty)
                val error = index.exceptionOrNull()?.message ?: selected.exceptionOrNull()?.let { "${it.message}. Tap to edit the filter." }
                views.setTextViewText(
                    R.id.empty_text,
                    error ?: when {
                        filter == null -> "No filter chosen. Tap to choose one."
                        look.group == GroupBy.AGENDA -> "All clear. No matching tasks with due dates."
                        else -> "All clear. No matching tasks."
                    },
                )
                views.setImageViewResource(R.id.empty_icon, if (error != null) R.drawable.ic_warning else R.drawable.ic_task_alt)
                if (error != null) paint.color(views, R.id.empty_icon, "setColorFilter", R.color.widget_error)
                val fix = when {
                    error != null -> Intent(context, MainActivity::class.java)
                    filter == null -> Intent(context, WidgetConfigActivity::class.java)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    else -> null
                }
                views.setOnClickPendingIntent(
                    R.id.empty,
                    fix?.let { PendingIntent.getActivity(context, id, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) },
                )
                return views
            }

            for (id in manager.getAppWidgetIds(ComponentName(context, TasksWidget::class.java))) {
                val (filter, look) = synchronized(settingsLock) {
                    Store.widgetFilter(context, id) to Store.look(context, id)
                }
                val views = try {
                    draw(id, filter, look)
                } catch (e: RuntimeException) {
                    Log.w("VaultTasks", "Couldn't draw widget $id", e)
                    settingsStatus(context, filter, look, loading = false)
                }
                synchronized(settingsLock) {
                    // A settings save or newer manual refresh owns the display until the next scan finishes.
                    if (generation == refreshGeneration && look == Store.look(context, id) && filter == Store.widgetFilter(context, id)) {
                        try {
                            manager.updateAppWidget(id, views)
                        } catch (e: RuntimeException) {
                            Log.w("VaultTasks", "Couldn't update widget $id", e)
                            manager.updateAppWidget(id, settingsStatus(context, filter, look, loading = false))
                        }
                    }
                }
            }
        }

        /** Under the title: whether Obsidian has synced. Tapping the title opens Obsidian, or asks for usage access. */
        private fun syncLine(context: Context, views: RemoteViews, sync: Sync, tree: Uri?, paint: Painter, today: LocalDate) {
            val text = when (sync) {
                Sync.NoAccess -> "Tap to show sync status"
                Sync.NotRecently -> "Not synced in 4 weeks"
                is Sync.Unsynced -> "${sync.notes} ${if (sync.notes == 1) "note" else "notes"} not synced. Tap to sync."
                is Sync.Synced -> Instant.ofEpochMilli(sync.at).atZone(ZoneId.systemDefault()).let { at ->
                    val time = at.format(DateTimeFormatter.ofPattern("HH:mm"))
                    if (at.toLocalDate() == today) "Synced $time" else "Synced ${friendlyDate(at.toLocalDate(), today)} $time"
                }
            }
            views.setTextViewText(R.id.sync, text)
            views.setViewVisibility(R.id.sync, if (tree == null) View.GONE else View.VISIBLE)
            val warn = sync is Sync.Unsynced || sync is Sync.NotRecently
            paint.color(views, R.id.sync, "setTextColor", if (warn) R.color.widget_error else R.color.widget_on_surface_variant)
            val intent = when {
                tree == null -> null
                sync == Sync.NoAccess -> Intent(context, UsageAccessActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                else -> Obsidian.open(tree)
            }
            views.setOnClickPendingIntent(
                R.id.heading,
                intent?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) },
            )
        }

        /** A group's heading: agenda days that have passed are in the error colour. */
        private fun header(context: Context, group: Group, paint: Painter, today: LocalDate) =
            RemoteViews(context.packageName, R.layout.widget_header).apply {
                setTextViewText(R.id.header_title, group.title)
                setTextViewText(R.id.header_detail, group.detail.orEmpty())
                setTextViewText(R.id.header_count, "${group.tasks.size}")
                setContentDescription(R.id.header, listOfNotNull(group.title, group.detail, "${group.tasks.size} tasks").joinToString(", "))
                val overdue = group.date != null && group.date < today
                paint.color(this, R.id.header_title, "setTextColor", if (overdue) R.color.widget_error else R.color.widget_primary)
                paint.color(this, R.id.header_detail, "setTextColor", if (overdue) R.color.widget_error else R.color.widget_on_surface_variant)
                paint.color(this, R.id.header_count, "setTextColor", R.color.widget_on_surface_variant)
                if (group.notePath != null) {
                    setOnClickFillInIntent(R.id.header, Intent(ACTION_OPEN).putExtra("path", group.notePath))
                }
            }

        private fun row(context: Context, task: Task, path: String, look: Look, paint: Painter, today: LocalDate) =
            RemoteViews(context.packageName, R.layout.widget_item).apply {
                val (icon, label) = when (task.type) {
                    StatusType.TODO -> R.drawable.ic_todo to "Complete task"
                    StatusType.IN_PROGRESS -> R.drawable.ic_in_progress to "Complete task, in progress"
                    StatusType.DONE -> R.drawable.ic_done to "Done"
                    StatusType.CANCELLED -> R.drawable.ic_cancelled to "Cancelled"
                    StatusType.NON_TASK -> R.drawable.ic_todo to "Not a task"
                }
                setImageViewResource(R.id.check, icon)
                setContentDescription(R.id.check, label)
                paint.tint(this, R.id.row, "setBackgroundTintList", R.color.widget_row)
                paint.color(this, R.id.check, "setColorFilter", if (task.done) R.color.widget_outline else R.color.widget_primary)
                setTextViewText(R.id.text, styled(context, task))
                paint.color(this, R.id.text, "setTextColor", if (task.done) R.color.widget_on_surface_variant else R.color.widget_on_surface)

                // Priority as a flag, coloured by level; due as a short date, coloured when it's today or late (W-08).
                val flag = mapOf(
                    Priority.HIGHEST to R.color.widget_error, Priority.HIGH to R.color.widget_tertiary,
                    Priority.MEDIUM to R.color.widget_primary, Priority.LOW to R.color.widget_outline, Priority.LOWEST to R.color.widget_outline,
                )[task.priority]?.takeIf { look.dates }
                setViewVisibility(R.id.priority, if (flag == null) View.GONE else View.VISIBLE)
                if (flag != null) {
                    paint.color(this, R.id.priority, "setColorFilter", flag)
                    setContentDescription(R.id.priority, "${task.priority.name.lowercase().replaceFirstChar(Char::uppercase)} priority")
                }
                // Under a heading, rows leave out what it already says.
                val due = task.due?.takeIf { look.dates && look.group != GroupBy.AGENDA }
                setTextViewText(R.id.due, due?.let { friendlyDate(it, today) }.orEmpty())
                setViewVisibility(R.id.due, if (due == null) View.GONE else View.VISIBLE)
                paint.color(
                    this, R.id.due, "setTextColor",
                    when {
                        due == null || task.done -> R.color.widget_on_surface_variant
                        due < today -> R.color.widget_error
                        due == today -> R.color.widget_primary
                        else -> R.color.widget_on_surface_variant
                    },
                )
                val meta = listOfNotNull(
                    task.scheduled?.let { "Scheduled ${friendlyDate(it, today)}" }.takeIf { look.dates },
                    "Repeats ${task.recurrence.orEmpty()}".trimEnd().takeIf { task.recurring },
                    when (look.group) {
                        GroupBy.NOTE -> null
                        GroupBy.FOLDER -> path.substringAfterLast("/")
                        else -> path
                    }?.removeSuffix(".md")?.takeIf { look.path },
                )
                setTextViewText(R.id.meta, meta.joinToString(" · "))
                paint.color(this, R.id.meta, "setTextColor", R.color.widget_on_surface_variant)
                setViewVisibility(R.id.meta_row, if (meta.isEmpty() && flag == null && due == null) View.GONE else View.VISIBLE)
                if (look.compact) {
                    setTextViewTextSize(R.id.text, TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextViewTextSize(R.id.due, TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextViewTextSize(R.id.meta, TypedValue.COMPLEX_UNIT_SP, 11f)
                    setViewLayoutHeight(R.id.check, 40f, TypedValue.COMPLEX_UNIT_DIP)
                    setViewLayoutWidth(R.id.check, 40f, TypedValue.COMPLEX_UNIT_DIP)
                    val px = (context.resources.displayMetrics.density * 1).toInt()
                    setViewPadding(R.id.item, 0, px, 0, px)
                    setViewPadding(R.id.check, 10 * px, 10 * px, 10 * px, 10 * px)
                }
                // Tapping the task opens its note in Obsidian (at its line, block id or heading); tapping the checkbox completes it.
                setOnClickFillInIntent(
                    R.id.row,
                    Intent(ACTION_OPEN)
                        .putExtra("path", path)
                        .putExtra("line", task.line)
                        .apply {
                            if (task.blockId != null) putExtra("blockId", task.blockId)
                            if (task.heading != null) putExtra("heading", task.heading)
                        },
                )
                if (!task.done) setOnClickFillInIntent(
                    R.id.check,
                    Intent().putExtra("file", task.file).putExtra("line", task.line).putExtra("text", task.text)
                        .putExtra("noteHash", task.noteHash),
                )
            }

        /** The description with its Markdown styles; done and cancelled tasks are struck through, as in Obsidian. */
        private fun styled(context: Context, task: Task): CharSequence {
            val (text, spans) = inlineMarkdown(task.description)
            // Spans can't follow day/night, so links take a mid tone that reads on both.
            val link = context.getColor(android.R.color.system_accent1_400)
            return SpannableString(text).apply {
                for (span in spans) {
                    val what: List<Any> = when (span.style) {
                        Style.BOLD -> listOf(StyleSpan(Typeface.BOLD))
                        Style.ITALIC -> listOf(StyleSpan(Typeface.ITALIC))
                        Style.STRIKE -> listOf(StrikethroughSpan())
                        Style.HIGHLIGHT -> listOf(BackgroundColorSpan(0x66FFD54F))
                        Style.CODE -> listOf(TypefaceSpan("monospace"))
                        Style.LINK -> listOf(ForegroundColorSpan(link), UnderlineSpan())
                    }
                    for (w in what) setSpan(w, span.start, span.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (task.done) setSpan(StrikethroughSpan(), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        fun refreshIntent(context: Context) = broadcast(
            context, Intent(ACTION_REFRESH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            requestCode = 1,
        )

        fun scheduledRefreshIntent(context: Context) = broadcast(
            context, Intent(ACTION_SCHEDULED_REFRESH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            requestCode = 2,
        )

        private fun broadcast(context: Context, intent: Intent, flags: Int, requestCode: Int = 0) =
            PendingIntent.getBroadcast(context, requestCode, intent.setClass(context, WidgetActionReceiver::class.java), flags)
    }
}

private const val ACTION_REFRESH = "com.vaulttasks.REFRESH"
private const val ACTION_SCHEDULED_REFRESH = "com.vaulttasks.SCHEDULED_REFRESH"
private const val ACTION_OPEN = "com.vaulttasks.OPEN"

/** Widget taps: complete a task, open its note, or refresh. Not exported, so only our own PendingIntents can make it write. */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            TasksWidget.requestRefresh(context)
            return
        }
        if (intent.action == ACTION_SCHEDULED_REFRESH) {
            RefreshJob.request(context)
            return
        }
        if (intent.action == ACTION_OPEN) {
            val tree = Vault.tree(context) ?: return
            val line = intent.getIntExtra("line", -1).takeIf { it >= 0 }
            return runCatching {
                context.startActivity(
                    Obsidian.open(
                        tree = tree,
                        path = intent.getStringExtra("path"),
                        line = line,
                        blockId = intent.getStringExtra("blockId"),
                        heading = intent.getStringExtra("heading"),
                        useAdvancedUri = Store.hasAdvancedUri(context),
                    ),
                )
            }.getOrElse { toast(context, "Couldn't open Obsidian: ${it.message}") }
        }
        val pending = goAsync()
        thread {
            try {
                complete(context, intent)
            } catch (e: Exception) {
                Log.w("VaultTasks", "Complete failed", e)
                toast(context, "Couldn't complete the task: ${e.message}")
            } finally {
                RefreshJob.request(context, expedited = true)
                pending.finish()
            }
        }
    }

    private fun complete(context: Context, intent: Intent) {
        val task = Task(
            intent.getStringExtra("file")!!, intent.getIntExtra("line", -1), intent.getStringExtra("text")!!,
            intent.getIntExtra("noteHash", 0),
        )
        val note = Uri.parse(task.file)
        val result = completeInNote(
            task, LocalDate.now(),
            read = { Vault.read(context, note) },
            write = { before, after -> Vault.write(context, note, before, after) },
        )
        Log.i("VaultTasks", "Complete line ${task.line + 1}: ${result::class.simpleName}")
        when (result) {
            is Completed -> {}
            Conflict -> toast(context, "The note changed since the last scan. Nothing was written; refreshing.")
            Unsupported -> toast(context, "This task's recurrence rule or custom status isn't supported here. Complete it in Obsidian.")
        }
    }
}

/**
 * Paints a widget in its colour roles (res/values and values-night colors.xml), resolved for day and for night so
 * it can follow the system or force either [Theme].
 */
private class Painter(private val context: Context, private val theme: Theme) {
    private fun at(night: Int) = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
    })
    private val day = at(Configuration.UI_MODE_NIGHT_NO)
    private val night = at(Configuration.UI_MODE_NIGHT_YES)

    private fun pair(color: Int) = day.getColor(color) to night.getColor(color)

    /** Calls [method] on view [id] with [color], an int colour resource. */
    fun color(views: RemoteViews, id: Int, method: String, color: Int) {
        val (day, night) = pair(color)
        when (theme) {
            Theme.SYSTEM -> views.setColorInt(id, method, day, night)
            Theme.LIGHT -> views.setInt(id, method, day)
            Theme.DARK -> views.setInt(id, method, night)
        }
    }

    /** Applies a tint to a view drawable, keeping its shape. */
    fun tint(views: RemoteViews, id: Int, method: String, color: Int) {
        val (day, night) = pair(color).let { (d, n) -> ColorStateList.valueOf(d) to ColorStateList.valueOf(n) }
        when (theme) {
            Theme.SYSTEM -> views.setColorStateList(id, method, day, night)
            Theme.LIGHT -> views.setColorStateList(id, method, day)
            Theme.DARK -> views.setColorStateList(id, method, night)
        }
    }
}

/** A due or scheduled date as a widget shows it: Yesterday, Today, Tomorrow, a weekday this week, else the date. */
internal fun friendlyDate(date: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String {
    val days = ChronoUnit.DAYS.between(today, date)
    return when {
        days == -1L -> "Yesterday"
        days == 0L -> "Today"
        days == 1L -> "Tomorrow"
        days in 2..6 -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
        date.year == today.year -> date.format(DateTimeFormatter.ofPattern("d MMM", locale))
        else -> date.format(DateTimeFormatter.ofPattern("d MMM yyyy", locale))
    }
}

private fun toast(context: Context, text: String) =
    Handler(Looper.getMainLooper()).post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
