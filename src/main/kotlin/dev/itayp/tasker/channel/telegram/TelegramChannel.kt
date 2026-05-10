package dev.itayp.tasker.channel.telegram

import dev.itayp.tasker.channel.ChannelInbound
import dev.itayp.tasker.planning.WeeklyPlanningOrchestrator
import dev.itayp.tasker.planning.WeeklyPlanningOrchestrator.Phase
import dev.itayp.tasker.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import org.telegram.telegrambots.longpolling.BotSession
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer
import org.telegram.telegrambots.longpolling.starter.AfterBotRegistration
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.exceptions.TelegramApiException
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Component
@ConditionalOnProperty(prefix = "tasker.telegram", name = ["enabled"], havingValue = "true")
class TelegramChannel(
    @Value("\${tasker.telegram.bot-username}") private val botUsername: String,
    @Value("\${tasker.telegram.bot-token}") private val botToken: String,
    private val userRepository: UserRepository,
    private val orchestrator: WeeklyPlanningOrchestrator,
) : SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private val telegramClient: TelegramClient = OkHttpTelegramClient(botToken)
    private val chatSessions = ConcurrentHashMap<Long, UUID>()

    override fun getBotToken(): String = botToken

    override fun getUpdatesConsumer(): LongPollingUpdateConsumer = this

    override fun consume(update: Update) {
        try {
            handleUpdate(update)
        } catch (e: TelegramApiException) {
            logger.error("Telegram API error handling update {}: {}", update.updateId, e.message, e)
        }
    }

    private fun handleUpdate(update: Update) {
        val (chatId, telegramUserId, inbound) = when {
            update.hasMessage() && update.message.hasText() ->
                Triple(update.message.chatId, update.message.from.id, ChannelInbound.Text(update.message.text))

            update.hasCallbackQuery() -> {
                telegramClient.execute(AnswerCallbackQuery(update.callbackQuery.id))
                Triple(
                    update.callbackQuery.message.chatId,
                    update.callbackQuery.from.id,
                    ChannelInbound.Selection(update.callbackQuery.data),
                )
            }

            else -> return
        }

        val user = userRepository.findByTelegramId(telegramUserId)
        if (user == null) {
            telegramClient.execute(
                SendMessage.builder()
                    .chatId(chatId)
                    .text("Please sign up at backlog.fyi to use the planner.")
                    .build()
            )
            return
        }
        val userId = user.id!!

        val channel = TelegramConversationChannel(chatId, telegramClient)

        val existingSessionId = chatSessions[chatId]
        if (existingSessionId == null) {
            val sessionId = orchestrator.start(userId, channel)
            chatSessions[chatId] = sessionId
            return
        }

        if (orchestrator.phase(existingSessionId) == null) {
            chatSessions.remove(chatId)
            val sessionId = orchestrator.start(userId, channel)
            chatSessions[chatId] = sessionId
            return
        }

        orchestrator.handleInbound(existingSessionId, inbound, channel)

        if (orchestrator.phase(existingSessionId) == Phase.DONE) {
            chatSessions.remove(chatId)
        }
    }

    @AfterBotRegistration
    fun afterRegistration(botSession: BotSession) {
        logger.info("Registered bot {}, running state is: {}", botUsername, botSession.isRunning())
    }

    companion object {
        private val logger = LoggerFactory.getLogger(TelegramChannel::class.java)
    }
}
