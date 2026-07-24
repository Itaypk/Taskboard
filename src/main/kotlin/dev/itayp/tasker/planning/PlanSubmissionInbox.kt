package dev.itayp.tasker.planning

import dev.itayp.tasker.planning.dto.AgreedPlan
import org.springframework.stereotype.Component

/**
 * Bridges the stateless [dev.itayp.nescioquid.openrouter.tool.AiTool] callback API and the orchestrator
 * that wraps a single `sendMessage` invocation. The orchestrator opens a thread-local
 * collection bucket before each AI turn, [SubmitPlanTool] drops parsed payloads into it,
 * and the orchestrator drains it after the turn finishes. Cleared automatically on drain.
 */
@Component
class PlanSubmissionInbox {

    private val bucket = ThreadLocal<MutableList<AgreedPlan>>()

    fun begin() {
        bucket.set(mutableListOf())
    }

    fun record(plan: AgreedPlan) {
        bucket.get()?.add(plan)
    }

    fun drain(): List<AgreedPlan> {
        val current = bucket.get() ?: return emptyList()
        bucket.remove()
        return current.toList()
    }
}
