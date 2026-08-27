package dev.itayp.tasker.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.session.config.SessionRepositoryCustomizer
import org.springframework.session.jdbc.JdbcIndexedSessionRepository
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession
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
}
