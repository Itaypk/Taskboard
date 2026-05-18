package dev.itayp.tasker.config

import dev.itayp.tasker.interceptor.MdcUserInterceptor
import dev.itayp.tasker.interceptor.RateLimitInterceptor
import dev.itayp.tasker.ratelimit.InMemoryRateLimiter
import dev.itayp.tasker.ratelimit.RateLimiter
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.filter.ShallowEtagHeaderFilter
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
@EnableConfigurationProperties(RateLimitProperties::class)
class WebConfiguration(private val rateLimitProperties: RateLimitProperties) : WebMvcConfigurer {

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

    override fun addInterceptors(registry: InterceptorRegistry) {
        logger.info("Registering MdcUserInterceptor and RateLimitInterceptor")
        registry.addInterceptor(MdcUserInterceptor())
        registry.addInterceptor(RateLimitInterceptor(apiRateLimiter(), demoLoginRateLimiter()))
    }

    companion object {
        private val logger = LoggerFactory.getLogger(WebConfiguration::class.java)
    }
}
