package dev.itayp.tasker.external

import dev.itayp.tasker.config.SecurityConfiguration
import dev.itayp.tasker.model.BoardRole
import dev.itayp.tasker.model.BoardSummary
import dev.itayp.tasker.model.UserSettings
import dev.itayp.tasker.security.ApiTokenAuthenticationFilter
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.ApiTokenService
import dev.itayp.tasker.service.BacklogTaskCategoryService
import dev.itayp.tasker.service.BacklogTaskTagService
import dev.itayp.tasker.service.BoardService
import dev.itayp.tasker.service.UserSettingsService
import org.junit.jupiter.api.Test
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
import java.time.Instant
import java.util.UUID

@WebMvcTest(ExternalBoardController::class)
@Import(SecurityConfiguration::class)
class ExternalBoardControllerTest(@Autowired val mockMvc: MockMvc) {

    @MockitoBean lateinit var boardService: BoardService
    @MockitoBean lateinit var categoryService: BacklogTaskCategoryService
    @MockitoBean lateinit var tagService: BacklogTaskTagService
    @MockitoBean lateinit var userSettingsService: UserSettingsService
    @MockitoBean lateinit var apiTokenService: ApiTokenService

    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000099")
    private val boardId = UUID.fromString("00000000-0000-0000-0000-000000000003")
    private val otherBoardId = UUID.fromString("00000000-0000-0000-0000-000000000005")

    private fun auth(vararg scopes: String) = UsernamePasswordAuthenticationToken(
        TaskerPrincipal(userId),
        null,
        listOf(SimpleGrantedAuthority("ROLE_USER")) + scopes.map { SimpleGrantedAuthority(it) },
    )

    private val writeAuth = auth(
        ApiTokenAuthenticationFilter.EXTERNAL_READ,
        ApiTokenAuthenticationFilter.EXTERNAL_WRITE,
    )
    private val readAuth = auth(ApiTokenAuthenticationFilter.EXTERNAL_READ)

    private fun settings() = UserSettings(
        userId = userId,
        displayName = "Itay",
        contextBlock = null,
        timeZone = "Asia/Jerusalem",
        preferredLanguage = "en",
        calendarInviteEmail = false,
        gender = null,
        agentDescription = null,
        planningCron = null,
        weekStartDay = null,
        autoArchiveDays = null,
    )

    private fun boards() = listOf(
        BoardSummary(boardId, "Personal", BoardRole.OWNER, Instant.parse("2026-01-01T00:00:00Z"), 1, "default"),
        BoardSummary(otherBoardId, "Shared", BoardRole.MEMBER, Instant.parse("2026-02-01T00:00:00Z"), 2, "default"),
    )

    @Test
    fun `me reports identity, the user's time zone, and the default board`() {
        whenever(userSettingsService.getOrCreate(userId)).thenReturn(settings())
        whenever(boardService.listBoardsForUser(userId)).thenReturn(boards())

        mockMvc.perform(get("/api/external/v1/me").with(authentication(writeAuth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.userId").value(userId.toString()))
            .andExpect(jsonPath("$.displayName").value("Itay"))
            // The whole reason /me exists: a caller can't resolve "tomorrow" without this.
            .andExpect(jsonPath("$.timeZone").value("Asia/Jerusalem"))
            .andExpect(jsonPath("$.defaultBoardId").value(boardId.toString()))
    }

    @Test
    fun `me reports the calling token's scope`() {
        whenever(userSettingsService.getOrCreate(userId)).thenReturn(settings())
        whenever(boardService.listBoardsForUser(userId)).thenReturn(boards())

        mockMvc.perform(get("/api/external/v1/me").with(authentication(writeAuth)))
            .andExpect(jsonPath("$.tokenScope").value("write"))

        mockMvc.perform(get("/api/external/v1/me").with(authentication(readAuth)))
            .andExpect(jsonPath("$.tokenScope").value("read"))
    }

    @Test
    fun `boards marks the first as default, matching where writes land`() {
        whenever(boardService.listBoardsForUser(userId)).thenReturn(boards())

        mockMvc.perform(get("/api/external/v1/boards").with(authentication(readAuth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id").value(boardId.toString()))
            .andExpect(jsonPath("$[0].isDefault").value(true))
            .andExpect(jsonPath("$[0].role").value("owner"))
            .andExpect(jsonPath("$[1].isDefault").value(false))
            .andExpect(jsonPath("$[1].role").value("member"))
    }

    @Test
    fun `categories span every board when none is named`() {
        whenever(boardService.listBoardsForUser(userId)).thenReturn(boards())
        whenever(categoryService.getCategories(userId, boardId)).thenReturn(emptyList())
        whenever(categoryService.getCategories(userId, otherBoardId)).thenReturn(emptyList())

        mockMvc.perform(get("/api/external/v1/categories").with(authentication(readAuth)))
            .andExpect(status().isOk)
    }

    @Test
    fun `a malformed board id is a 400`() {
        mockMvc.perform(get("/api/external/v1/categories?board=not-a-uuid").with(authentication(readAuth)))
            .andExpect(status().isBadRequest)
    }
}
