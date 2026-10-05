package dev.itayp.tasker.controller

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

// Fast-path forward for known top-level SPA routes that users may bookmark or share.
// Unknown routes are handled by SpaErrorController, which intercepts 404s and forwards
// to index.html so React Router can render the appropriate page. The content pages (/terms,
// /privacy, /about, /faq) aren't here: IndexHtmlController serves them with their text rendered in.
@Controller
class SpaForwardController {

    @GetMapping("/email-login", "/email-verify", "/invite", "/settings", "/settings/**")
    fun forwardToIndex(): String = "forward:/index.html"
}
