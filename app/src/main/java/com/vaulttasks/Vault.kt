package com.vaulttasks

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.io.IOException

/** The SAF-granted vault folder. Notes are read and written in place; nothing is copied. */
object Vault {
    private const val KEY = "tree"

    private fun prefs(context: Context) = context.getSharedPreferences("vault", Context.MODE_PRIVATE)

    fun tree(context: Context): Uri? = prefs(context).getString(KEY, null)?.let(Uri::parse)

    fun choose(context: Context, tree: Uri) {
        context.contentResolver.takePersistableUriPermission(
            tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        prefs(context).edit().putString(KEY, tree.toString()).apply()
    }

    /** Every note in the vault, with the provider's modified time for the incremental index. */
    // One query per folder; DocumentFile.listFiles() costs extra queries per file. Skips dot-folders (.obsidian, .trash).
    fun list(context: Context, tree: Uri): List<Listed> {
        val notes = mutableListOf<Listed>()
        fun walk(folderId: String, prefix: String) {
            val folders = mutableListOf<Pair<String, String>>()
            context.contentResolver.query(
                DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId),
                arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED),
                null, null, null,
            )?.use {
                while (it.moveToNext()) {
                    val (id, name) = it.getString(0) to it.getString(1)
                    when {
                        name.startsWith(".") -> {}
                        it.getString(2) == Document.MIME_TYPE_DIR -> folders += id to "$prefix$name/"
                        name.endsWith(".md", ignoreCase = true) -> notes += Listed(
                            DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), "$prefix$name",
                            if (it.isNull(3)) 0 else it.getLong(3),
                        )
                    }
                }
            } ?: error("Can't list /$prefix")
            for ((id, path) in folders) walk(id, path)
        }
        walk(DocumentsContract.getTreeDocumentId(tree), "")
        return notes
    }

    // Strict UTF-8: a lossy decode would be written back as corrupted text.
    fun read(context: Context, note: Uri): String =
        context.contentResolver.openInputStream(note)!!.use { it.readBytes().decodeToString(throwOnInvalidSequence = true) }

    /**
     * Replaces [note]'s text [before] with [after]. "wt", not "w": some providers don't truncate on "w" and leave a
     * stale tail. But "wt" truncates first, so a write that fails partway would lose the note: [before] is kept in
     * app storage until [after] reads back intact. If the write throws, [before] is put back. If the note merely
     * reads back different, that may be a sync edit landing just after ours, so it is left alone and the copy is
     * kept. A copy left behind is listed in the app ([interruptedWrites]).
     */
    fun write(context: Context, note: Uri, before: String, after: String) {
        val backup = File(context.filesDir, "$BACKUP${System.currentTimeMillis()}-${note.lastPathSegment?.replace(Regex("[^\\w.-]"), "_")}")
        backup.writeText(before)
        try {
            overwrite(context, note, after)
        } catch (e: Exception) {
            val restored = runCatching { overwrite(context, note, before); read(context, note) == before }.getOrDefault(false)
            if (restored) backup.delete()
            throw IOException(if (restored) "Write failed, so the note was put back" else "Write failed. The original note is saved as $backup", e)
        }
        if (runCatching { read(context, note) }.getOrNull() != after) {
            throw IOException("The note changed as it was written. Check it in Obsidian; the original is saved as $backup")
        }
        backup.delete()
    }

    private fun overwrite(context: Context, note: Uri, content: String) =
        context.contentResolver.openOutputStream(note, "wt")!!.use { it.write(content.encodeToByteArray()) }

    fun interruptedWrites(context: Context): List<File> = context.filesDir.listFiles { f -> f.name.startsWith(BACKUP) }.orEmpty().toList()

    /** True if the vault's `.obsidian/community-plugins.json` lists [pluginId] as enabled. */
    fun hasObsidianPlugin(context: Context, tree: Uri, pluginId: String): Boolean = runCatching {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        fun childId(parentId: String, name: String): String? =
            context.contentResolver.query(
                DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId),
                arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) if (c.getString(1) == name) return@use c.getString(0)
                null
            }
        val obsidianDir = childId(rootId, ".obsidian") ?: return@runCatching false
        val pluginsDoc = childId(obsidianDir, "community-plugins.json") ?: return@runCatching false
        val json = read(context, DocumentsContract.buildDocumentUriUsingTree(tree, pluginsDoc))
        Regex(""""${Regex.escape(pluginId)}"""").containsMatchIn(json)
    }.getOrDefault(false)

    private const val BACKUP = "backup-"
}
