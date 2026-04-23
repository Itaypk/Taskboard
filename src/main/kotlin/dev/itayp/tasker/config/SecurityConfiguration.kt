package dev.itayp.tasker.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler
import org.springframework.security.web.csrf.CsrfTokenRequestHandler
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler
import org.springframework.util.StringUtils
import java.util.function.Supplier
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(PrometheusAuthProperties::class)
class SecurityConfiguration(
    private val prometheusAuthProperties: PrometheusAuthProperties,
) {

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
                authorize("/assets/**", permitAll)
                authorize("/favicon.ico", permitAll)
                authorize("/api/auth/telegram", permitAll)
                authorize("/api/auth/dev-login", permitAll)
                authorize("/api/auth/logout", permitAll)
                authorize("/api/**", authenticated)
                authorize("/actuator/health", permitAll)
                authorize("/actuator/health/**", permitAll)
                authorize(anyRequest, permitAll)
            }
            csrf {
                csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse()
                csrfTokenRequestHandler = SpaCsrfTokenRequestHandler()
                ignoringRequestMatchers("/api/auth/telegram", "/api/auth/dev-login")
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
                deleteCookies("JSESSIONID", "XSRF-TOKEN")
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
