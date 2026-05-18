package dev.itayp.tasker.planning

import dev.itayp.tasker.planning.dto.AgreedPlan
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class PlanSubmissionInboxTest {

    private val inbox = PlanSubmissionInbox()

    @Test
    fun `record before begin is silently dropped`() {
        inbox.record(AgreedPlan(emptyList(), "x"))
        assertEquals(0, inbox.drain().size)
    }

    @Test
    fun `begin then record then drain returns recorded items and clears the bucket`() {
        inbox.begin()
        inbox.record(AgreedPlan(emptyList(), "first"))
        inbox.record(AgreedPlan(emptyList(), "second"))

        val drained = inbox.drain()
        assertEquals(2, drained.size)
        assertEquals(listOf("first", "second"), drained.map { it.summary })

        // Second drain returns empty (bucket cleared).
        assertEquals(0, inbox.drain().size)
    }
}
