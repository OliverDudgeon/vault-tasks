package com.vaulttasks

import android.app.AlarmManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.app.job.JobWorkItem
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

/**
 * Rescans the vault in the background (N-06) and redraws every widget. Requests are queued as work on one job, so
 * a request made mid-scan runs another refresh afterwards instead of cancelling the scan in progress.
 */
class RefreshJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        // Exceptions from the work queue itself only come after the system has stopped the job.
        thread {
            try {
                var item = params.dequeueWork()
                while (item != null) {
                    // One refresh covers every request already queued.
                    var latest: JobWorkItem = item
                    while (true) {
                        val next = params.dequeueWork() ?: break
                        params.completeWork(latest)
                        latest = next
                    }
                    try {
                        refresh(this)
                    } catch (e: Exception) {
                        Log.w("VaultTasks", "Refresh failed", e)
                    }
                    params.completeWork(latest)
                    item = params.dequeueWork()
                }
            } catch (e: Exception) {
                Log.w("VaultTasks", "Refresh job stopped", e)
            }
        }
        return true
    }

    // Uncompleted work is redelivered when the job is rescheduled.
    override fun onStopJob(params: JobParameters) = true

    companion object {
        fun request(context: Context, expedited: Boolean = false): Boolean {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            fun enqueue(asExpedited: Boolean): Boolean = try {
                val job = JobInfo.Builder(1, ComponentName(context, RefreshJob::class.java))
                    .apply { if (asExpedited) setExpedited(true) }
                    .build()
                val queued = scheduler.enqueue(job, JobWorkItem(Intent()))
                if (queued != JobScheduler.RESULT_SUCCESS) {
                    Log.w("VaultTasks", "Couldn't queue a refresh (expedited=$asExpedited)")
                }
                queued == JobScheduler.RESULT_SUCCESS
            } catch (e: RuntimeException) {
                Log.w("VaultTasks", "Couldn't queue a refresh (expedited=$asExpedited)", e)
                false
            }
            return (expedited && enqueue(true)) || enqueue(false)
        }

        private fun refresh(context: Context) {
            val generation = TasksWidget.refreshGeneration()
            runCatching { refreshAtMidnight(context) }
                .onFailure { Log.w("VaultTasks", "Couldn't schedule the midnight refresh", it) }
            val tree = Vault.tree(context)
            val index = if (tree == null) Result.failure(Exception("Open Vault Tasks and choose a vault.")) else scan(context, tree)
            TasksWidget.renderAll(context, index, generation)
        }

        /**
         * Refreshes again just after the next local midnight, so "today" moves on (V-07). A 10-minute window needs no
         * exact-alarm permission. Non-waking: if the phone is asleep it fires when the screen next comes on.
         */
        private fun refreshAtMidnight(context: Context) {
            val midnight = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            context.getSystemService(AlarmManager::class.java)
                .setWindow(AlarmManager.RTC, midnight, 10 * 60_000L, TasksWidget.scheduledRefreshIntent(context))
        }

        private fun scan(context: Context, tree: Uri) = runCatching {
            val start = SystemClock.elapsedRealtime()
            val listing = Vault.list(context, tree)
            val listed = SystemClock.elapsedRealtime()
            val index = refreshIndex(Store.index(context), listing) { uri ->
                runCatching { Vault.read(context, Uri.parse(uri)) }
                    .onFailure { Log.w("VaultTasks", "Skipping unreadable note $uri", it) }
                    .getOrNull()
            }
            Store.saveIndex(context, index)
            val unreadable = listing.size - index.size
            val status = "${listing.size} notes, ${index.values.sumOf { it.tasks.size }} tasks" +
                (if (unreadable > 0) ", $unreadable notes unreadable (see the VaultTasks log)" else "")
            Log.i("VaultTasks", "Scanned $status (listing ${listed - start} ms, reading ${SystemClock.elapsedRealtime() - listed} ms)")
            Store.setStatus(context, "$status · scanned at ${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))}")
            index
        }.recoverCatching { e ->
            Log.w("VaultTasks", "Scan failed", e)
            Store.setStatus(context, "Scan failed: $e")
            throw Exception("Can't read the vault (${e.message}). Open Vault Tasks to choose it again.", e)
        }
    }
}
