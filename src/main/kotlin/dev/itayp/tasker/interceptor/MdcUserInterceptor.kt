package dev.itayp.tasker.interceptor

import dev.itayp.tasker.util.clientIp
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.servlet.HandlerInterceptor

class MdcUserInterceptor : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val authentication = SecurityContextHolder.getContext().authentication
        val username = when {
            authentication != null && authentication.isAuthenticated && authentication.name != "anonymousUser" ->
                authentication.name
            else -> "anonymous"
        }
        MDC.put(USER_KEY, username)
        MDC.put(IP_KEY, request.clientIp())
        return true
    }

    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?
    ) {
        MDC.remove(USER_KEY)
        MDC.remove(IP_KEY)
    }

    companion object {
        const val USER_KEY = "user"
        const val IP_KEY = "ip"
    }
}
