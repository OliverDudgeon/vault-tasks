package com.vaulttasks

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
            for (id in manager.getAppWidgetIds(ComponentName(context, TasksWidget::class.java))) {
                val filter = Store.widgetFilter(context, id)
                val look = Store.look(context, id)
                val colors = Colors(context, look.theme)
                val views = RemoteViews(context.packageName, R.layout.widget)
                colors.tint(views, R.id.root, "setBackgroundColor", colors.background)
                colors.tint(views, R.id.refresh, "setTextColor", colors.text)
                colors.tint(views, R.id.empty, "setTextColor", colors.meta)
                views.setTextViewText(R.id.refresh, "${filter?.name ?: "Vault Tasks"}  ⟳")
                views.setOnClickPendingIntent(R.id.refresh, refreshIntent(context))

                val selected = runCatching { filter?.select(tasks, LocalDate.now()) { notes[it.file]?.path ?: it.file }.orEmpty() }
                val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
                for (task in selected.getOrDefault(emptyList())) items.addItem(
                    "${task.file}#${task.line}".hashCode().toLong(),
                    row(context, task, notes[task.file]?.path, look, colors),
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
                    R.id.empty,
                    error ?: if (filter == null) "No filter chosen. Tap to choose one." else "No matching tasks",
                )
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

        private fun row(context: Context, task: Task, path: String?, look: Look, colors: Colors) =
            RemoteViews(context.packageName, R.layout.widget_item).apply {
                setTextViewText(
                    R.id.check,
                    when (task.type) {
                        StatusType.TODO -> "☐"
                        StatusType.IN_PROGRESS -> "◐"
                        StatusType.DONE -> "☑"
                        StatusType.CANCELLED -> "☒"
                        StatusType.NON_TASK -> "▫"
                    },
                )
                setTextViewText(R.id.text, styled(context, task))
                val meta = listOfNotNull(
                    task.priority.emoji.ifEmpty { null }.takeIf { look.dates },
                    task.due?.let { "📅 $it" }.takeIf { look.dates },
                    task.scheduled?.let { "⏳ $it" }.takeIf { look.dates },
                    "🔁 ${task.recurrence.orEmpty()}".trimEnd().takeIf { task.recurring },
                    path?.removeSuffix(".md").takeIf { look.path },
                )
                setTextViewText(R.id.meta, meta.joinToString("  "))
                setViewVisibility(R.id.meta, if (meta.isEmpty()) View.GONE else View.VISIBLE)
                colors.tint(this, R.id.check, "setTextColor", colors.text)
                colors.tint(this, R.id.text, "setTextColor", colors.text)
                colors.tint(this, R.id.meta, "setTextColor", colors.meta)
                if (look.compact) {
                    setTextViewTextSize(R.id.text, TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextViewTextSize(R.id.meta, TypedValue.COMPLEX_UNIT_SP, 10f)
                    setViewLayoutHeight(R.id.check, 32f, TypedValue.COMPLEX_UNIT_DIP)
                    setViewPadding(R.id.row, 0, 0, 0, 0)
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
            val link = context.getColor(android.R.color.system_accent1_500)
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

/** Day and night colours for a widget, both one colour if its [Theme] isn't the system's. */
private class Colors(private val context: Context, private val theme: Theme) {
    private fun pair(day: Int, night: Int) = context.getColor(day) to context.getColor(night)
    val background = pair(android.R.color.system_neutral1_50, android.R.color.system_neutral1_900)
    val text = pair(android.R.color.system_neutral1_900, android.R.color.system_neutral1_50)
    val meta = pair(android.R.color.system_neutral1_500, android.R.color.system_neutral1_400)

    fun tint(views: RemoteViews, id: Int, method: String, colors: Pair<Int, Int>) = when (theme) {
        Theme.SYSTEM -> views.setColorInt(id, method, colors.first, colors.second)
        Theme.LIGHT -> views.setInt(id, method, colors.first)
        Theme.DARK -> views.setInt(id, method, colors.second)
    }
}

private fun toast(context: Context, text: String) =
    Handler(Looper.getMainLooper()).post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
