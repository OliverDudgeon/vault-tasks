package com.vaulttasks

import java.io.Serializable

/** A note as listed by the vault: [uri] identifies it; [modified] is the provider's timestamp, 0 if it has none. */
data class Listed(val uri: String, val path: String, val modified: Long)

data class IndexedNote(val path: String, val modified: Long, val tasks: List<Task>) : Serializable

/**
 * The task index after [listing]: a cache keyed by note URI, rebuildable from the notes (V-04). Only notes that are
 * new or whose modified time changed are [read] (V-08); a provider with no timestamps gets a full read every time.
 * [read] returns null for a note it can't read, which is left out and retried next time.
 */
fun refreshIndex(old: Map<String, IndexedNote>, listing: List<Listed>, read: (String) -> String?): Map<String, IndexedNote> =
    listing.mapNotNull { note ->
        val cached = old[note.uri]?.takeIf { note.modified != 0L && it.modified == note.modified }
        val indexed = cached ?: read(note.uri)?.let { IndexedNote(note.path, note.modified, parseTasks(note.uri, it)) }
        indexed?.let { note.uri to it }
    }.toMap()
