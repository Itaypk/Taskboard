package dev.itayp.tasker.controller

import dev.itayp.tasker.config.AppVersion
import dev.itayp.tasker.config.SecurityConfiguration
import dev.itayp.tasker.planning.BacklogTaskChangeService
import dev.itayp.tasker.planning.BacklogTaskWatermarkEntity
import dev.itayp.tasker.planning.PlanWatermarkService
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.BoardAccessDeniedException
import dev.itayp.tasker.service.BoardMembershipService
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.time.Instant
import java.util.UUID

@WebMvcTest(SyncController::class)
@Import(SecurityConfiguration::class, AppVersion::class)
class SyncControllerTest(@Autowired val mockMvc: MockMvc) {

    @MockitoBean lateinit var backlogTaskChangeService: BacklogTaskChangeService
    @MockitoBean lateinit var planWatermarkService: PlanWatermarkService
    @MockitoBean lateinit var boardMembershipService: BoardMembershipService
    @MockitoBean lateinit var clock: Clock

    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000099")
    private val boardId = UUID.fromString("00000000-0000-0000-0000-000000000003")
    private val path = "/api/v1/boards/$boardId/sync"

    private val auth = UsernamePasswordAuthenticationToken(
        TaskerPrincipal(userId),
        null,
        listOf(SimpleGrantedAuthority("ROLE_USER")),
    )

    @BeforeEach
    fun stubClock() {
        // Lenient: the unauthenticated / membership-denied paths never reach clock.instant().
        Mockito.lenient().`when`(clock.instant()).thenReturn(Instant.parse("2026-06-17T12:00:00Z"))
    }

    @Test
    fun `GET sync returns the per-entity watermarks and a version`() {
        val tasksAt = Instant.parse("2026-05-19T09:00:00Z")
        val tagsAt = Instant.parse("2026-05-19T09:30:00Z")
        val planAt = Instant.parse("2026-05-19T08:00:00Z")
        whenever(backlogTaskChangeService.readWatermark(boardId)).thenReturn(
            BacklogTaskWatermarkEntity().apply {
                this.boardId = this@SyncControllerTest.boardId
                this.tasksChangedAt = tasksAt
                this.tagsChangedAt = tagsAt
            }
        )
        whenever(planWatermarkService.read(userId)).thenReturn(planAt)

        mockMvc.perform(get(path).with(authentication(auth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.tasksChangedAt").value(tasksAt.toString()))
            .andExpect(jsonPath("$.tagsChangedAt").value(tagsAt.toString()))
            .andExpect(jsonPath("$.categoriesChangedAt").value(nullValue()))
            .andExpect(jsonPath("$.planChangedAt").value(planAt.toString()))
            .andExpect(jsonPath("$.appVersion").exists())
            .andExpect(jsonPath("$.checkedAt").exists())
    }

    @Test
    fun `GET sync nulls watermarks when nothing recorded`() {
        whenever(backlogTaskChangeService.readWatermark(boardId)).thenReturn(null)
        whenever(planWatermarkService.read(userId)).thenReturn(null)

        mockMvc.perform(get(path).with(authentication(auth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.tasksChangedAt").value(nullValue()))
            .andExpect(jsonPath("$.planChangedAt").value(nullValue()))
    }

    @Test
    fun `GET sync enforces board membership`() {
        whenever(boardMembershipService.requireMember(userId, boardId))
            .thenThrow(BoardAccessDeniedException(userId, boardId))

        mockMvc.perform(get(path).with(authentication(auth)))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `GET sync unauthenticated returns 401`() {
        mockMvc.perform(get(path))
            .andExpect(status().isUnauthorized)
    }
}
