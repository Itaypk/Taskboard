package dev.itayp.tasker.planning

import org.springframework.stereotype.Component
import java.time.ZoneId
import java.util.UUID

/**
 * Bridges the stateless [dev.itayp.nescioquid.openrouter.tool.AiTool] callback API and the per-turn user
 * context the orchestrator owns. Mirrors [PlanSubmissionInbox]: the orchestrator opens a
 * thread-local scope before dispatching a turn's tool calls, the `DATA_LOOKUP` tools
 * ([FindTaskTool], [SuggestTaskTool], [CreateTaskTool]) read the current [userId]/[zone] from it,
 * and the orchestrator clears it once the turn finishes.
 */
@Component
class PlanningToolContext {

    data class Scope(val userId: UUID, val zone: ZoneId)

    private val scope = ThreadLocal<Scope>()

    fun begin(userId: UUID, zone: ZoneId) {
        scope.set(Scope(userId, zone))
    }

    /** The active scope, or null if no planning turn is in flight on this thread. */
    fun current(): Scope? = scope.get()

    /** The active user id; throws if a tool is somehow invoked outside a planning turn. */
    fun requireUserId(): UUID =
        scope.get()?.userId ?: error("No active planning tool context on this thread")

    fun clear() {
        scope.remove()
    }
}
