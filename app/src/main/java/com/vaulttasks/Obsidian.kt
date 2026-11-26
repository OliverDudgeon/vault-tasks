package com.vaulttasks

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract

/** The Obsidian app: opening notes in it, and when it last ran, as a stand-in for when it last synced. */
object Obsidian {
    const val PACKAGE = "md.obsidian"

    /** Opens the vault [tree] at note [path] (vault-relative, with .md) if given. Obsidian names a vault after its folder. */
    fun open(tree: Uri, path: String? = null): Intent {
        val vault = DocumentsContract.getTreeDocumentId(tree).substringAfter(':').trimEnd('/').substringAfterLast('/')
        val uri = Uri.Builder().scheme("obsidian").authority("open").appendQueryParameter("vault", vault)
            .apply { if (path != null) appendQueryParameter("file", path) }.build()
        return Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun hasUsageAccess(context: Context) = context.getSystemService(AppOpsManager::class.java)
        .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED

    /** When Obsidian was last in use, or null if not in the last 4 weeks. Needs usage access. */
    fun lastUsed(context: Context): Long? {
        val now = System.currentTimeMillis()
        return context.getSystemService(UsageStatsManager::class.java)
            .queryAndAggregateUsageStats(now - 28 * 24 * 3600_000L, now)[PACKAGE]?.lastTimeUsed?.takeIf { it > 0 }
    }
}

/** What the widget can say about sync. Obsidian has no background sync on Android: it syncs only while it's open. */
sealed interface Sync {
    data object NoAccess : Sync
    data object NotRecently : Sync
    data class Synced(val at: Long) : Sync
    data class Unsynced(val notes: Int) : Sync
}

/**
 * Notes changed after Obsidian was last open (by the widget or another app) haven't been synced yet. Notes it wrote
 * itself, including those Sync downloaded, are older than [lastUsed]; [slack] covers its writes as it closes.
 */
fun syncState(hasAccess: Boolean, lastUsed: Long?, modified: Collection<Long>, slack: Long = 10_000): Sync = when {
    !hasAccess -> Sync.NoAccess
    lastUsed == null -> Sync.NotRecently
    else -> modified.count { it > lastUsed + slack }.let { if (it > 0) Sync.Unsynced(it) else Sync.Synced(lastUsed) }
}
