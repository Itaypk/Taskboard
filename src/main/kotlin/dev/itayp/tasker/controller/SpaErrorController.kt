package dev.itayp.tasker.controller

import jakarta.servlet.RequestDispatcher
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.web.error.ErrorAttributeOptions
import org.springframework.boot.webmvc.error.ErrorAttributes
import org.springframework.boot.webmvc.error.ErrorController
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.context.request.ServletWebRequest

// Replaces Spring Boot's BasicErrorController to give the SPA a chance to render
// its own 404 page for browser navigation, while preserving JSON error responses
// for API clients.
@Controller
@RequestMapping("/error")
class SpaErrorController(private val errorAttributes: ErrorAttributes) : ErrorController {

    // Browser navigation (Accept: text/html).
    // 5xx → static error.html (no JS, no API calls — works even when the app is broken).
    // Everything else → index.html so React Router renders the appropriate page (e.g. NotFoundPage).
    @RequestMapping(produces = [MediaType.TEXT_HTML_VALUE])
    fun errorHtml(request: HttpServletRequest): String {
        val status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) as? Int ?: 0
        return if (status >= 500) "forward:/error.html" else "forward:/index.html"
    }

    // API and programmatic clients — return Spring Boot's standard JSON error body.
    @RequestMapping
    @ResponseBody
    fun error(request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<Map<String, Any?>> {
        val statusCode = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) as? Int ?: response.status
        val status = HttpStatus.resolve(statusCode) ?: HttpStatus.INTERNAL_SERVER_ERROR
        val attrs = errorAttributes.getErrorAttributes(ServletWebRequest(request), ErrorAttributeOptions.defaults())
        return ResponseEntity.status(status).body(attrs)
    }
}
