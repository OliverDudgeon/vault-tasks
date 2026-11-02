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

/** Lists every open task in the vault. ponytail: one unfiltered list; per-widget saved filters are Phase 1. */
class TasksWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) =
        inBackground { render(context, manager, ids) }

    companion object {
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            render(context, manager, manager.getAppWidgetIds(ComponentName(context, TasksWidget::class.java)))
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val tree = Vault.tree(context)
            val (tasks, emptyText) = when (tree) {
                null -> emptyList<Pair<String, Task>>() to "Open Vault Tasks and choose a vault"
                else -> try {
                    Vault.scan(context, tree).filter { it.second.isOpen } to "No open tasks"
                } catch (e: Exception) {
                    Log.w("VaultTasks", "Scan failed", e)
                    emptyList<Pair<String, Task>>() to "Can't read vault (${e.message}). Open Vault Tasks to choose it again."
                }
            }
            val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
            for ((path, task) in tasks) {
                val item = RemoteViews(context.packageName, R.layout.widget_item)
                item.setTextViewText(R.id.text, task.text.substringAfter("] "))
                item.setTextViewText(R.id.path, "$path:${task.line + 1}")
                item.setOnClickFillInIntent(
                    R.id.item,
                    Intent().putExtra("file", task.file).putExtra("line", task.line).putExtra("text", task.text),
                )
                items.addItem("${task.file}#${task.line}".hashCode().toLong(), item)
            }
            val views = RemoteViews(context.packageName, R.layout.widget)
            views.setRemoteAdapter(R.id.list, items.build())
            views.setEmptyView(R.id.list, R.id.empty)
            views.setTextViewText(R.id.empty, emptyText)
            views.setPendingIntentTemplate(
                R.id.list,
                PendingIntent.getBroadcast(
                    context, 0, Intent(context, WidgetActionReceiver::class.java),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            views.setOnClickPendingIntent(
                R.id.refresh,
                PendingIntent.getBroadcast(
                    context, 0, Intent(context, WidgetActionReceiver::class.java).setAction(ACTION_REFRESH),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            manager.updateAppWidget(ids, views)
        }
    }
}

private const val ACTION_REFRESH = "com.vaulttasks.REFRESH"

/** Widget taps: complete a task, or refresh. Not exported, so only our own PendingIntents can make it write. */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = inBackground {
        if (intent.action != ACTION_REFRESH) try {
            complete(context, intent)
        } catch (e: Exception) {
            Log.w("VaultTasks", "Complete failed", e)
            toast(context, "Couldn't complete the task: ${e.message}")
        }
        TasksWidget.refreshAll(context)
    }

    private fun complete(context: Context, intent: Intent) {
        val task = Task(intent.getStringExtra("file")!!, intent.getIntExtra("line", -1), intent.getStringExtra("text")!!)
        val note = Uri.parse(task.file)
        val result = completeInNote(task, LocalDate.now(), { Vault.read(context, note) }, { Vault.write(context, note, it) })
        Log.i("VaultTasks", "Complete line ${task.line + 1}: ${result::class.simpleName}")
        when (result) {
            is Completed -> {}
            Conflict -> toast(context, "Note changed since the last scan, or the task appears twice. Nothing was written.")
            Unsupported -> toast(context, "Complete recurring tasks in Obsidian for now.")
        }
    }
}

// ponytail: goAsync gives ~10 s, and every refresh rescans the whole vault; move to WorkManager plus a cached
// index (N-06) once real vaults get near that.
private fun BroadcastReceiver.inBackground(work: () -> Unit) {
    val pending = goAsync()
    thread {
        try {
            work()
        } finally {
            pending.finish()
        }
    }
}

private fun toast(context: Context, text: String) =
    Handler(Looper.getMainLooper()).post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
