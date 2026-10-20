package dev.itayp.tasker.controller

import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class UnhandledExceptionResolverTest {

    private val resolver = UnhandledExceptionResolver()
    private val request = MockHttpServletRequest("POST", "/api/v1/planning/sessions")
    private val response = MockHttpServletResponse()

    @Test
    fun `an unhandled exception becomes a 500 instead of escaping to the container`() {
        val result = resolver.resolveException(request, response, null, IllegalStateException("boom"))

        assertNotNull(result)
        assertEquals(500, response.status)
    }

    @Test
    fun `spring security exceptions pass through to the security filter chain`() {
        assertNull(resolver.resolveException(request, response, null, AccessDeniedException("no")))
        assertNull(resolver.resolveException(request, response, null, BadCredentialsException("no")))
        assertEquals(200, response.status)
    }

    @Test
    fun `a committed response is left alone`() {
        response.setCommitted(true)

        assertNull(resolver.resolveException(request, response, null, IllegalStateException("boom")))
    }
}
