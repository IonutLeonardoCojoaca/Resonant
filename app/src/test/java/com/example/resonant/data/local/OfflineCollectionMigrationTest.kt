package com.example.resonant.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Migración v6 → v7 (collection-queue-offline): explícita y solo aditiva. Las descargas existentes no se tocan.
 * La validación del esquema contra las entidades la hace Room al abrir la base de datos en el dispositivo.
 */
class OfflineCollectionMigrationTest {

    private val statements = AppDatabase.MIGRATION_6_7_STATEMENTS.map { it.uppercase() }

    @Test
    fun `migration goes from 6 to 7`() {
        assertEquals(6, AppDatabase.MIGRATION_6_7.startVersion)
        assertEquals(7, AppDatabase.MIGRATION_6_7.endVersion)
    }

    @Test
    fun `migration only creates new tables and indexes`() {
        assertTrue(statements.isNotEmpty())
        statements.forEach { sql ->
            assertTrue(sql, sql.startsWith("CREATE TABLE IF NOT EXISTS") || sql.startsWith("CREATE INDEX IF NOT EXISTS"))
            listOf("DROP", "DELETE", "ALTER", "TRUNCATE", "RENAME", "INSERT", "UPDATE").forEach { forbidden ->
                assertFalse("$forbidden en $sql", Regex("\\b$forbidden\\b").containsMatchIn(sql))
            }
            assertFalse(sql, sql.contains("DOWNLOADED_SONGS") || sql.contains("DOWNLOAD_COLLECTION"))
        }
    }

    @Test
    fun `migration creates the offline package tables with their keys`() {
        val joined = statements.joinToString("\n")
        assertTrue(joined.contains("`OFFLINE_PLAYMIX` ("))
        assertTrue(joined.contains("PRIMARY KEY(`USERID`, `PLAYMIXID`)"))
        assertTrue(joined.contains("`OFFLINE_PLAYMIX_CLIP` ("))
        assertTrue(joined.contains("PRIMARY KEY(`USERID`, `PLAYMIXID`, `TRANSITIONID`)"))
        assertTrue(joined.contains("`INDEX_OFFLINE_PLAYMIX_CLIP_USERID_PLAYMIXID`"))
        assertTrue(joined.contains("`OFFLINE_PLAYLIST` ("))
        assertTrue(joined.contains("PRIMARY KEY(`USERID`, `PLAYLISTID`)"))
    }
}
