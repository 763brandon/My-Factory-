package com.myfactory.forge.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.myfactory.forge.core.ai.ProviderConfig
import com.myfactory.forge.core.ai.ProviderId
import com.myfactory.forge.core.session.AuditCategory
import com.myfactory.forge.core.session.Project
import com.myfactory.forge.data.db.ForgeDatabase
import com.myfactory.forge.data.repository.ForgeRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepositoryInstrumentedTest {

    private lateinit var db: ForgeDatabase
    private lateinit var repository: ForgeRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            ForgeDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = ForgeRepository(db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun projectsRoundTrip() = runBlocking {
        val project = Project("p1", "Demo", "/data/projects/p1", 1000L, 2000L)
        repository.saveProject(project)

        assertEquals(project, repository.project("p1"))
        assertEquals(listOf(project), repository.observeProjects().first())
    }

    @Test
    fun aProviderConfigNeverPersistsTheKeyItself() = runBlocking {
        val config = ProviderConfig(
            id = "c1",
            providerId = ProviderId.ANTHROPIC,
            displayName = "Anthropic",
            baseUrl = "https://api.anthropic.com",
            model = "claude-sonnet-5",
            apiKeyAlias = "alias-1",
            extraHeaders = mapOf("X-Custom" to "value"),
        )
        repository.saveProviderConfig(config)

        val loaded = repository.providerConfig("c1")!!
        assertEquals("alias-1", loaded.apiKeyAlias)
        assertEquals(mapOf("X-Custom" to "value"), loaded.extraHeaders)

        // Prove it by inspecting the row: there is no column that could hold
        // a key, and no value in the row resembles one.
        val cursor = db.openHelper.readableDatabase
            .query("SELECT * FROM provider_configs WHERE id = 'c1'")
        cursor.use {
            assertTrue(it.moveToFirst())
            assertFalse("apiKey" in it.columnNames.toList())
            for (index in 0 until it.columnCount) {
                val value = runCatching { it.getString(index) }.getOrNull().orEmpty()
                assertFalse("a key-like value is stored", value.startsWith("sk-"))
            }
        }
    }

    @Test
    fun aProviderRowWithAnUnknownTypeIsSkippedRatherThanCrashing() = runBlocking {
        db.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO provider_configs
            (id, providerId, displayName, baseUrl, model, apiKeyAlias, maxOutputTokens,
             temperature, extraHeadersJson, toolsEnabled)
            VALUES ('legacy', 'REMOVED_PROVIDER', 'Old', 'https://x', 'm', NULL, 1024,
                    NULL, '{}', 1)
            """.trimIndent(),
        )

        assertNull(repository.providerConfig("legacy"))
        assertTrue(repository.providerConfigs().isEmpty())
    }

    @Test
    fun deletingAProjectCascadesToItsSessions() = runBlocking {
        val project = Project("p1", "Demo", "/tmp/p1", 1L, 1L)
        repository.saveProject(project)
        repository.saveSession(
            com.myfactory.forge.core.session.Session(
                id = "s1",
                projectId = "p1",
                title = "Session",
                providerConfigId = null,
                createdAtMillis = 1L,
                updatedAtMillis = 1L,
            ),
        )

        repository.deleteProject(project)

        assertTrue(repository.observeSessions("p1").first().isEmpty())
    }

    @Test
    fun theAuditLogRecordsAndReadsBack() = runBlocking {
        repository.record(
            category = AuditCategory.FILE_WRITE,
            summary = "Wrote src/App.kt",
            detail = "src/App.kt",
            projectId = "p1",
        )

        val entries = repository.observeAudit().first()
        assertEquals(1, entries.size)
        assertEquals(AuditCategory.FILE_WRITE, entries.single().category)
        assertEquals("Wrote src/App.kt", entries.single().summary)
    }

    @Test
    fun automaticCheckpointsArePrunedNewestFirst() = runBlocking {
        repository.saveProject(Project("p1", "Demo", "/tmp/p1", 1L, 1L))
        repeat(15) { index ->
            repository.saveCheckpoint(
                com.myfactory.forge.core.checkpoint.Checkpoint(
                    id = "cp$index",
                    projectId = "p1",
                    label = "Auto $index",
                    createdAtMillis = index.toLong(),
                    fileCount = 1,
                    archiveBytes = 100,
                    automatic = true,
                ),
            )
        }

        val pruned = repository.pruneAutomaticCheckpoints("p1", keep = 10)

        assertEquals(5, pruned.size)
        // The oldest go first.
        assertTrue(pruned.all { it.createdAtMillis < 5 })
        assertEquals(10, repository.observeCheckpoints("p1").first().size)
    }
}
