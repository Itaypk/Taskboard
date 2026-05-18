package dev.itayp.tasker.planning

import dev.itayp.tasker.ai.tool.ToolKind
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.assertEquals

/**
 * Sanity check on tool kinds. The orchestrator dispatches by [ToolKind]; if a tool
 * silently changes kind, the rest of the flow misroutes without a build error, so
 * this test pins the contract.
 */
class PlanningToolKindsTest {

    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `say is one-way output`() {
        assertEquals(ToolKind.ONE_WAY_OUTPUT, SayTool().kind)
    }

    @Test
    fun `ask_choice is interactive input`() {
        assertEquals(ToolKind.INTERACTIVE_INPUT, AskChoiceTool().kind)
    }

    @Test
    fun `submit_plan is one-way output`() {
        val tool = SubmitPlanTool(PlanSubmissionInbox(), objectMapper)
        assertEquals(ToolKind.ONE_WAY_OUTPUT, tool.kind)
    }
}
