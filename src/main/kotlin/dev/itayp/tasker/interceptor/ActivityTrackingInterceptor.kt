package dev.itayp.tasker.interceptor

import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.ActivityTracker
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.servlet.HandlerInterceptor

/**
 * Records authenticated API activity into [ActivityTracker] so `users.last_active_at` stays fresh
 * for inactivity-based account cleanup. Anonymous/unauthenticated requests are ignored.
 */
class ActivityTrackingInterceptor(
    private val activityTracker: ActivityTracker,
) : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val principal = SecurityContextHolder.getContext().authentication?.principal
        if (principal is TaskerPrincipal) {
            activityTracker.touch(principal.userId)
        }
        return true
    }
}
