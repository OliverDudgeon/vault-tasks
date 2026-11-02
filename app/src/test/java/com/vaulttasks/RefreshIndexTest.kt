package com.vaulttasks

import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshIndexTest {
    private val notes = mutableMapOf(
        "uri:a" to "- [ ] Alpha",
        "uri:b" to "- [ ] Beta",
    )
    private val reads = mutableListOf<String>()
    private val read = { uri: String -> reads += uri; notes[uri] }

    private val listing = listOf(Listed("uri:a", "A.md", 100), Listed("uri:b", "Sub/B.md", 100))

    @Test
    fun `reads every note the first time`() {
        val index = refreshIndex(emptyMap(), listing, read)

        assertEquals(listOf("uri:a", "uri:b"), reads)
        assertEquals(listOf("Alpha", "Beta"), index.values.flatMap { it.tasks }.map { it.description })
        assertEquals("Sub/B.md", index.getValue("uri:b").path)
    }

    @Test
    fun `re-reads only notes whose modified time changed, and forgets deleted ones`() {
        val old = refreshIndex(emptyMap(), listing, read)
        reads.clear()
        notes["uri:a"] = "- [ ] Alpha edited"
        notes["uri:c"] = "- [ ] Gamma"

        val index = refreshIndex(old, listOf(Listed("uri:a", "A.md", 200), Listed("uri:c", "C.md", 100)), read)

        assertEquals(listOf("uri:a", "uri:c"), reads)
        assertEquals(listOf("Alpha edited", "Gamma"), index.values.flatMap { it.tasks }.map { it.description })
    }

    @Test
    fun `always re-reads notes whose provider reports no modified time`() {
        val unknown = listOf(Listed("uri:a", "A.md", 0))
        val old = refreshIndex(emptyMap(), unknown, read)

        refreshIndex(old, unknown, read)

        assertEquals(listOf("uri:a", "uri:a"), reads)
    }

    @Test
    fun `leaves out a note it can't read, so the next refresh tries it again`() {
        val index = refreshIndex(emptyMap(), listing + Listed("uri:gone", "Gone.md", 100), read)

        assertEquals(setOf("uri:a", "uri:b"), index.keys)
    }
}
