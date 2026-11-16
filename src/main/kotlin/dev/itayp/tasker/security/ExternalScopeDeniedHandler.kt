package dev.itayp.tasker.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.web.access.AccessDeniedHandler

/**
 * A valid token whose scope doesn't cover the call. Answered as RFC 7807 with a detail naming the
 * scope that would, instead of the bare 403 Spring Security sends: an agent reading it can tell the
 * user exactly which token to create rather than retrying. Never reached without a valid token —
 * that is a 401 from the entry point.
 */
class ExternalScopeDeniedHandler : AccessDeniedHandler {

    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        val path = request.requestURI.removePrefix(request.contextPath)
        val needed = if (PLAN_PATHS.any { path.startsWith(it) }) "plan" else "write or plan"
        response.status = HttpStatus.FORBIDDEN.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        // Fixed text only (no request data), so hand-written JSON needs no escaping.
        response.writer.write(
            """{"type":"about:blank","title":"Forbidden","status":403,""" +
                """"detail":"This token's scope does not allow this call. It needs a token with scope: $needed. """ +
                """Ask the user to create one in Settings → Integrations."}""",
        )
    }

    private companion object {
        val PLAN_PATHS = listOf("/api/external/v1/plans", "/api/external/v1/events")
    }
}
