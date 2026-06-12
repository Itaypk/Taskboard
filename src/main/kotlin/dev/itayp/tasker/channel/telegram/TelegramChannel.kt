package dev.itayp.tasker.channel.telegram

import dev.itayp.tasker.capture.QuickAddFlow
import dev.itayp.tasker.channel.ChannelInbound
import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.telegram.commands.BotCommandContext
import dev.itayp.tasker.channel.telegram.commands.BotCommandDispatcher
import dev.itayp.tasker.channel.telegram.commands.BotCommandHandler
import dev.itayp.tasker.channel.telegram.commands.PlanConfirmationRegistry
import dev.itayp.tasker.channel.telegram.commands.PlanConfirmationRegistry.Companion.OPTION_KEEP
import dev.itayp.tasker.channel.telegram.commands.PlanConfirmationRegistry.Companion.OPTION_NEXT_WEEK
import dev.itayp.tasker.channel.telegram.commands.PlanConfirmationRegistry.Companion.OPTION_REVISE
import dev.itayp.tasker.channel.telegram.commands.PlanConfirmationRegistry.Companion.OPTION_THIS_WEEK
import dev.itayp.tasker.planning.WeekOffset
import dev.itayp.tasker.planning.WeekResolver
import dev.itayp.tasker.planning.WeeklyPlanningOrchestrator
import dev.itayp.tasker.service.UserSettingsService
import org.springframework.context.MessageSource
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
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
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand
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
    private val commandHandlers: List<BotCommandHandler>,
    private val planConfirmationRegistry: PlanConfirmationRegistry,
    private val quickAddRegistry: QuickAddRegistry,
    private val quickAddFlow: QuickAddFlow,
    private val userSettingsService: UserSettingsService,
    private val messageSource: MessageSource,
    private val telegramClient: TelegramClient,
    private val clock: Clock,
) : SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    override fun getBotToken(): String = botToken

    override fun getUpdatesConsumer(): LongPollingUpdateConsumer = this

    override fun consume(update: Update) {
        try {
            handleUpdate(update)
        } catch (e: TelegramApiException) {
            logger.error("Telegram API error handling update {}: {}", update.updateId, e.message, e)
        } catch (e: Exception) {
            logger.error("Unhandled error handling update {}", update.updateId, e)
            notifyUpdateFailed(update)
        }
    }

    private fun notifyUpdateFailed(update: Update) {
        try {
            val chatId = when {
                update.hasMessage() && update.message.hasText() -> update.message.chatId
                update.hasCallbackQuery() -> update.callbackQuery.message.chatId
                else -> return
            }
            val locale = runCatching {
                val telegramUserId = when {
                    update.hasMessage() -> update.message.from?.id
                    update.hasCallbackQuery() -> update.callbackQuery.from?.id
                    else -> null
                }
                telegramUserId
                    ?.let { userRepository.findByTelegramId(it)?.id }
                    ?.let { userSettingsService.getLocale(it) }
            }.getOrDefault(Locale.ENGLISH)
            val text = messageSource.getMessage("command.handleUpdateFailed", null, locale)
            telegramClient.execute(SendMessage.builder().chatId(chatId).text(text).build())
        } catch (e: Exception) {
            logger.error("Failed to send error reply to user for update {}", update.updateId, e)
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
            // A new command always supersedes an in-progress quick-add capture (latest intent wins).
            quickAddRegistry.remove(chatId)
            val context = BotCommandContext(userId, chatId, "", channel, sessionRegistry)
            val handled = commandDispatcher.dispatch(inbound.text, context)
            if (!handled) {
                channel.send(ChannelMessage.Text("Unknown command. Send /help to see what I can do."))
            }
            return
        }

        // Intercept replies to the plan confirmation / week-picker choice
        val pendingConfirmation = planConfirmationRegistry.get(chatId)
        if (pendingConfirmation != null) {
            val locale = userSettingsService.getLocale(pendingConfirmation.userId)
            val selection = inbound as? ChannelInbound.Selection
            when (selection?.optionId) {
                OPTION_KEEP -> {
                    planConfirmationRegistry.remove(chatId)
                    channel.send(ChannelMessage.Text(messageSource.getMessage("planning.confirm.kept", null, locale)))
                }
                OPTION_REVISE -> {
                    planConfirmationRegistry.remove(chatId)
                    val revisableSessionId = pendingConfirmation.revisableSessionId
                    if (revisableSessionId == null) {
                        channel.send(ChannelMessage.Text(messageSource.getMessage("planning.confirm.choose", null, locale)))
                    } else {
                        orchestrator.startRevision(pendingConfirmation.userId, revisableSessionId, channel)
                        sessionRegistry.put(chatId, revisableSessionId)
                    }
                }
                OPTION_THIS_WEEK, OPTION_NEXT_WEEK -> {
                    planConfirmationRegistry.remove(chatId)
                    pendingConfirmation.existingSessionId?.let { sid ->
                        orchestrator.abandon(pendingConfirmation.userId, sid)
                        sessionRegistry.remove(chatId)
                    }
                    val weekStart = resolveWeekStartForSelection(
                        pendingConfirmation.userId,
                        pendingConfirmation.replanWeekStart,
                        selection.optionId,
                    )
                    val sessionId = orchestrator.start(pendingConfirmation.userId, channel, weekStart)
                    sessionRegistry.put(chatId, sessionId)
                }
                else -> channel.send(ChannelMessage.Text(messageSource.getMessage("planning.confirm.choose", null, locale)))
            }
            return
        }

        // Drive an in-progress quick-add ("/add") capture, if any.
        val quickAddState = quickAddRegistry.get(chatId)
        if (quickAddState != null) {
            val next = quickAddFlow.handleInbound(userId, channel, quickAddState, inbound)
            if (next != null) {
                quickAddRegistry.set(chatId, next)
            } else {
                quickAddRegistry.remove(chatId)
            }
            return
        }

        val sessionId = sessionRegistry.get(chatId)
        if (sessionId == null || orchestrator.phase(sessionId) == null) {
            sessionRegistry.remove(chatId)
            channel.send(ChannelMessage.Text("Send /add to capture a task, or /help to see what I can do."))
            return
        }

        orchestrator.handleInbound(sessionId, inbound, channel)

        if (orchestrator.phase(sessionId) == Phase.DONE) {
            sessionRegistry.remove(chatId)
        }
    }

    private fun resolveWeekStartForSelection(
        userId: java.util.UUID,
        replanWeekStart: LocalDate?,
        optionId: String,
    ): LocalDate {
        // When the user is replanning an existing session we reuse that session's week,
        // regardless of which of THIS_WEEK / NEXT_WEEK they tapped (case 1/2 only offer one
        // active "replan" option that's bound to the existing week).
        if (replanWeekStart != null) return replanWeekStart
        val settings = userSettingsService.getOrCreate(userId)
        val zone = runCatching { ZoneId.of(settings.timeZone) }.getOrDefault(ZoneId.of("UTC"))
        val today = LocalDate.now(clock.withZone(zone))
        val weekStartDay = WeekResolver.parseWeekStartDay(settings.weekStartDay)
        val offset = if (optionId == OPTION_NEXT_WEEK) WeekOffset.NEXT else WeekOffset.CURRENT
        return WeekResolver.resolveWeekStart(today, weekStartDay, offset)
    }

    @AfterBotRegistration
    fun afterRegistration(botSession: BotSession) {
        logger.info("Registered bot {}, running state is: {}", botUsername, botSession.isRunning())
        publishCommandMenu()
    }

    /**
     * Publishes the bot's command menu to Telegram so users see the available slash commands
     * in the chat UI's "/" menu. Best-effort: if the call fails (transient network, invalid
     * token), we log and continue — the bot still works without the menu.
     */
    private fun publishCommandMenu() {
        val commands = commandHandlers
            .sortedBy { it.command }
            .map { BotCommand(it.command, it.description) }
        try {
            telegramClient.execute(SetMyCommands.builder().commands(commands).build())
            logger.info("Published {} bot commands to Telegram menu", commands.size)
        } catch (e: TelegramApiException) {
            logger.warn("Failed to publish bot command menu: {}", e.message)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(TelegramChannel::class.java)
    }
}
