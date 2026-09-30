package dev.itayp.tasker.controller

import dev.itayp.tasker.model.response.LoginMethodsResponse
import dev.itayp.tasker.model.response.PublicConfigResponse
import dev.itayp.tasker.service.LoginMethods
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Public, unauthenticated: the instance settings the anonymous SPA needs before anyone signs in —
 * which login options to render. Runtime rather than `VITE_*` build-time values, so one published
 * build (or container image) serves any instance's configuration.
 */
@RestController
class PublicConfigController(private val loginMethods: LoginMethods) {

    @GetMapping("/api/public/config")
    fun config(): PublicConfigResponse = PublicConfigResponse(
        login = LoginMethodsResponse(
            telegram = loginMethods.telegram,
            email = loginMethods.email,
            password = loginMethods.password,
            demo = loginMethods.demo,
        ),
        registrationOpen = loginMethods.registrationOpen,
    )
}
