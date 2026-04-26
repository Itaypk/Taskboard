package dev.itayp.tasker.config

import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.filter.ShallowEtagHeaderFilter

@Configuration
class WebConfiguration {

    @Bean
    fun etagFilter(): FilterRegistrationBean<ShallowEtagHeaderFilter> {
        val registration = FilterRegistrationBean(ShallowEtagHeaderFilter())
        registration.addUrlPatterns("/api/v1/*")
        registration.order = 1
        return registration
    }
}
