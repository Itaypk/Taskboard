package dev.itayp.tasker.controller

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

/**
 * SPA fallback: forwards any path that looks like a client-side route (no file extension)
 * to /index.html so React Router can render the correct page, including the 404 view.
 *
 * The regex [^\\.] excludes dots, so static assets (*.js, *.css, *.png …) fall through
 * to Spring's resource handler. /api/** is matched by @RestController beans first and
 * never reaches here.
 */
@Controller
class SpaForwardController {

    @GetMapping("/{path:[^\\\\.]*}")
    fun forwardSingleSegment(): String = "forward:/index.html"

    @GetMapping("/**/{path:[^\\\\.]*}")
    fun forwardNestedSegment(): String = "forward:/index.html"
}
