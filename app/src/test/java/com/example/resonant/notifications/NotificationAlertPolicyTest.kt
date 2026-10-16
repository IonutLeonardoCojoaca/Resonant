package com.example.resonant.notifications

import com.example.resonant.data.network.NotificationItemDTO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationAlertPolicyTest {

    private val now = 1_791_660_000_000L
    private val user = "u1"

    private fun item(
        id: String,
        createdAt: Long,
        alert: Boolean = true,
        read: Long? = null,
        expires: Long? = null,
        category: String = "live"
    ) = NotificationItemDTO(
        id = id,
        category = category,
        title = "t$id",
        alert = alert,
        createdAtUtcMs = createdAt,
        readAtUtcMs = read,
        expiresAtUtcMs = expires
    )

    private fun mark(createdAt: Long, vararg ids: String) = NotificationAlertMark(user, createdAt, ids.toSet())

    private fun decide(items: List<NotificationItemDTO>, mark: NotificationAlertMark?, canShow: Boolean = true) =
        NotificationAlertPolicy.decide(items, mark, user, now, canShow)

    @Test
    fun `new unread alert item after the mark is shown and moves the mark`() {
        val live = item("a", now - 60_000)

        val decision = decide(listOf(live), mark(now - 3_600_000, "old"))

        assertEquals(listOf(live), decision.toShow)
        assertEquals(mark(now - 60_000, "a"), decision.newMark)
    }

    @Test
    fun `item without alert is not shown but moves the mark`() {
        val songOfDay = item("s", now - 60_000, alert = false, category = "song_of_day")

        val decision = decide(listOf(songOfDay), mark(now - 3_600_000))

        assertTrue(decision.toShow.isEmpty())
        assertEquals(mark(now - 60_000, "s"), decision.newMark)
    }

    @Test
    fun `expired item is not shown`() {
        val expired = item("e", now - 60_000, expires = now - 1)

        assertTrue(decide(listOf(expired), mark(now - 3_600_000)).toShow.isEmpty())
    }

    @Test
    fun `read item is not shown`() {
        val read = item("r", now - 60_000, read = now - 30_000)

        assertTrue(decide(listOf(read), mark(now - 3_600_000)).toShow.isEmpty())
    }

    @Test
    fun `second run without new items shows nothing and keeps the mark`() {
        val live = item("a", now - 60_000)
        val first = decide(listOf(live), mark(now - 3_600_000))

        val second = decide(listOf(live), first.newMark)

        assertTrue(second.toShow.isEmpty())
        assertNull(second.newMark)
    }

    @Test
    fun `same millisecond sibling not yet shown is still new`() {
        val shown = item("a", now - 60_000)
        val sibling = item("b", now - 60_000)

        val decision = decide(listOf(sibling, shown), mark(now - 60_000, "a"))

        assertEquals(listOf(sibling), decision.toShow)
        assertEquals(mark(now - 60_000, "a", "b"), decision.newMark)
    }

    @Test
    fun `foreground or no permission shows nothing but still moves the mark`() {
        val live = item("a", now - 60_000)

        val decision = decide(listOf(live), mark(now - 3_600_000), canShow = false)

        assertTrue(decision.toShow.isEmpty())
        assertEquals(mark(now - 60_000, "a"), decision.newMark)
    }

    @Test
    fun `without a mark only the recent window is shown and the whole page becomes the baseline`() {
        val recent = item("new", now - 60_000)
        val backlog = item("old", now - NotificationAlertPolicy.FIRST_SYNC_LOOKBACK_MS - 1)

        val decision = decide(listOf(recent, backlog), mark = null)

        assertEquals(listOf(recent), decision.toShow)
        assertEquals(mark(now - 60_000, "new"), decision.newMark)
    }

    @Test
    fun `without a mark and only old items the baseline is still saved`() {
        val backlog = item("old", now - NotificationAlertPolicy.FIRST_SYNC_LOOKBACK_MS - 1)

        val decision = decide(listOf(backlog), mark = null)

        assertTrue(decision.toShow.isEmpty())
        assertEquals(mark(backlog.createdAtUtcMs, "old"), decision.newMark)
    }

    @Test
    fun `a mark from another user is ignored`() {
        val live = item("a", now - 60_000)
        val otherUserMark = NotificationAlertMark("u2", now, setOf("x"))

        val decision = decide(listOf(live), otherUserMark)

        assertEquals(listOf(live), decision.toShow)
        assertEquals(mark(now - 60_000, "a"), decision.newMark)
    }

    @Test
    fun `at most MAX_PER_RUN are shown, newest first`() {
        val items = (1..8).map { item("i$it", now - it * 1_000L) }

        val decision = decide(items, mark(now - 3_600_000))

        assertEquals(NotificationAlertPolicy.MAX_PER_RUN, decision.toShow.size)
        assertEquals("i1", decision.toShow.first().id)
        assertEquals(mark(now - 1_000, "i1"), decision.newMark)
    }
}
