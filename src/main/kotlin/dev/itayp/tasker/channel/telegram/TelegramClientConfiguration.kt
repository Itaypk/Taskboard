package dev.itayp.tasker.channel.telegram

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import org.telegram.telegrambots.meta.generics.TelegramClient

@Configuration
@ConditionalOnProperty(prefix = "tasker.telegram", name = ["enabled"], havingValue = "true")
class TelegramClientConfiguration {

    @Bean
    fun telegramClient(@Value("\${tasker.telegram.bot-token}") botToken: String): TelegramClient =
        OkHttpTelegramClient(botToken)
}
