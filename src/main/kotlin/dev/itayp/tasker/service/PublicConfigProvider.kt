package dev.itayp.tasker.service

import dev.itayp.tasker.ai.AiProperties
import dev.itayp.tasker.calendar.source.CalendarSourceProperties
import dev.itayp.tasker.config.AppProperties
import dev.itayp.tasker.config.InboundMailProperties
import dev.itayp.tasker.model.response.BrandingResponse
import dev.itayp.tasker.model.response.LoginMethodsResponse
import dev.itayp.tasker.model.response.PublicConfigResponse
import org.springframework.stereotype.Component

/**
 * The anonymous-safe instance settings the SPA needs: which login methods to offer, and the name
 * and contact addresses to show. Inlined into every `index.html` by
 * [dev.itayp.tasker.controller.IndexHtmlController] and also served as JSON by
 * [dev.itayp.tasker.controller.PublicConfigController] for scripts.
 */
@Component
class PublicConfigProvider(
    private val loginMethods: LoginMethods,
    private val appProperties: AppProperties,
    private val aiProperties: AiProperties,
    private val inboundMailProperties: InboundMailProperties,
    private val calendarSourceProperties: CalendarSourceProperties,
) {
    fun config(): PublicConfigResponse = PublicConfigResponse(
        login = LoginMethodsResponse(
            telegram = loginMethods.telegram,
            google = loginMethods.google,
            email = loginMethods.email,
            password = loginMethods.password,
            demo = loginMethods.demo,
        ),
        registrationOpen = loginMethods.registrationOpen,
        branding = BrandingResponse(
            name = appProperties.name,
            supportEmail = appProperties.supportEmail,
            abuseEmail = appProperties.abuseEmail,
        ),
        ai = aiProperties.enabled,
        aiZeroDataRetention = aiProperties.zeroDataRetentionInEffect,
        inboundMail = inboundMailProperties.active,
        // Only the planner reads these calendars, so they're offered only where it exists.
        calendarSources = calendarSourceProperties.enabled && aiProperties.enabled,
    )
}
