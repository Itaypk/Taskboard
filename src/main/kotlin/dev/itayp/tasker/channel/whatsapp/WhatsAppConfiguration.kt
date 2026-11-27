package dev.itayp.tasker.channel.whatsapp

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import java.net.http.HttpClient
import java.time.Duration

@Configuration
class WhatsAppConfiguration {
    @Bean
    @ConditionalOnWhatsAppCapture
    fun whatsAppClient(properties: WhatsAppProperties, objectMapper: ObjectMapper): WhatsAppClient {
        val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply { setReadTimeout(Duration.ofSeconds(15)) }
        return WhatsAppClient(properties, RestClient.builder().requestFactory(requestFactory), objectMapper)
    }
}
