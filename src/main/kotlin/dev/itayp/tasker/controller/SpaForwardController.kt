package dev.itayp.tasker.controller

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

/**
 * SPA fallback for client-side routes that don't correspond to real static files.
 * Forwards to /index.html so the React router can take over after the bundle loads.
 *
 * Add a new mapping here for each top-level SPA route that users may visit directly
 * (i.e. by typing the URL or following an external link). Routes scoped under "/" are
 * already handled by Spring's static resource serving.
 */
@Controller
class SpaForwardController {

    @GetMapping("/terms", "/privacy")
    fun forwardToIndex(): String = "forward:/index.html"
}
