package com.example.resonant.autodownload

import com.example.resonant.data.local.entities.AutoDownloadJob
import com.example.resonant.data.local.entities.AutoDownloadJobState
import com.example.resonant.data.models.AutoDownloadPlanDTO
import com.example.resonant.data.models.Song
import com.example.resonant.managers.AutoDownloadManager
import com.example.resonant.managers.AutoDownloadSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoDownloadRunnerTest {

    private class FakeEnv : AutoDownloadEnvironment {
        var userIds = ArrayDeque<String?>()
        var fixedUser: String? = "u1"
        var settings = AutoDownloadSettings()
        var free = 50_000L * MB

        override fun currentUserId(): String? = if (userIds.isNotEmpty()) userIds.removeFirst() else fixedUser
        override suspend fun settings(userId: String) = settings
        override fun installationId() = "inst"
        override fun freeBytes() = free
        override fun nowMs() = 1_000L
    }

    private class FakeStore : AutoDownloadLocalStore {
        val jobs = mutableMapOf<String, AutoDownloadJob>()
        val downloaded = mutableSetOf<String>()
        var used = 0L
        var cleanups = 0

        override suspend fun unreported(userId: String) =
            jobs.values.filter { it.userId == userId && it.state == AutoDownloadJobState.DOWNLOADED_UNREPORTED }
        override suspend fun saveJob(job: AutoDownloadJob) { jobs[job.planId] = job }
        override suspend fun updateJobState(userId: String, planId: String, state: String, nowMs: Long) {
            jobs[planId]?.let { jobs[planId] = it.copy(state = state) }
        }
        override suspend fun isDownloaded(userId: String, songId: String) = songId in downloaded
        override suspend fun autoUsedBytes(userId: String) = used
        override suspend fun cleanup(userId: String, keepDays: Int, nowMs: Long) { cleanups++ }
    }

    private val service = FakeAutoDownloadService()
    private val env = FakeEnv()
    private val store = FakeStore()
    private val downloads = mutableListOf<String>()
    private var downloadSucceeds = true
    private val runner = AutoDownloadRunner(
        manager = AutoDownloadManager(service),
        env = env,
        store = store,
        downloader = { song, _, _, _ ->
            downloads += song.id
            if (downloadSucceeds) store.downloaded += song.id
            downloadSucceeds
        }
    )

    private fun plan(id: String, size: Long? = 5 * MB, artist: String = "X") = AutoDownloadPlanDTO(
        planId = "p$id",
        songId = "s$id",
        reasonArtistId = "a-$artist",
        reasonArtistName = artist,
        status = "pending",
        contentLength = size,
        song = Song(id = "s$id", title = "Canción $id")
    ).also { service.pendingPlans += it }

    @Test
    fun `no user makes no requests`() = runTest {
        env.fixedUser = null
        plan("1")

        assertEquals(AutoDownloadRunOutcome.NoUser, runner.run())
        assertEquals(0, service.getPlansCalls)
        assertTrue(service.results.isEmpty())
    }

    @Test
    fun `paused only resends unreported results`() = runTest {
        env.settings = AutoDownloadSettings(paused = true)
        store.jobs["old"] = job("old", AutoDownloadJobState.DOWNLOADED_UNREPORTED)
        plan("1")

        assertEquals(AutoDownloadRunOutcome.Paused, runner.run())
        assertEquals(listOf("old" to "completed"), service.results)
        assertEquals(0, service.getPlansCalls)
        assertTrue(service.claimed.isEmpty())
        assertEquals(AutoDownloadJobState.REPORTED, store.jobs.getValue("old").state)
    }

    @Test
    fun `default preferences download pending plans without enabling anything`() = runTest {
        plan("1", artist = "Rosalía")
        plan("2", artist = "Rosalía")

        val outcome = runner.run() as AutoDownloadRunOutcome.Finished

        assertEquals(mapOf("Rosalía" to 2), outcome.downloadedByArtist)
        assertEquals(listOf("p1", "p2"), service.claimed)
        assertEquals(listOf("p1" to "completed", "p2" to "completed"), service.results)
        assertTrue(store.jobs.values.all { it.state == AutoDownloadJobState.REPORTED })
    }

    @Test
    fun `user change stops the run`() = runTest {
        env.userIds = ArrayDeque<String?>(listOf("u1", "u1", "u2"))
        plan("1")
        plan("2")

        val outcome = runner.run() as AutoDownloadRunOutcome.Finished

        assertTrue(outcome.userChanged)
        assertEquals(listOf("s1"), downloads)
    }

    @Test
    fun `already downloaded song is reported without traffic`() = runTest {
        store.downloaded += "s1"
        plan("1")

        runner.run()

        assertTrue(downloads.isEmpty())
        assertTrue(service.claimed.isEmpty())
        assertEquals(listOf("p1" to "already_downloaded"), service.results)
    }

    @Test
    fun `full quota reports no space`() = runTest {
        store.used = 2040 * MB
        plan("1", size = 30 * MB)

        runner.run()

        assertTrue(downloads.isEmpty())
        assertEquals(listOf("p1" to "no_space"), service.results)
    }

    @Test
    fun `plan claimed elsewhere is skipped`() = runTest {
        plan("1")
        plan("2")
        service.claimErrors["p1"] = autoDownloadError(409, AutoDownloadManager.CODE_PLAN_CLAIMED)

        runner.run()

        assertEquals(listOf("s2"), downloads)
        assertEquals(listOf("p2" to "completed"), service.results)
    }

    @Test
    fun `network failure when reporting keeps the job and resends next run`() = runTest {
        plan("1")
        service.resultThrows = true

        runner.run()

        assertEquals(AutoDownloadJobState.DOWNLOADED_UNREPORTED, store.jobs.getValue("p1").state)

        service.resultThrows = false
        service.pendingPlans.clear()
        runner.run()

        assertEquals(listOf("p1" to "completed"), service.results)
        assertEquals(AutoDownloadJobState.REPORTED, store.jobs.getValue("p1").state)
    }

    @Test
    fun `at most twenty plans per run`() = runTest {
        repeat(35) { plan("$it", size = MB) }

        runner.run()

        assertEquals(listOf(20), service.requestedLimits)
        assertEquals(20, downloads.size)
    }

    @Test
    fun `feature disabled ends silently`() = runTest {
        service.plansError = autoDownloadError(404, "FEATURE_DISABLED")

        assertEquals(AutoDownloadRunOutcome.Disabled, runner.run())
    }

    @Test
    fun `failed download is reported as failed`() = runTest {
        downloadSucceeds = false
        plan("1")

        val outcome = runner.run() as AutoDownloadRunOutcome.Finished

        assertTrue(outcome.downloadedByArtist.isEmpty())
        assertEquals(listOf("p1" to "failed"), service.results)
    }

    @Test
    fun `cleanup runs only with keep days`() = runTest {
        runner.run()
        assertEquals(0, store.cleanups)

        env.settings = AutoDownloadSettings(keepDays = 30)
        runner.run()
        assertEquals(1, store.cleanups)
    }

    // ── Notificación ──

    @Test
    fun `notification text groups by artist`() {
        assertEquals("1 canción nueva de X descargada", AutoDownloadNotificationText.text(mapOf("X" to 1)))
        assertEquals("3 canciones nuevas de X descargadas", AutoDownloadNotificationText.text(mapOf("X" to 3)))
        assertEquals(
            "5 canciones nuevas descargadas: Y (3), X (2)",
            AutoDownloadNotificationText.text(mapOf("X" to 2, "Y" to 3))
        )
        assertEquals(null, AutoDownloadNotificationText.text(emptyMap()))
    }

    private fun job(planId: String, state: String) = AutoDownloadJob(
        userId = "u1", planId = planId, songId = "s-$planId", artistId = "a", artistName = "X",
        state = state, attempts = 1, sizeBytes = 0, updatedAtEpochMs = 0
    )

    companion object {
        private const val MB = 1024L * 1024L
    }
}
