package dev.itayp.tasker.config

import jakarta.servlet.ServletContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.session.config.SessionRepositoryCustomizer
import org.springframework.session.jdbc.JdbcIndexedSessionRepository
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession
import org.springframework.session.web.http.CookieSerializer
import org.springframework.session.web.http.DefaultCookieSerializer
import java.time.Duration

/**
 * Explicitly enables Spring Session JDBC. Direct `@EnableJdbcHttpSession` avoids depending on
 * Spring Boot's session auto-configuration picking up the store-type from properties — that
 * wiring is less reliable across Spring Boot major versions, and without it `SessionRepositoryFilter`
 * is never registered and sessions stay in the servlet container's in-memory map.
 *
 * `spring.session.jdbc.initialize-schema: never` in application.yaml keeps Spring Session from
 * trying to create the SPRING_SESSION tables on startup — Liquibase (changeset 001) owns the schema.
 */
@Configuration
@EnableJdbcHttpSession(cleanupCron = "0 0 * * * *") // top of every hour
class SessionConfiguration {

    @Bean
    fun sessionTtlCustomizer(): SessionRepositoryCustomizer<JdbcIndexedSessionRepository> =
        SessionRepositoryCustomizer { repo ->
            repo.setDefaultMaxInactiveInterval(Duration.ofDays(30))
        }

    /**
     * The `SESSION` cookie's settings. Spring Session's own default copies only the name, domain,
     * path and max-age from the container's session-cookie config — never `secure` — so
     * `server.servlet.session.cookie.secure: true` (the `prod` profile) was silently ignored, and
     * behind a TLS-terminating proxy that talks plain HTTP to the app the cookie went out without
     * `Secure`. Same defaults otherwise (`HttpOnly`, `SameSite=Lax`).
     */
    @Bean
    fun cookieSerializer(
        servletContext: ServletContext,
        @Value("\${server.servlet.session.cookie.secure:false}") secure: Boolean,
    ): CookieSerializer = DefaultCookieSerializer().apply {
        val config = servletContext.sessionCookieConfig
        config.name?.let(::setCookieName)
        config.domain?.let(::setDomainName)
        config.path?.let(::setCookiePath)
        if (config.maxAge != -1) setCookieMaxAge(config.maxAge)
        // When false, leave it unset: the serializer then follows request.isSecure(), as before.
        if (secure) setUseSecureCookie(true)
    }
}
