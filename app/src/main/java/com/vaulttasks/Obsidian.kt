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
    const val ADVANCED_URI_PLUGIN = "obsidian-advanced-uri"

    /**
     * Opens the vault [tree] at note [path] (vault-relative, with .md) if given. Obsidian names a vault after its folder.
     * When [useAdvancedUri] is true and [line] (0-based) is given, uses the Advanced URI plugin to jump to that exact line;
     * otherwise uses Obsidian's built-in subpath (`#^blockId` or `#heading`) when available.
     */
    fun open(
        tree: Uri,
        path: String? = null,
        line: Int? = null,
        blockId: String? = null,
        heading: String? = null,
        useAdvancedUri: Boolean = false,
    ): Intent {
        val vault = DocumentsContract.getTreeDocumentId(tree).substringAfter(':').trimEnd('/').substringAfterLast('/')
        val link = obsidianLink(vault, path, line, blockId, heading, useAdvancedUri)
        val uri = Uri.Builder().scheme("obsidian").authority(link.action).apply {
            for ((k, v) in link.params) appendQueryParameter(k, v)
        }.build()
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

internal data class ObsidianLink(val action: String, val params: List<Pair<String, String>>)

internal fun obsidianLink(
    vault: String,
    path: String? = null,
    line: Int? = null,
    blockId: String? = null,
    heading: String? = null,
    useAdvancedUri: Boolean = false,
): ObsidianLink {
    if (path != null && line != null && useAdvancedUri) {
        return ObsidianLink(
            "advanced-uri",
            listOf("vault" to vault, "filepath" to stripMd(path), "line" to "${line + 1}"),
        )
    }
    val file = when {
        path == null -> null
        !blockId.isNullOrBlank() -> "${stripMd(path)}#^$blockId"
        !heading.isNullOrBlank() -> "${stripMd(path)}#$heading"
        else -> path
    }
    return ObsidianLink(
        "open",
        buildList {
            add("vault" to vault)
            if (file != null) add("file" to file)
        },
    )
}

private fun stripMd(path: String) = if (path.endsWith(".md", ignoreCase = true)) path.dropLast(3) else path
