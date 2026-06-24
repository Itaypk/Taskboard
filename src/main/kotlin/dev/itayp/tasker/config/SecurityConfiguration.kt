package dev.itayp.tasker.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.annotation.Order
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler
import org.springframework.security.web.csrf.*
import org.springframework.util.StringUtils
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import java.util.function.Supplier

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(PrometheusAuthProperties::class, AppProperties::class)
class SecurityConfiguration(
    private val prometheusAuthProperties: PrometheusAuthProperties,
    private val appProperties: AppProperties,
    private val environment: Environment,
) {

    // Dev-only chain for the H2 console. Disables CSP, CSRF, and frame restrictions
    // because the H2 web app uses inline scripts and same-origin frames.
    @Bean
    @Order(0)
    @Profile("dev")
    fun h2ConsoleFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            securityMatcher("/h2-console/**")
            authorizeHttpRequests {
                authorize(anyRequest, permitAll)
            }
            csrf { disable() }
            headers {
                frameOptions { disable() }
                contentSecurityPolicy { policyDirectives = "frame-ancestors 'self'" }
            }
        }
        return http.build()
    }

    @Bean
    @Order(1)
    fun prometheusFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            securityMatcher("/actuator/prometheus")
            authorizeHttpRequests {
                authorize(anyRequest, hasRole("PROMETHEUS"))
            }
            httpBasic {}
            sessionManagement {
                sessionCreationPolicy = SessionCreationPolicy.STATELESS
            }
            csrf { disable() }
        }
        return http.build()
    }

    @Bean
    @Order(2)
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            authorizeHttpRequests {
                authorize("/", permitAll)
                authorize("/index.html", permitAll)
                authorize("/robots.txt", permitAll)
                authorize("/sitemap.xml", permitAll)
                authorize("/assets/**", permitAll)
                authorize("/favicon.ico", permitAll)
                authorize("/api/auth/me", permitAll)
                // Telegram OIDC login: the start kicks off the redirect, the callback creates the
                // session — both must be reachable unauthenticated. The link variant
                // (/api/auth/telegram/link/start) is NOT listed, so it stays authenticated.
                authorize("/api/auth/telegram/start", permitAll)
                authorize("/api/auth/telegram/callback", permitAll)
                authorize("/api/auth/dev-login", permitAll)
                authorize("/api/auth/demo-login", permitAll)
                authorize("/api/auth/email", permitAll)
                authorize("/api/auth/email/precheck", permitAll)
                authorize("/api/auth/email/callback", permitAll)
                authorize("/api/auth/logout", permitAll)
                authorize("/api/v1/settings/email/verify", permitAll)
                // Invitation preview is the side-effect-free accept-screen read; the token is the
                // authorization, so it's reachable unauthenticated. Accept (POST .../accept) is not
                // matched here and falls through to authenticated.
                authorize(HttpMethod.GET, "/api/v1/invitations/*", permitAll)
                authorize("/api/**", authenticated)
                authorize("/actuator/health", permitAll)
                authorize("/actuator/health/**", permitAll)
                authorize(anyRequest, permitAll)
            }
            headers {
                // By default, Spring Security disables caching by setting Cache-Control: no-cache, no-store, max-age=0, must-revalidate and Pragma: no-cache.
                // This breaks caching of static assets, so we turn it off and rely on our own cache-control headers (defined in Nginx + Spring resource handlers).
                cacheControl { disable() }
                // All response security headers (CSP, HSTS, X-Frame-Options, X-Content-Type-Options,
                // Referrer-Policy, Permissions-Policy) are owned by Nginx in front of the app — see the
                // `tasks` service `csp` value and security-headers.conf in the itayp_dev Ansible repo.
                // CSP in particular *must* live there: Spring's HeaderWriterFilter never runs on the
                // welcome-page / forwarded index.html responses (`/`, `/settings`, `/terms`, …), so the
                // app layer cannot protect the SPA's HTML document — the response that matters most.
                // Keeping CSP out of the app also avoids emitting a duplicate header behind Nginx.
            }
            cors {
                    val isDev = environment.acceptsProfiles(Profiles.of("dev"))
                    val allowedOrigins = buildList {
                        add(appProperties.baseUrl)
                        if (isDev) {
                            add("http://localhost:63342"); add("http://127.0.0.1:63342")
                            add("http://localhost:5173");  add("http://127.0.0.1:5173")
                        }
                    }
                    configurationSource = CorsConfigurationSource { request ->
                        // CORS is only relevant for the API endpoints, and only when accessed from a browser.
                        if (request.requestURI.startsWith("/api/")) {
                            val config = CorsConfiguration()
                            config.allowedOrigins = allowedOrigins
                            config.allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                            config.allowedHeaders = listOf("Content-Type", "Accept", "X-XSRF-TOKEN", "X-Requested-With")
                            config.allowCredentials = true
                            return@CorsConfigurationSource config
                        }
                        null
                    }
            }
            csrf {
                csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse()
                csrfTokenRequestHandler = SpaCsrfTokenRequestHandler()
                // These endpoints carry their credential in the request body (not in a session-derived
                // cookie), so CSRF protection adds nothing and would break cross-device flows.
                ignoringRequestMatchers(
                    "/api/auth/dev-login", "/api/auth/demo-login",
                    "/api/auth/email", "/api/auth/email/callback",
                    "/api/v1/settings/email/verify",
                    "/api/dev/**",
                )
            }
            sessionManagement {
                sessionFixation { newSession() }
                sessionCreationPolicy = SessionCreationPolicy.IF_REQUIRED
            }
            exceptionHandling {
                authenticationEntryPoint = HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)
            }
            logout {
                logoutUrl = "/api/auth/logout"
                logoutSuccessHandler = HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)
                invalidateHttpSession = true
                deleteCookies("SESSION", "XSRF-TOKEN")
            }
        }
        return http.build()
    }

    @Bean
    fun prometheusUserDetailsService(): UserDetailsService {
        val scraper = User.withUsername(prometheusAuthProperties.username)
            .password("{noop}${prometheusAuthProperties.password}")
            .roles("PROMETHEUS")
            .build()
        return InMemoryUserDetailsManager(scraper)
    }
}

// Forces the deferred CSRF token to load on every request so the XSRF-TOKEN cookie is
// always written (including on GETs). Without this, the cookie is never set and the
// first POST returns 403. See: https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html#csrf-integration-javascript-spa
private class SpaCsrfTokenRequestHandler : CsrfTokenRequestHandler {
    private val plain = CsrfTokenRequestAttributeHandler()
    private val xor = XorCsrfTokenRequestAttributeHandler()

    override fun handle(request: HttpServletRequest, response: HttpServletResponse, csrfToken: Supplier<CsrfToken>) {
        xor.handle(request, response, csrfToken)
        csrfToken.get()
    }

    override fun resolveCsrfTokenValue(request: HttpServletRequest, csrfToken: CsrfToken): String? {
        val headerValue = request.getHeader(csrfToken.headerName)
        return (if (StringUtils.hasText(headerValue)) plain else xor).resolveCsrfTokenValue(request, csrfToken)
    }
}
