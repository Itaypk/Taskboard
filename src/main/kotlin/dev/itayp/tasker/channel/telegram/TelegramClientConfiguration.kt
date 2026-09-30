package dev.itayp.tasker.channel.telegram

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import org.telegram.telegrambots.meta.generics.TelegramClient

@Configuration
@ConditionalOnTelegramBot
class TelegramClientConfiguration {

    @Bean
    fun telegramClient(@Value("\${tasker.telegram.bot-token}") botToken: String): TelegramClient =
        OkHttpTelegramClient(botToken)
}
