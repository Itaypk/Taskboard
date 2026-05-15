package dev.itayp.tasker.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.annotation.Order
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

// Content-Security-Policy applied to every non-actuator response. Notes on each entry:
//  - script-src telegram.org           : Telegram Login Widget script (telegram-widget.js)
//  - script-src unsafe-eval            : telegram-widget.js calls eval() internally; unavoidable for this third-party widget
//  - script-src sha256-ZswfT...        : hash of the inline <script> that bootstraps the Telegram widget on the login page
//  - frame-src oauth.telegram.org      : the iframe the widget injects for the login flow
//  - style-src 'unsafe-inline'         : React inline `style={{...}}` attributes (no nonces in our build)
//  - style-src fonts.googleapis        : the Google Fonts stylesheet linked from index.html
//  - font-src fonts.gstatic            : the actual font files referenced by that stylesheet
//  - img-src data:                     : SVG noise/mask textures used as CSS backgrounds and masks
//  - frame-ancestors 'none'            : modern equivalent of X-Frame-Options: DENY
private val CSP_POLICY = listOf(
    "default-src 'self'",
    "script-src 'self' https://telegram.org 'unsafe-eval' 'sha256-ZswfTY7H35rbv8WC7NXBoiC7WNu86vSzCDChNWwZZDM='",
    "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
    "font-src 'self' https://fonts.gstatic.com",
    "img-src 'self' data:",
    "frame-src https://oauth.telegram.org",
    "connect-src 'self'",
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
    "frame-ancestors 'none'",
    "upgrade-insecure-requests",
).joinToString("; ")

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(PrometheusAuthProperties::class, AppProperties::class)
class SecurityConfiguration(
    private val prometheusAuthProperties: PrometheusAuthProperties,
    private val appProperties: AppProperties,
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
                authorize("/api/auth/telegram", permitAll)
                authorize("/api/auth/dev-login", permitAll)
                authorize("/api/auth/demo-login", permitAll)
                authorize("/api/auth/logout", permitAll)
                authorize("/api/v1/settings/email/verify", permitAll)
                authorize("/api/**", authenticated)
                authorize("/actuator/health", permitAll)
                authorize("/actuator/health/**", permitAll)
                authorize(anyRequest, permitAll)
            }
            headers {
                // By default, Spring Security disables caching by setting Cache-Control: no-cache, no-store, max-age=0, must-revalidate and Pragma: no-cache.
                // This breaks caching of static assets, so we turn it off and rely on our own cache-control headers (defined in Nginx + Spring resource handlers).
                cacheControl { disable() }
                // Other security headers (HSTS, X-Frame-Options, X-Content-Type-Options, Referrer-Policy, etc.)
                // are set by Nginx in front of the app. CSP lives here because it depends on knowledge of
                // the app's own asset graph.
                contentSecurityPolicy {
                    policyDirectives = CSP_POLICY
                }
            }
            cors {
                    configurationSource = CorsConfigurationSource { request ->
                        // CORS is only relevant for the API endpoints, and only when accessed from a browser. In both cases, we can allow all origins.
                        if (request.requestURI.startsWith("/api/")) {
                            val config = CorsConfiguration()
                            config.allowedOrigins = listOf(
                                appProperties.baseUrl,
                                "http://localhost:63342", "http://127.0.0.1:63342",
                                "http://localhost:5173",  "http://127.0.0.1:5173",
                            )
                            config.allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                            config.allowedHeaders = listOf("*")
                            config.allowCredentials = true
                            return@CorsConfigurationSource config
                        }
                        null
                    }
            }
            csrf {
                csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse()
                csrfTokenRequestHandler = SpaCsrfTokenRequestHandler()
                ignoringRequestMatchers("/api/auth/telegram", "/api/auth/dev-login", "/api/auth/demo-login", "/api/dev/**")
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
