package com.vaulttasks

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.os.SystemClock
import android.provider.DocumentsContract.Document
import android.util.Log

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

    /** Every task in every readable note, as (vault-relative path, task). [Task.file] is the note's document URI. */
    fun scan(context: Context, tree: Uri): List<Pair<String, Task>> {
        val start = SystemClock.elapsedRealtime()
        val notes = notes(context, tree)
        val listed = SystemClock.elapsedRealtime()
        val tasks = notes.flatMap { (path, uri) ->
            val content = runCatching { read(context, uri) }
                .onFailure { Log.w("VaultTasks", "Skipping unreadable note $path", it) }
                .getOrNull() ?: return@flatMap emptyList()
            parseTasks(uri.toString(), content).map { path to it }
        }
        Log.i(
            "VaultTasks",
            "Scanned ${notes.size} notes, ${tasks.size} tasks: listing ${listed - start} ms, " +
                "reading ${SystemClock.elapsedRealtime() - listed} ms",
        )
        return tasks
    }

    // One query per folder; DocumentFile.listFiles() costs extra queries per file. Skips dot-folders (.obsidian, .trash).
    private fun notes(context: Context, tree: Uri): List<Pair<String, Uri>> {
        val notes = mutableListOf<Pair<String, Uri>>()
        fun walk(folderId: String, prefix: String) {
            val children = mutableListOf<Triple<String, String, String>>()
            context.contentResolver.query(
                DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId),
                arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE),
                null, null, null,
            )?.use { while (it.moveToNext()) children += Triple(it.getString(0), it.getString(1), it.getString(2)) }
                ?: error("Can't list /$prefix")
            for ((id, name, mime) in children) when {
                name.startsWith(".") -> {}
                mime == Document.MIME_TYPE_DIR -> walk(id, "$prefix$name/")
                name.endsWith(".md", ignoreCase = true) ->
                    notes += "$prefix$name" to DocumentsContract.buildDocumentUriUsingTree(tree, id)
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), "")
        return notes
    }

    // Strict UTF-8: a lossy decode would be written back as corrupted text.
    fun read(context: Context, note: Uri): String =
        context.contentResolver.openInputStream(note)!!.use { it.readBytes().decodeToString(throwOnInvalidSequence = true) }

    // "wt", not "w": some providers don't truncate on "w" and leave a stale tail.
    fun write(context: Context, note: Uri, content: String) =
        context.contentResolver.openOutputStream(note, "wt")!!.use { it.write(content.encodeToByteArray()) }
}
