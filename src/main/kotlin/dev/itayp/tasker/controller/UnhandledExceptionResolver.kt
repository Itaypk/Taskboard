package dev.itayp.tasker.controller

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerExceptionResolver
import org.springframework.web.servlet.ModelAndView

/**
 * Logs exceptions no other resolver handled, while the request's MDC (`user`, `ip`) is still set,
 * and turns them into a plain 500 — which then renders through [SpaErrorController] as before.
 *
 * Left alone, such an exception propagates out of the `DispatcherServlet` and Tomcat logs it, but
 * only after `MdcUserInterceptor.afterCompletion` has cleared the MDC, so the one log line that
 * matters most carries no user.
 *
 * Runs last (after `@ExceptionHandler`s, `@ResponseStatus` and Spring's standard exceptions).
 * Spring Security's exceptions are passed through: its `ExceptionTranslationFilter` turns them into
 * the 401/403 the SPA relies on.
 */
@Component
class UnhandledExceptionResolver : HandlerExceptionResolver, Ordered {

    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE

    override fun resolveException(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any?,
        ex: Exception,
    ): ModelAndView? {
        if (ex is AccessDeniedException || ex is AuthenticationException || response.isCommitted) return null
        log.error("Unhandled exception processing {} {}", request.method, request.requestURI, ex)
        response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
        return ModelAndView()
    }

    companion object {
        private val log = LoggerFactory.getLogger(UnhandledExceptionResolver::class.java)
    }
}
