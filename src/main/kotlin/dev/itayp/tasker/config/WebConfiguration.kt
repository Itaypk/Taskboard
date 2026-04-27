package dev.itayp.tasker.config

import dev.itayp.tasker.interceptor.MdcUserInterceptor
import org.slf4j.LoggerFactory
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.filter.ShallowEtagHeaderFilter
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebConfiguration : WebMvcConfigurer {

    @Bean
    fun etagFilter(): FilterRegistrationBean<ShallowEtagHeaderFilter> {
        val registration = FilterRegistrationBean(ShallowEtagHeaderFilter())
        registration.addUrlPatterns("/api/v1/*")
        registration.order = 1
        logger.info("Registering ShallowEtagHeaderFilter")
        return registration
    }

    override fun addInterceptors(registry: InterceptorRegistry) {
        logger.info("Registering MdcUserInterceptor")
        registry.addInterceptor(MdcUserInterceptor())
    }

    companion object {
        private val logger = LoggerFactory.getLogger(WebConfiguration::class.java)
    }

}
