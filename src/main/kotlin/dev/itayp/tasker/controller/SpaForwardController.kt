package dev.itayp.tasker.controller

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

// Forwards any extensionless path to index.html so React Router handles routing,
// including rendering the 404 page. Static assets (*.js, *.css …) and /api/** routes
// are matched before these mappings and are not affected.
@Controller
class SpaForwardController {

    @GetMapping("/{path:[^.]*}")
    fun forwardSingleSegment(): String = "forward:/index.html"

    @GetMapping("/**/{path:[^.]*}")
    fun forwardNestedSegment(): String = "forward:/index.html"
}
