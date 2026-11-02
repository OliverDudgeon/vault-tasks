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
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.concurrent.thread

/** A list of the vault's tasks matching the saved filter chosen for each widget instance. */
class TasksWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = RefreshJob.request(context)

    override fun onDeleted(context: Context, ids: IntArray) = Store.forgetWidgets(context, ids)

    companion object {
        /** Draws every widget from [index], or its error. */
        fun renderAll(context: Context, index: Result<Map<String, IndexedNote>>) {
            val manager = AppWidgetManager.getInstance(context)
            val notes = index.getOrDefault(emptyMap())
            val tasks = notes.values.flatMap { it.tasks }
            val today = LocalDate.now()
            for (id in manager.getAppWidgetIds(ComponentName(context, TasksWidget::class.java))) {
                val filter = Store.widgetFilter(context, id)
                val look = Store.look(context, id)
                val paint = Painter(context, look.theme)
                val views = RemoteViews(context.packageName, R.layout.widget)
                paint.background(views, android.R.id.background, R.color.widget_background)
                paint.color(views, R.id.title, "setTextColor", R.color.widget_primary)
                paint.color(views, R.id.refresh, "setColorFilter", R.color.widget_on_surface_variant)
                paint.color(views, R.id.empty_icon, "setColorFilter", R.color.widget_primary)
                paint.color(views, R.id.empty_text, "setTextColor", R.color.widget_on_surface_variant)
                views.setTextViewText(R.id.title, filter?.name ?: context.getString(R.string.app_name))
                views.setOnClickPendingIntent(R.id.refresh, refreshIntent(context))

                val selected = runCatching { filter?.select(tasks, today) { notes[it.file]?.path ?: it.file }.orEmpty() }
                val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
                for (task in selected.getOrDefault(emptyList())) items.addItem(
                    "${task.file}#${task.line}".hashCode().toLong(),
                    row(context, task, notes[task.file]?.path, look, paint, today),
                )
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
                    error ?: if (filter == null) "No filter chosen. Tap to choose one." else "All clear. No matching tasks.",
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
                manager.updateAppWidget(id, views)
            }
        }

        private fun row(context: Context, task: Task, path: String?, look: Look, paint: Painter, today: LocalDate) =
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
                paint.background(this, R.id.row, R.color.widget_row)
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
                val due = task.due?.takeIf { look.dates }
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
                    path?.removeSuffix(".md").takeIf { look.path },
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

        fun refreshIntent(context: Context) = broadcast(context, Intent(ACTION_REFRESH), PendingIntent.FLAG_IMMUTABLE)

        private fun broadcast(context: Context, intent: Intent, flags: Int) =
            PendingIntent.getBroadcast(context, 0, intent.setClass(context, WidgetActionReceiver::class.java), flags)
    }
}

private const val ACTION_REFRESH = "com.vaulttasks.REFRESH"

/** Widget taps: complete a task, or refresh. Not exported, so only our own PendingIntents can make it write. */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) return RefreshJob.request(context)
        val pending = goAsync()
        thread {
            try {
                complete(context, intent)
            } catch (e: Exception) {
                Log.w("VaultTasks", "Complete failed", e)
                toast(context, "Couldn't complete the task: ${e.message}")
            } finally {
                RefreshJob.request(context)
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

    /** Tints view [id]'s background drawable, keeping its rounded shape. */
    fun background(views: RemoteViews, id: Int, color: Int) {
        val (day, night) = pair(color).let { (d, n) -> ColorStateList.valueOf(d) to ColorStateList.valueOf(n) }
        when (theme) {
            Theme.SYSTEM -> views.setColorStateList(id, "setBackgroundTintList", day, night)
            Theme.LIGHT -> views.setColorStateList(id, "setBackgroundTintList", day)
            Theme.DARK -> views.setColorStateList(id, "setBackgroundTintList", night)
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
