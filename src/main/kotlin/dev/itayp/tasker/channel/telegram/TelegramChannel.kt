package dev.itayp.tasker.channel.telegram

import dev.itayp.tasker.channel.ChannelInbound
import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.telegram.commands.BotCommandContext
import dev.itayp.tasker.channel.telegram.commands.BotCommandDispatcher
import dev.itayp.tasker.channel.telegram.commands.PlanConfirmationRegistry
import dev.itayp.tasker.channel.telegram.commands.PlanConfirmationRegistry.Companion.OPTION_KEEP
import dev.itayp.tasker.channel.telegram.commands.PlanConfirmationRegistry.Companion.OPTION_NEW
import dev.itayp.tasker.planning.WeeklyPlanningOrchestrator
import dev.itayp.tasker.service.UserSettingsService
import org.springframework.context.MessageSource
import java.util.Locale
import dev.itayp.tasker.planning.WeeklyPlanningOrchestrator.Phase
import dev.itayp.tasker.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
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

@Component
@ConditionalOnProperty(prefix = "tasker.telegram", name = ["enabled"], havingValue = "true")
class TelegramChannel(
    @Value("\${tasker.telegram.bot-username}") private val botUsername: String,
    @Value("\${tasker.telegram.bot-token}") private val botToken: String,
    private val userRepository: UserRepository,
    private val orchestrator: WeeklyPlanningOrchestrator,
    private val sessionRegistry: TelegramSessionRegistry,
    private val commandDispatcher: BotCommandDispatcher,
    private val planConfirmationRegistry: PlanConfirmationRegistry,
    private val userSettingsService: UserSettingsService,
    private val messageSource: MessageSource,
    private val telegramClient: TelegramClient,
) : SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

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

        if (inbound is ChannelInbound.Text && inbound.text.startsWith("/")) {
            val context = BotCommandContext(userId, chatId, "", channel, sessionRegistry)
            val handled = commandDispatcher.dispatch(inbound.text, context)
            if (!handled) {
                channel.send(ChannelMessage.Text("Unknown command. Use /plan to start a planning session."))
            }
            return
        }

        // Intercept replies to the "keep vs. redo" plan confirmation choice
        val pendingConfirmation = planConfirmationRegistry.get(chatId)
        if (pendingConfirmation != null) {
            val locale = userLocale(pendingConfirmation.userId)
            when {
                inbound is ChannelInbound.Selection && inbound.optionId == OPTION_KEEP -> {
                    planConfirmationRegistry.remove(chatId)
                    channel.send(ChannelMessage.Text(messageSource.getMessage("planning.confirm.kept", null, locale)))
                }
                inbound is ChannelInbound.Selection && inbound.optionId == OPTION_NEW -> {
                    planConfirmationRegistry.remove(chatId)
                    pendingConfirmation.existingSessionId?.let { sid ->
                        orchestrator.abandon(pendingConfirmation.userId, sid)
                        sessionRegistry.remove(chatId)
                    }
                    val sessionId = orchestrator.start(pendingConfirmation.userId, channel)
                    sessionRegistry.put(chatId, sessionId)
                }
                else -> channel.send(ChannelMessage.Text(messageSource.getMessage("planning.confirm.choose", null, locale)))
            }
            return
        }

        val sessionId = sessionRegistry.get(chatId)
        if (sessionId == null || orchestrator.phase(sessionId) == null) {
            sessionRegistry.remove(chatId)
            channel.send(ChannelMessage.Text("Use /plan to start a planning session."))
            return
        }

        orchestrator.handleInbound(sessionId, inbound, channel)

        if (orchestrator.phase(sessionId) == Phase.DONE) {
            sessionRegistry.remove(chatId)
        }
    }

    private fun userLocale(userId: java.util.UUID): Locale {
        val lang = userSettingsService.getOrCreate(userId).preferredLanguage
        return Locale.forLanguageTag(lang)
    }

    @AfterBotRegistration
    fun afterRegistration(botSession: BotSession) {
        logger.info("Registered bot {}, running state is: {}", botUsername, botSession.isRunning())
    }

    companion object {
        private val logger = LoggerFactory.getLogger(TelegramChannel::class.java)
    }
}
