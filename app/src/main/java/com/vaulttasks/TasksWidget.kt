package com.vaulttasks

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
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
                val views = RemoteViews(context.packageName, R.layout.widget)
                views.setTextViewText(R.id.refresh, "${filter?.name ?: "Vault Tasks"}  ⟳")
                views.setOnClickPendingIntent(R.id.refresh, broadcast(context, Intent(ACTION_REFRESH), PendingIntent.FLAG_IMMUTABLE))

                val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
                for (task in filter?.select(tasks, LocalDate.now()).orEmpty()) items.addItem(
                    "${task.file}#${task.line}".hashCode().toLong(),
                    row(context, task, notes[task.file]?.path),
                )
                views.setRemoteAdapter(R.id.list, items.build())
                views.setPendingIntentTemplate(
                    R.id.list,
                    broadcast(context, Intent(), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                )

                // Empty states say what's wrong and tapping them goes where it can be fixed (W-05).
                views.setEmptyView(R.id.list, R.id.empty)
                val error = index.exceptionOrNull()
                views.setTextViewText(
                    R.id.empty,
                    error?.message ?: if (filter == null) "No filter chosen. Tap to choose one." else "No matching tasks",
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

        private fun row(context: Context, task: Task, path: String?) = RemoteViews(context.packageName, R.layout.widget_item).apply {
            setTextViewText(R.id.check, if (task.done) "☑" else "☐")
            setTextViewText(R.id.text, task.description)
            val meta = listOfNotNull(
                task.priority.emoji.ifEmpty { null },
                task.due?.let { "📅 $it" },
                "🔁".takeIf { task.recurring },
                path,
            )
            setTextViewText(R.id.meta, meta.joinToString("  "))
            if (!task.done) setOnClickFillInIntent(
                R.id.check,
                Intent().putExtra("file", task.file).putExtra("line", task.line).putExtra("text", task.text)
                    .putExtra("noteHash", task.noteHash),
            )
        }

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
            Unsupported -> toast(context, "Complete recurring tasks in Obsidian for now.")
        }
    }
}

private fun toast(context: Context, text: String) =
    Handler(Looper.getMainLooper()).post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
