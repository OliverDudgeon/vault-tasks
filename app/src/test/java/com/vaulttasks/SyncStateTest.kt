package com.vaulttasks

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncStateTest {
    @Test
    fun `notes changed after Obsidian last ran are unsynced, those it wrote are not`() {
        assertEquals(Sync.Synced(1_000_000), syncState(true, 1_000_000, listOf(0, 500_000, 1_005_000)))
        assertEquals(Sync.Unsynced(2), syncState(true, 1_000_000, listOf(500_000, 1_020_000, 2_000_000)))
        assertEquals(Sync.NotRecently, syncState(true, null, listOf(1)))
        assertEquals(Sync.NoAccess, syncState(false, 1_000_000, listOf(2_000_000)))
    }
}
