package dev.itayp.tasker.channel.telegram

import dev.itayp.tasker.ai.access.AiAccessService
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
import dev.itayp.tasker.notification.ReminderActionHandler
import dev.itayp.tasker.planning.WeekOffset
import dev.itayp.tasker.planning.WeekResolver
import dev.itayp.tasker.planning.WeeklyPlanningOrchestrator
import dev.itayp.tasker.planning.WeeklyPlanningOrchestrator.Phase
import dev.itayp.tasker.repository.UserRepository
import dev.itayp.tasker.service.UserSettingsService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.MessageSource
import org.springframework.stereotype.Component
import org.telegram.telegrambots.longpolling.BotSession
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer
import org.telegram.telegrambots.longpolling.starter.AfterBotRegistration
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot
import org.telegram.telegrambots.longpolling.util.DefaultLongPollingUpdateConsumer
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.exceptions.TelegramApiException
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.*

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
    private val mediaExtractor: TelegramMediaExtractor,
    private val reminderActionHandler: ReminderActionHandler,
    private val aiAccessService: AiAccessService,
    private val userSettingsService: UserSettingsService,
    private val messageSource: MessageSource,
    private val telegramClient: TelegramClient,
    private val clock: Clock,
) : SpringLongPollingBot, DefaultLongPollingUpdateConsumer() {

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
                update.hasMessage() -> update.message.chatId
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
        // A photo/voice message needs the user resolved (for its locale, and for the AI opt-out
        // check) before we spend a download on it, so it's carried as a null inbound here and
        // extracted further down.
        val mediaMessage = update.message?.takeIf { !it.hasText() && mediaExtractor.carriesMedia(it) }
        val routed: Triple<Long, Long, ChannelInbound?> = when {
            update.hasMessage() && update.message.hasText() ->
                Triple(update.message.chatId, update.message.from.id, ChannelInbound.Text(update.message.text))

            mediaMessage != null ->
                Triple(mediaMessage.chatId, mediaMessage.from.id, null)

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
        val (chatId, telegramUserId, inbound) = routed

        val channel = TelegramConversationChannel(chatId, telegramClient)

        // /start is the standard first-contact command and must work before any account exists —
        // always English, since a brand-new user has no language preference yet.
        if (inbound is ChannelInbound.Text && isStartCommand(inbound.text)) {
            channel.send(ChannelMessage.Text(messageSource.getMessage("command.start", null, Locale.ENGLISH)))
            return
        }

        val user = userRepository.findByTelegramId(telegramUserId)
        if (user == null) {
            channel.send(ChannelMessage.Text("Please sign up at backlog.fyi to use the planner."))
            return
        }
        val userId = user.id!!

        if (inbound == null) {
            handleMedia(userId, chatId, channel, mediaMessage!!)
            return
        }

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

        // A tap on a slot-reminder button is self-describing (the notification id rides in the callback
        // data), so it's handled straight from the payload — ahead of the session/quick-add registries,
        // since a reminder can land mid-session.
        if (inbound is ChannelInbound.Selection &&
            reminderActionHandler.processReminderResponse(userId, channel, inbound.optionId)
        ) {
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

    /**
     * Routes a photo / voice note. Media is only accepted as part of an **in-progress quick-add**:
     * how an out-of-band attachment should behave is still an open product question, so a photo
     * that arrives on its own gets a pointer to `/add` rather than a capture we'd have to undo
     * later. That gate also means the download only happens for a user who asked for it.
     */
    private fun handleMedia(userId: UUID, chatId: Long, channel: TelegramConversationChannel, message: Message) {
        val locale = userSettingsService.getLocale(userId)
        if (!aiAccessService.isAiAvailableForUser(userId)) {
            channel.send(ChannelMessage.Text(messageSource.getMessage("command.ai_disabled", null, locale)))
            return
        }
        val quickAddState = quickAddRegistry.get(chatId)
        if (quickAddState == null) {
            channel.send(ChannelMessage.Text(messageSource.getMessage("quickadd.media.no_flow", null, locale)))
            return
        }
        val inbound = when (val extraction = mediaExtractor.extract(message)) {
            is TelegramMediaExtractor.Extraction.Media -> extraction.inbound
            is TelegramMediaExtractor.Extraction.Rejected -> {
                channel.send(ChannelMessage.Text(messageSource.getMessage(rejectionKey(extraction.reason), null, locale)))
                return
            }
            TelegramMediaExtractor.Extraction.None -> return
        }
        val next = quickAddFlow.handleInbound(userId, channel, quickAddState, inbound)
        if (next != null) quickAddRegistry.set(chatId, next) else quickAddRegistry.remove(chatId)
    }

    private fun rejectionKey(reason: TelegramMediaExtractor.Reason): String = when (reason) {
        TelegramMediaExtractor.Reason.TOO_LARGE -> "quickadd.media.too_large"
        TelegramMediaExtractor.Reason.UNSUPPORTED_TYPE -> "quickadd.media.unsupported_type"
        TelegramMediaExtractor.Reason.DOWNLOAD_FAILED -> "quickadd.media.download_failed"
    }

    /** Recognizes "/start", "/start@BotName", and deep-link forms like "/start payload". */
    private fun isStartCommand(text: String): Boolean =
        text.removePrefix("/").substringBefore(" ").substringBefore("@").lowercase() == "start"

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
        logger.info("Registered bot {}, running state is: {}", botUsername, botSession.isRunning)
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
