package dev.itayp.tasker.config

import dev.itayp.tasker.interceptor.ActivityTrackingInterceptor
import dev.itayp.tasker.interceptor.MdcUserInterceptor
import dev.itayp.tasker.interceptor.RateLimitInterceptor
import dev.itayp.tasker.ratelimit.InMemoryRateLimiter
import dev.itayp.tasker.ratelimit.RateLimiter
import dev.itayp.tasker.security.AbsoluteSessionLifetimeFilter
import dev.itayp.tasker.service.ActivityTracker
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.filter.ShallowEtagHeaderFilter
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.time.Clock

@Configuration
@EnableConfigurationProperties(RateLimitProperties::class, SessionProperties::class)
class WebConfiguration(
    private val rateLimitProperties: RateLimitProperties,
    // ObjectProvider so @WebMvcTest slices (which don't load service beans) still construct this config.
    private val activityTrackerProvider: ObjectProvider<ActivityTracker>,
) : WebMvcConfigurer {

    @Bean
    fun etagFilter(): FilterRegistrationBean<ShallowEtagHeaderFilter> {
        val registration = FilterRegistrationBean(ShallowEtagHeaderFilter())
        registration.addUrlPatterns("/api/v1/*")
        registration.order = 1
        logger.info("Registering ShallowEtagHeaderFilter")
        return registration
    }

    @Bean
    fun apiRateLimiter(): RateLimiter = InMemoryRateLimiter(
        limit = rateLimitProperties.api.limit,
        windowMillis = rateLimitProperties.api.windowSeconds * 1_000,
    )

    @Bean
    fun demoLoginRateLimiter(): RateLimiter = InMemoryRateLimiter(
        limit = rateLimitProperties.demoLogin.limit,
        windowMillis = rateLimitProperties.demoLogin.windowSeconds * 1_000,
    )

    @Bean
    fun telegramLoginRateLimiter(): RateLimiter = InMemoryRateLimiter(
        limit = rateLimitProperties.telegramLogin.limit,
        windowMillis = rateLimitProperties.telegramLogin.windowSeconds * 1_000,
    )

    @Bean
    fun emailVerificationRateLimiter(): RateLimiter = InMemoryRateLimiter(
        limit = rateLimitProperties.emailVerification.limit,
        windowMillis = rateLimitProperties.emailVerification.windowSeconds * 1_000,
    )

    @Bean
    fun externalApiRateLimiter(): RateLimiter = InMemoryRateLimiter(
        limit = rateLimitProperties.externalApi.limit,
        windowMillis = rateLimitProperties.externalApi.windowSeconds * 1_000,
    )

    @Bean
    fun feedbackRateLimiter(): RateLimiter = InMemoryRateLimiter(
        limit = rateLimitProperties.feedback.limit,
        windowMillis = rateLimitProperties.feedback.windowSeconds * 1_000,
    )

    // Only registered when a Clock bean is present (i.e. full app context, not @WebMvcTest slices,
    // which don't load TimeConfiguration). The filter is non-essential for slice tests anyway —
    // they exercise individual controllers, not session lifetime.
    //
    // Order matters: one slot ahead of springSecurityFilterChain, so an
    // over-age session is invalidated before authorization runs. It still sits well behind Spring
    // Session's SessionRepositoryFilter (Integer.MIN_VALUE + 50), so getSession(false) resolves the
    // JDBC-backed session rather than a container one.
    @Bean
    @ConditionalOnBean(Clock::class)
    fun absoluteSessionLifetimeFilter(
        clock: Clock,
        sessionProperties: SessionProperties,
    ): FilterRegistrationBean<AbsoluteSessionLifetimeFilter> {
        val registration = FilterRegistrationBean(
            AbsoluteSessionLifetimeFilter(clock, sessionProperties.maxLifetime),
        )
        registration.order = SECURITY_FILTER_ORDER - 1
        logger.info("Registering AbsoluteSessionLifetimeFilter (max lifetime {})", sessionProperties.maxLifetime)
        return registration
    }

    override fun addInterceptors(registry: InterceptorRegistry) {
        logger.info("Registering MdcUserInterceptor and RateLimitInterceptor")
        registry.addInterceptor(MdcUserInterceptor())
        registry.addInterceptor(
            RateLimitInterceptor(
                apiRateLimiter(), demoLoginRateLimiter(), telegramLoginRateLimiter(), externalApiRateLimiter(),
            ),
        )
        activityTrackerProvider.ifAvailable { tracker ->
            registry.addInterceptor(ActivityTrackingInterceptor(tracker))
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(WebConfiguration::class.java)

        // Spring Boot registers springSecurityFilterChain at SecurityProperties.DEFAULT_FILTER_ORDER.
        // Inlined rather than imported: Boot 4 relocated the autoconfigure packages, and the value
        // itself is stable public API.
        private const val SECURITY_FILTER_ORDER = -100
    }
}
