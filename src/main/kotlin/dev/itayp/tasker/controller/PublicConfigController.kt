package dev.itayp.tasker.controller

import dev.itayp.tasker.model.response.PublicConfigResponse
import dev.itayp.tasker.service.PublicConfigProvider
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Public, unauthenticated: the instance settings from [PublicConfigProvider] as JSON. The SPA doesn't
 * call this — it reads the same object inlined into `index.html` ([IndexHtmlController]) — but it's
 * a stable way for scripts and health checks to see which login methods an instance offers.
 */
@RestController
class PublicConfigController(private val publicConfigProvider: PublicConfigProvider) {

    @GetMapping("/api/public/config")
    fun config(): PublicConfigResponse = publicConfigProvider.config()
}
