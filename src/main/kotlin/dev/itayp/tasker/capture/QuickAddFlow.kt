package dev.itayp.tasker.capture

import dev.itayp.tasker.channel.AttachmentKind
import dev.itayp.tasker.channel.InboundAttachment
import dev.itayp.tasker.channel.MessageFormatter
import dev.itayp.tasker.channel.ChannelInbound
import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.ChoiceOption
import dev.itayp.tasker.channel.ConversationChannel
import dev.itayp.tasker.model.BacklogTaskCategory
import dev.itayp.tasker.model.TaskPriority
import dev.itayp.tasker.oneoff.OneOffEventDraft
import dev.itayp.tasker.oneoff.OneOffEventService
import dev.itayp.tasker.planning.CapturedItem
import dev.itayp.tasker.planning.ClarifyOption
import dev.itayp.tasker.planning.ClarificationExchange
import dev.itayp.tasker.planning.EventDraft
import dev.itayp.tasker.planning.PlanFinalizationService
import dev.itayp.tasker.planning.PlanningSessionService
import dev.itayp.tasker.planning.SuggestionOutcome
import dev.itayp.tasker.planning.TaskDraft
import dev.itayp.tasker.planning.TaskSuggestionAgent
import dev.itayp.tasker.planning.UnsupportedModalityException
import dev.itayp.tasker.planning.dto.AgreedPlanTask
import dev.itayp.tasker.planning.dto.AgreedTimeSlot
import dev.itayp.tasker.planning.toCreateBacklogTaskRequest
import dev.itayp.tasker.ratelimit.RateLimiter
import dev.itayp.tasker.service.BacklogTaskCategoryService
import dev.itayp.tasker.service.BacklogTaskService
import dev.itayp.tasker.service.BoardMembershipService
import dev.itayp.tasker.service.UserSettingsService
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.MessageSource
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID

/**
 * Channel-agnostic "/add" quick-capture flow. Drafts one or more items (tasks and/or one-off
 * calendar events) from a free-text request, shows a confirmation card with Save / Adjust /
 * Cancel, folds free-text adjustments back into a revised draft, and — for genuinely vague input —
 * surfaces up to [MAX_CLARIFY_ROUNDS] bounded clarifying questions before forcing a best-guess
 * draft. Every model call is a single-turn sub-agent call (draft / revise); the turn-taking here
 * is a deterministic state machine, not an LLM conversation.
 *
 * The flow holds no state of its own: callers pass the current [QuickAddState] in and store the
 * returned one (or clear it when null is returned). A channel-specific registry owns that storage
 * and the idle TTL. Renders happen through the supplied [ConversationChannel], so the same flow
 * can back Telegram today and a web surface later.
 */
@Service
class QuickAddFlow(
    private val suggestionAgent: TaskSuggestionAgent,
    private val backlogTaskService: BacklogTaskService,
    private val oneOffEventService: OneOffEventService,
    private val boardMembershipService: BoardMembershipService,
    private val categoryService: BacklogTaskCategoryService,
    private val userSettingsService: UserSettingsService,
    private val planningSessionService: PlanningSessionService,
    private val planFinalizationService: PlanFinalizationService,
    private val messageSource: MessageSource,
    private val meterRegistry: MeterRegistry,
    @Qualifier("quickAddRateLimiter") private val rateLimiter: RateLimiter,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(QuickAddFlow::class.java)

    /** Opens a flow. [description] is the inline text after `/add`, or null/blank for a bare `/add`. */
    fun begin(userId: UUID, channel: ConversationChannel, description: String?): QuickAddState? {
        val request = description?.trim().orEmpty()
        if (request.isBlank()) {
            // No model call yet, so nothing to charge against the limit — the description that
            // follows goes through onDescription, which is already inside the flow.
            channel.send(ChannelMessage.Text(msg(userId, "quickadd.prompt.describe")))
            return QuickAddState.AwaitingDescription(now())
        }
        countEntry("command")
        return draft(userId, channel, request, unprompted = false).stateOrNull()
    }

    /**
     * Opens a flow from a message the user sent on their own — no `/add` in front of it. Behaves
     * exactly like [begin] when the message is a capture; when it plainly isn't one, the model says
     * so and this returns [CaptureEntry.Routed] for the channel to dispatch
     * (`docs/FREE-TEXT-CAPTURE.md` D1–D3). That third reply shape is offered **only** here.
     */
    fun beginUnprompted(userId: UUID, channel: ConversationChannel, text: String): CaptureEntry {
        val request = text.trim()
        if (request.isBlank()) return CaptureEntry.Captured(null)
        countEntry("unprompted")
        return draft(userId, channel, request, unprompted = true)
    }

    private fun draft(
        userId: UUID,
        channel: ConversationChannel,
        request: String,
        unprompted: Boolean,
    ): CaptureEntry {
        if (!allow(userId, channel)) return CaptureEntry.Captured(null)
        channel.indicateTyping()
        val op = PendingOp.Draft(request, emptyList())
        val outcome = suggestionAgent.quickAddDraft(userId, request, emptyList(), mustDraft = false, unprompted = unprompted)
        return renderOutcome(userId, channel, op, outcome, clarifyRound = 1)
    }

    /**
     * Captures from media the user sent — a forwarded photo of an invitation, a voice note. The
     * attachment is read once, by the capture model, which hands back what it found; from the
     * confirmation card on, the capture behaves exactly like a typed one (that read-back stands in
     * for the user's request), so no bytes are held past this call.
     *
     * Reached from [handleInbound] — an attachment sent into a capture that is already running. For
     * an attachment that arrives on its own, see [beginUnpromptedFromMedia].
     *
     * Returns null — the flow is over — when the configured model can't accept the modality, or the
     * capture failed; both paths tell the user what to do instead.
     */
    fun beginFromMedia(
        userId: UUID,
        channel: ConversationChannel,
        attachments: List<InboundAttachment>,
        caption: String?,
    ): QuickAddState? =
        captureMedia(userId, channel, attachments, caption, offerRouting = false, entrySource = null).stateOrNull()

    /**
     * Captures from an attachment the user sent on its own, with no `/add` and no capture running.
     *
     * The two media kinds are not treated alike (`docs/FREE-TEXT-CAPTURE.md` D7). An **image** is a
     * capture: nobody forwards a photo to a task bot incidentally, so offering the model a way to
     * bail out would only be a way to lose the obvious case. A **voice note** is as open-ended as
     * typed text — "remind me to call the plumber" and "how does this thing work?" arrive the same
     * way — so it gets the same routing escape text does. Either way the classification rides along
     * in the call that already transcribes and drafts, so it costs nothing extra.
     */
    fun beginUnpromptedFromMedia(
        userId: UUID,
        channel: ConversationChannel,
        attachments: List<InboundAttachment>,
        caption: String?,
    ): CaptureEntry = captureMedia(
        userId, channel, attachments, caption,
        offerRouting = attachments.any { it.kind == AttachmentKind.AUDIO },
        entrySource = "unprompted",
    )

    private fun captureMedia(
        userId: UUID,
        channel: ConversationChannel,
        attachments: List<InboundAttachment>,
        caption: String?,
        offerRouting: Boolean,
        entrySource: String?,
    ): CaptureEntry {
        if (attachments.isEmpty()) return CaptureEntry.Captured(null)
        entrySource?.let { countEntry(it) }
        if (!allow(userId, channel)) return CaptureEntry.Captured(null)
        channel.indicateTyping()
        val outcome = try {
            suggestionAgent.quickAddDraftFromMedia(userId, attachments, caption, unprompted = offerRouting)
        } catch (e: UnsupportedModalityException) {
            countMedia(attachments, "unsupported")
            log.info("quick-add media capture declined: model lacks modalities {}", e.kinds)
            channel.send(ChannelMessage.Text(msg(userId, unsupportedMessageKey(attachments))))
            return CaptureEntry.Captured(null)
        }
        if (outcome is SuggestionOutcome.NotACapture) {
            countMedia(attachments, "routed")
            // What the model heard stands in for the message text, so a route out of a voice note
            // can carry it the same way a typed one does.
            val heard = outcome.sourceText ?: caption?.trim().orEmpty()
            return renderOutcome(userId, channel, PendingOp.Draft(heard, emptyList()), outcome, clarifyRound = 1)
        }
        val request = outcome.sourceText
            ?: caption?.trim()?.takeIf { it.isNotBlank() }
            ?: MEDIA_REQUEST_PLACEHOLDER
        outcome.sourceText?.let { echoSourceText(userId, channel, attachments, it) }
        countMedia(attachments, if (outcome is SuggestionOutcome.Unparseable) "failed" else "captured")
        val op = PendingOp.Draft(request, emptyList())
        return renderOutcome(userId, channel, op, outcome, clarifyRound = 1)
    }

    /**
     * Advances an in-progress flow. Returns the next state, or [CaptureEntry.Captured] with a null
     * state when the flow is finished. It can also return [CaptureEntry.Routed]: the plan offer at
     * the end of a capture is passive, so a message typed instead of tapping it is a new message,
     * and gets the unprompted entry's treatment — including its routing escape.
     */
    fun handleInbound(
        userId: UUID,
        channel: ConversationChannel,
        state: QuickAddState,
        inbound: ChannelInbound,
    ): CaptureEntry = when (state) {
        is QuickAddState.AwaitingPlanDay ->
            (inbound as? ChannelInbound.Selection)?.let { onPlanDay(userId, channel, state, it) }
                ?: lapseOffer(userId, channel, inbound)
        is QuickAddState.AwaitingPlanTime ->
            (inbound as? ChannelInbound.Selection)?.let { onPlanTime(userId, channel, state, it) }
                ?: lapseOffer(userId, channel, inbound)
        else -> CaptureEntry.Captured(
            if (inbound is ChannelInbound.Media) {
                // Media always (re)starts the capture from the attachment: it carries far more than
                // the typed line it replaces, so folding it into a half-built draft would be wrong.
                beginFromMedia(userId, channel, inbound.attachments, inbound.caption)
            } else when (state) {
                is QuickAddState.AwaitingDescription -> onDescription(userId, channel, inbound)
                is QuickAddState.AwaitingConfirmation -> onConfirmation(userId, channel, state, inbound)
                is QuickAddState.AwaitingAdjustment -> onAdjustment(userId, channel, state, inbound)
                is QuickAddState.AwaitingClarification -> onClarification(userId, channel, state, inbound)
                // Handled above; listed so this stays exhaustive.
                is QuickAddState.AwaitingPlanDay, is QuickAddState.AwaitingPlanTime -> null
            }
        )
    }

    private fun onDescription(userId: UUID, channel: ConversationChannel, inbound: ChannelInbound): QuickAddState? {
        val text = (inbound as? ChannelInbound.Text)?.text?.trim().orEmpty()
        if (text.isBlank()) {
            channel.send(ChannelMessage.Text(msg(userId, "quickadd.prompt.describe")))
            return QuickAddState.AwaitingDescription(now())
        }
        return draft(userId, channel, text, unprompted = false).stateOrNull()
    }

    private fun onConfirmation(
        userId: UUID,
        channel: ConversationChannel,
        state: QuickAddState.AwaitingConfirmation,
        inbound: ChannelInbound,
    ): QuickAddState? {
        if (inbound is ChannelInbound.Selection) {
            return when (inbound.optionId) {
                OPTION_SAVE -> saveAndOffer(userId, channel, state)
                OPTION_CANCEL -> {
                    count("cancelled")
                    channel.send(ChannelMessage.Text(msg(userId, "quickadd.cancelled")))
                    null
                }
                OPTION_ADJUST -> {
                    channel.send(ChannelMessage.Text(msg(userId, "quickadd.adjust.prompt")))
                    QuickAddState.AwaitingAdjustment(state.items, state.originalRequest, state.clarifications, now())
                }
                else -> { renderCard(userId, channel, state.items); state }
            }
        }
        val text = (inbound as? ChannelInbound.Text)?.text?.trim().orEmpty()
        if (text.isBlank()) return state
        return applyAdjustment(userId, channel, state.items, state.originalRequest, state.clarifications, text)
    }

    private fun onAdjustment(
        userId: UUID,
        channel: ConversationChannel,
        state: QuickAddState.AwaitingAdjustment,
        inbound: ChannelInbound,
    ): QuickAddState? {
        val text = (inbound as? ChannelInbound.Text)?.text?.trim().orEmpty()
        if (text.isBlank()) {
            channel.send(ChannelMessage.Text(msg(userId, "quickadd.adjust.prompt")))
            return state
        }
        return applyAdjustment(userId, channel, state.items, state.originalRequest, state.clarifications, text)
    }

    private fun onClarification(
        userId: UUID,
        channel: ConversationChannel,
        state: QuickAddState.AwaitingClarification,
        inbound: ChannelInbound,
    ): QuickAddState? {
        val answer: String = when (inbound) {
            is ChannelInbound.Selection -> {
                if (inbound.optionId == OPTION_EXPLAIN) {
                    channel.send(ChannelMessage.Text(msg(userId, "quickadd.clarify.explain")))
                    return state.copy(createdAt = now())
                }
                state.options.firstOrNull { it.id == inbound.optionId }?.label ?: inbound.optionId
            }
            is ChannelInbound.Text -> inbound.text.trim()
            // Unreachable: handleInbound routes media to a fresh capture before any state handler
            // sees it. Kept so this stays exhaustive rather than silently answering with an else.
            is ChannelInbound.Media -> inbound.caption.orEmpty()
        }
        if (answer.isBlank()) return state

        val newClarifications = state.op.clarifications + ClarificationExchange(state.question, answer)
        val mustDraft = state.rounds >= MAX_CLARIFY_ROUNDS
        channel.indicateTyping()
        val (newOp, outcome) = when (val op = state.op) {
            is PendingOp.Draft -> {
                val o = PendingOp.Draft(op.originalRequest, newClarifications)
                o to suggestionAgent.quickAddDraft(userId, op.originalRequest, newClarifications, mustDraft)
            }
            is PendingOp.Revise -> {
                val o = PendingOp.Revise(op.originalRequest, op.items, op.instruction, newClarifications)
                o to suggestionAgent.quickAddRevise(userId, op.originalRequest, op.items, op.instruction, newClarifications, mustDraft)
            }
        }
        return renderOutcome(userId, channel, newOp, outcome, clarifyRound = state.rounds + 1).stateOrNull()
    }

    private fun applyAdjustment(
        userId: UUID,
        channel: ConversationChannel,
        items: List<CapturedItem>,
        originalRequest: String,
        clarifications: List<ClarificationExchange>,
        instruction: String,
    ): QuickAddState? {
        channel.indicateTyping()
        val op = PendingOp.Revise(originalRequest, items, instruction, clarifications)
        val outcome = suggestionAgent.quickAddRevise(userId, originalRequest, items, instruction, clarifications, mustDraft = false)
        return renderOutcome(userId, channel, op, outcome, clarifyRound = 1).stateOrNull()
    }

    private fun renderOutcome(
        userId: UUID,
        channel: ConversationChannel,
        op: PendingOp,
        outcome: SuggestionOutcome,
        clarifyRound: Int,
    ): CaptureEntry = when (outcome) {
        is SuggestionOutcome.Draft -> CaptureEntry.Captured(
            validateItems(userId, outcome.items).let { validated ->
                if (validated.isEmpty()) {
                    log.warn("quick-add validated to no items; abandoning capture")
                    channel.send(ChannelMessage.Text(msg(userId, "quickadd.unparseable")))
                    null
                } else {
                    renderCard(userId, channel, validated)
                    QuickAddState.AwaitingConfirmation(
                        validated, op.originalRequest, op.clarifications, now(), outcome.planThisWeek,
                    )
                }
            },
        )
        is SuggestionOutcome.Clarify -> CaptureEntry.Captured(
            if (clarifyRound > MAX_CLARIFY_ROUNDS) {
                // The agent asked again despite being told it must draft — give up gracefully.
                log.warn("quick-add exceeded clarification budget; abandoning capture")
                channel.send(ChannelMessage.Text(msg(userId, "quickadd.unparseable")))
                null
            } else {
                // Normalize option ids to short, stable values (they become channel callback data).
                val options = outcome.options.mapIndexed { i, o -> ClarifyOption(id = "o$i", label = o.label) }
                renderClarify(userId, channel, outcome.question, options)
                QuickAddState.AwaitingClarification(op, outcome.question, options, clarifyRound, now())
            },
        )
        // Only reachable from an unprompted entry — the shape isn't offered otherwise, so the
        // in-flow callers below can safely unwrap this as a plain state.
        is SuggestionOutcome.NotACapture -> {
            count("not_a_capture")
            meterRegistry.counter("tasker.quickadd.route", "intent", outcome.intent.name.lowercase()).increment()
            log.debug("unprompted message routed as {}", outcome.intent)
            CaptureEntry.Routed(outcome.intent, op.originalRequest)
        }
        is SuggestionOutcome.Unparseable -> {
            count("failed")
            channel.send(ChannelMessage.Text(msg(userId, "quickadd.unparseable")))
            CaptureEntry.Captured(null)
        }
    }

    /**
     * Saves the capture and returns the id of the task it created — but only for the shape the plan
     * hand-off will accept: exactly one task and no events (`docs/FREE-TEXT-CAPTURE.md` D6 gate 2).
     * Null for anything else, including a failed save.
     */
    private fun save(userId: UUID, channel: ConversationChannel, items: List<CapturedItem>): UUID? {
        val tasks = items.filterIsInstance<CapturedItem.Task>().map { it.draft }
        val events = items.filterIsInstance<CapturedItem.Event>().map { it.draft }

        if (tasks.any { it.categoryId.isNullOrBlank() }) {
            log.warn("quick-add save aborted: a task draft had no category")
            channel.send(ChannelMessage.Text(msg(userId, "quickadd.failed")))
            return null
        }

        val savedTitles = mutableListOf<String>()
        val savedTaskIds = mutableListOf<UUID>()
        var invitesSkipped = false

        val result = runCatching {
            val boardId = boardMembershipService.resolveDefaultBoard(userId)
            for (task in tasks) {
                val created = backlogTaskService.createTask(userId, boardId, task.toCreateBacklogTaskRequest())
                savedTitles += created.title
                savedTaskIds += created.id
            }
            if (events.isNotEmpty()) {
                val created = oneOffEventService.createEvents(userId, boardId, events.map { it.toOneOffEventDraft() })
                savedTitles += created.events.map { it.title }
                invitesSkipped = !created.invitesScheduled
            }
        }

        result.onSuccess {
            count("saved")
            log.debug("quick-add created {} item(s)", savedTitles.size)
            val joined = savedTitles.joinToString(", ") { channel.formatter.escape(it) }
            channel.send(ChannelMessage.Text(msg(userId, "quickadd.saved", joined)))
            if (invitesSkipped) {
                channel.send(ChannelMessage.Text(msg(userId, "quickadd.event.invite_skipped")))
            }
        }.onFailure { e ->
            log.warn("quick-add save failed: {}", e.message)
            channel.send(ChannelMessage.Text(msg(userId, "quickadd.failed")))
        }
        if (result.isFailure || events.isNotEmpty()) return null
        return savedTaskIds.singleOrNull()
    }

    // ── The plan hand-off (`docs/FREE-TEXT-CAPTURE.md` D6) ───────────────────────────────────────

    /**
     * Saves, then — under D6's gates — offers to put the new task into this week's plan. The offer
     * is the only thing between the two: the save itself is unchanged, and a suppressed offer ends
     * the flow exactly as a save always did.
     */
    private fun saveAndOffer(
        userId: UUID,
        channel: ConversationChannel,
        state: QuickAddState.AwaitingConfirmation,
    ): QuickAddState? {
        val taskId = save(userId, channel, state.items) ?: return null
        return offerPlan(userId, channel, state, taskId)
    }

    /**
     * The offer, and the gate on it. Most captures are backlog items that are explicitly *not* for
     * this week, so asking every time would be noise; it appears only when there is a plan to add
     * to and the capture itself says it belongs there — either the user said so (`plan_this_week`)
     * or the drafted deadline falls inside the planned week.
     *
     * Everything here is deterministic: no model call, and the same [PlanFinalizationService] path
     * the web UI's "Add to this week's plan" uses.
     */
    private fun offerPlan(
        userId: UUID,
        channel: ConversationChannel,
        state: QuickAddState.AwaitingConfirmation,
        taskId: UUID,
    ): QuickAddState? {
        val draft = (state.items.singleOrNull() as? CapturedItem.Task)?.draft ?: return null
        val session = planningSessionService.findCurrentPlan(userId) ?: return null
        val deadline = draft.deadline?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val deadlineInWeek = deadline != null &&
            !deadline.isBefore(session.weekStart) && !deadline.isAfter(session.weekStart.plusDays(6))
        if (!state.planThisWeek && !deadlineInWeek) return null

        val zone = userZone(userId)
        val days = availableDays(session.weekStart, zone)
        if (days.isEmpty()) {
            // The planned week is behind us, or its last day is already over. Nothing to offer.
            log.debug("plan hand-off skipped: no day left in the planned week")
            return null
        }
        countPlan("offered")
        renderDayPicker(userId, channel, days)
        return QuickAddState.AwaitingPlanDay(
            taskId = taskId,
            title = draft.title,
            minutes = draft.estimatedMinutes?.toLong() ?: DEFAULT_PLAN_MINUTES,
            sessionId = session.id,
            days = days,
            createdAt = now(),
        )
    }

    private fun onPlanDay(
        userId: UUID,
        channel: ConversationChannel,
        state: QuickAddState.AwaitingPlanDay,
        selection: ChannelInbound.Selection,
    ): CaptureEntry {
        if (selection.optionId == OPTION_PLAN_SKIP) return declineOffer(userId, channel)
        val day = state.days.firstOrNull { selection.optionId == OPTION_PLAN_DAY + it }
        if (day == null) {
            // A stale tap from an older card — re-render rather than guessing which day was meant.
            renderDayPicker(userId, channel, state.days)
            return CaptureEntry.Captured(state)
        }
        val times = availableTimes(day, userZone(userId))
        if (times.isEmpty()) {
            renderDayPicker(userId, channel, state.days)
            return CaptureEntry.Captured(state)
        }
        renderTimePicker(userId, channel, day, times)
        return CaptureEntry.Captured(
            QuickAddState.AwaitingPlanTime(
                taskId = state.taskId,
                title = state.title,
                minutes = state.minutes,
                sessionId = state.sessionId,
                day = day,
                times = times,
                createdAt = now(),
            )
        )
    }

    private fun onPlanTime(
        userId: UUID,
        channel: ConversationChannel,
        state: QuickAddState.AwaitingPlanTime,
        selection: ChannelInbound.Selection,
    ): CaptureEntry {
        if (selection.optionId == OPTION_PLAN_SKIP) return declineOffer(userId, channel)
        val time = PlanTimeOfDay.parse(selection.optionId.removePrefix(OPTION_PLAN_TIME))
            ?.takeIf { it in state.times }
        if (time == null) {
            renderTimePicker(userId, channel, state.day, state.times)
            return CaptureEntry.Captured(state)
        }
        return schedule(userId, channel, state, time)
    }

    private fun schedule(
        userId: UUID,
        channel: ConversationChannel,
        state: QuickAddState.AwaitingPlanTime,
        time: PlanTimeOfDay,
    ): CaptureEntry {
        val zone = userZone(userId)
        val start = ZonedDateTime.of(state.day, time.at, zone)
        val end = start.plusMinutes(state.minutes)
        val slot = AgreedTimeSlot(
            startIso = start.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            endIso = end.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        )
        val invited = runCatching {
            planFinalizationService.addTaskToSession(
                userId,
                state.sessionId,
                AgreedPlanTask(taskId = state.taskId, title = state.title, slots = listOf(slot)),
            )
        }.getOrElse { e ->
            countPlan("failed")
            log.warn("plan hand-off failed for session {}: {}", state.sessionId, e.message)
            channel.send(ChannelMessage.Text(msg(userId, "quickadd.plan.failed")))
            return CaptureEntry.Captured(null)
        }
        countPlan("scheduled")
        log.info("Added task {} to plan {} from a quick-add capture", state.taskId, state.sessionId)
        val locale = locale(userId)
        channel.send(ChannelMessage.Text(
            msg(userId, "quickadd.plan.added", channel.formatter.escape(formatEventTime(start, end, locale, zone))),
        ))
        if (!invited) channel.send(ChannelMessage.Text(msg(userId, "quickadd.plan.invite_skipped")))
        return CaptureEntry.Captured(null)
    }

    private fun declineOffer(userId: UUID, channel: ConversationChannel): CaptureEntry {
        countPlan("declined")
        channel.send(ChannelMessage.Text(msg(userId, "quickadd.plan.skipped")))
        return CaptureEntry.Captured(null)
    }

    /**
     * The user typed (or sent an attachment) instead of answering the plan offer. The task is
     * already saved, so there is nothing to abandon: the offer lapses silently and the message gets
     * the treatment any out-of-band message gets, routing escape included.
     */
    private fun lapseOffer(userId: UUID, channel: ConversationChannel, inbound: ChannelInbound): CaptureEntry {
        countPlan("lapsed")
        return when (inbound) {
            is ChannelInbound.Text -> beginUnprompted(userId, channel, inbound.text)
            is ChannelInbound.Media -> beginUnpromptedFromMedia(userId, channel, inbound.attachments, inbound.caption)
            // Unreachable: a selection is what this state is waiting for.
            is ChannelInbound.Selection -> CaptureEntry.Captured(null)
        }
    }

    /**
     * The days of the planned week still worth offering: from today onward, and only those with a
     * time slot that hasn't already passed. Usually all of them; late on the week's last day, none.
     */
    private fun availableDays(weekStart: LocalDate, zone: ZoneId): List<LocalDate> {
        val today = LocalDate.ofInstant(clock.instant(), zone)
        val first = maxOf(weekStart, today)
        val last = weekStart.plusDays(6)
        if (first.isAfter(last)) return emptyList()
        return generateSequence(first) { it.plusDays(1) }
            .takeWhile { !it.isAfter(last) }
            .filter { availableTimes(it, zone).isNotEmpty() }
            .toList()
    }

    /** The coarse times still ahead of us on [day] — all three on any future day. */
    private fun availableTimes(day: LocalDate, zone: ZoneId): List<PlanTimeOfDay> {
        val now = clock.instant()
        return PlanTimeOfDay.entries.filter { ZonedDateTime.of(day, it.at, zone).toInstant().isAfter(now) }
    }

    private fun renderDayPicker(userId: UUID, channel: ConversationChannel, days: List<LocalDate>) {
        val locale = locale(userId)
        val weekdayFmt = DateTimeFormatter.ofPattern("EEEE", locale)
        val dateFmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
        val options = days.map { day ->
            ChoiceOption(
                OPTION_PLAN_DAY + day,
                messageSource.getMessage(
                    "quickadd.plan.day_label",
                    arrayOf<Any>(day.format(weekdayFmt), day.format(dateFmt)),
                    locale,
                ),
            )
        } + ChoiceOption(OPTION_PLAN_SKIP, msg(userId, "quickadd.plan.skip"))
        channel.send(ChannelMessage.Choice(prompt = msg(userId, "quickadd.plan.offer"), options = options))
    }

    private fun renderTimePicker(
        userId: UUID,
        channel: ConversationChannel,
        day: LocalDate,
        times: List<PlanTimeOfDay>,
    ) {
        val locale = locale(userId)
        val timeFmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
        val options = times.map { time ->
            ChoiceOption(
                OPTION_PLAN_TIME + time.name.lowercase(),
                messageSource.getMessage(time.labelKey, arrayOf<Any>(time.at.format(timeFmt)), locale),
            )
        } + ChoiceOption(OPTION_PLAN_SKIP, msg(userId, "quickadd.plan.skip"))
        val dayLabel = day.format(DateTimeFormatter.ofPattern("EEEE", locale))
        channel.send(ChannelMessage.Choice(
            prompt = msg(userId, "quickadd.plan.time_prompt", channel.formatter.escape(dayLabel)),
            options = options,
        ))
    }

    private fun countPlan(result: String) =
        meterRegistry.counter("tasker.quickadd.plan", "result", result).increment()

    /**
     * Drops fields the model may have malformed so the card shows exactly what will be saved. For
     * events, parses ISO timestamps, defaults a missing/invalid end to start + 60min, and silently
     * drops events whose start is unparseable or far in the past (the agent should have asked).
     */
    private fun validateItems(userId: UUID, items: List<CapturedItem>): List<CapturedItem> {
        if (items.isEmpty()) return emptyList()
        val categories = categoryService.getAllForUser(userId)
        val zone = userZone(userId)
        val nowInstant = clock.instant()
        return items.mapNotNull { item ->
            when (item) {
                is CapturedItem.Task -> {
                    val resolvedCategory = item.draft.categoryId
                        ?.let { id -> categories.firstOrNull { it.id.toString() == id } }
                        ?: categories.firstOrNull()
                    val priority = item.draft.priority?.lowercase()?.takeIf { it in TaskPriority.allowedValues }
                    val deadline = item.draft.deadline?.takeIf { DEADLINE_REGEX.matches(it) }
                    val estimate = item.draft.estimatedMinutes?.takeIf { it > 0 }
                    CapturedItem.Task(
                        item.draft.copy(
                            categoryId = resolvedCategory?.id?.toString(),
                            priority = priority,
                            deadline = deadline,
                            estimatedMinutes = estimate,
                        ),
                    )
                }
                is CapturedItem.Event -> {
                    val start = parseDateTime(item.draft.startIso, zone)
                    if (start == null) {
                        log.warn("quick-add dropping event '{}': unparseable start", item.draft.title)
                        return@mapNotNull null
                    }
                    if (start.toInstant().isBefore(nowInstant.minusSeconds(PAST_GRACE_SECONDS))) {
                        log.warn("quick-add dropping event '{}': start is in the past", item.draft.title)
                        return@mapNotNull null
                    }
                    val parsedEnd = item.draft.endIso?.let { parseDateTime(it, zone) }
                    val end = if (parsedEnd == null || !parsedEnd.isAfter(start)) {
                        start.plusMinutes(DEFAULT_EVENT_MINUTES)
                    } else {
                        parsedEnd
                    }
                    CapturedItem.Event(
                        item.draft.copy(
                            startIso = start.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                            endIso = end.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                        ),
                    )
                }
            }
        }
    }

    private fun renderCard(userId: UUID, channel: ConversationChannel, items: List<CapturedItem>) {
        val f = channel.formatter
        val locale = locale(userId)
        val zone = userZone(userId)
        val categories = categoryService.getAllForUser(userId)
        val blocks = items.map { item ->
            when (item) {
                is CapturedItem.Task -> renderTaskBlock(item.draft, categories, f, locale)
                is CapturedItem.Event -> renderEventBlock(item.draft, f, locale, zone)
            }
        }
        channel.send(
            ChannelMessage.Choice(
                prompt = blocks.joinToString("\n\n"),
                options = listOf(
                    ChoiceOption(OPTION_SAVE, msg(userId, "quickadd.button.save")),
                    ChoiceOption(OPTION_ADJUST, msg(userId, "quickadd.button.adjust")),
                    ChoiceOption(OPTION_CANCEL, msg(userId, "quickadd.button.cancel")),
                ),
            )
        )
    }

    private fun renderTaskBlock(
        draft: TaskDraft,
        categories: List<BacklogTaskCategory>,
        f: MessageFormatter,
        locale: Locale,
    ): String {
        val categoryLabel = draft.categoryId
            ?.let { id -> categories.firstOrNull { it.id.toString() == id }?.label }

        val meta = buildList {
            categoryLabel?.let { add("🗂 ${f.escape(it)}") }
            draft.priority?.let { add("❗${f.escape(priorityLabel(it, locale))}") }
            draft.deadline?.let { add("📅 ${f.escape(it)}") }
            draft.estimatedMinutes?.let {
                add("⏱ ${f.escape(messageSource.getMessage("quickadd.card.estimate", arrayOf(it), locale))}")
            }
        }.joinToString("  ")

        return buildString {
            append("➕ ").append(f.bold(draft.title))
            if (meta.isNotEmpty()) append("\n").append(meta)
            draft.tags.takeIf { it.isNotEmpty() }?.let { tags ->
                append("\n🏷 ").append(tags.joinToString(", ") { f.escape(it.label) })
            }
            draft.description?.takeIf { it.isNotBlank() }?.let { desc ->
                append("\n").append(f.italic(truncate(desc)))
            }
        }
    }

    private fun renderEventBlock(
        draft: EventDraft,
        f: MessageFormatter,
        locale: Locale,
        zone: ZoneId,
    ): String {
        val start = parseDateTime(draft.startIso, zone)
        val end = draft.endIso?.let { parseDateTime(it, zone) }
        val whenLine = if (start != null) formatEventTime(start, end, locale, zone) else f.escape(draft.startIso)

        return buildString {
            append("📅 ").append(f.bold(draft.title))
            append("\n🕒 ").append(f.escape(whenLine))
            draft.location?.takeIf { it.isNotBlank() }?.let { loc ->
                append("\n📍 ").append(f.escape(loc))
            }
            draft.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                append("\n").append(f.italic(truncate(notes)))
            }
        }
    }

    private fun renderClarify(
        userId: UUID,
        channel: ConversationChannel,
        question: String,
        options: List<ClarifyOption>,
    ) {
        if (options.isEmpty()) {
            channel.send(ChannelMessage.Text(question))
            return
        }
        val choiceOptions = options.map { ChoiceOption(it.id, it.label) } +
            ChoiceOption(OPTION_EXPLAIN, msg(userId, "quickadd.button.explain"))
        channel.send(ChannelMessage.Choice(prompt = question, options = choiceOptions))
    }

    private fun priorityLabel(priority: String, locale: Locale): String =
        messageSource.getMessage("quickadd.priority.${priority.lowercase()}", null, priority, locale)!!

    private fun truncate(text: String): String =
        if (text.length <= DESCRIPTION_PREVIEW) text else text.take(DESCRIPTION_PREVIEW).trimEnd() + "…"

    /**
     * Shows the user what the model read or heard before the draft card, so a misread is obvious at
     * a glance — a wrong date lifted off a blurry invitation is much easier to spot in the echo than
     * in the resulting event.
     */
    private fun echoSourceText(
        userId: UUID,
        channel: ConversationChannel,
        attachments: List<InboundAttachment>,
        sourceText: String,
    ) {
        val key = if (attachments.any { it.kind == AttachmentKind.AUDIO }) {
            "quickadd.media.heard"
        } else {
            "quickadd.media.read"
        }
        channel.send(ChannelMessage.Text(msg(userId, key, channel.formatter.escape(truncate(sourceText)))))
    }

    private fun unsupportedMessageKey(attachments: List<InboundAttachment>): String =
        if (attachments.any { it.kind == AttachmentKind.AUDIO }) {
            "quickadd.media.audio_unsupported"
        } else {
            "quickadd.media.image_unsupported"
        }

    private fun countMedia(attachments: List<InboundAttachment>, result: String) {
        val kind = attachments.map { it.kind }.distinct().singleOrNull()?.name?.lowercase() ?: "mixed"
        meterRegistry.counter("tasker.quickadd.media", "kind", kind, "result", result).increment()
    }

    private fun count(result: String) =
        meterRegistry.counter("tasker.quickadd.outcome", "result", result).increment()

    private fun countEntry(source: String) =
        meterRegistry.counter("tasker.quickadd.entry", "source", source).increment()

    /**
     * Guards the model call that opens a capture. Only entry points are checked: the rounds inside
     * a live capture are already bounded (by [MAX_CLARIFY_ROUNDS] and by the user's own taps), and
     * cutting one off would strand a draft the user is in the middle of fixing.
     */
    private fun allow(userId: UUID, channel: ConversationChannel): Boolean {
        if (rateLimiter.tryConsume(userId.toString())) return true
        count("rate_limited")
        log.info("quick-add rate limit reached for user {}", userId)
        channel.send(ChannelMessage.Text(msg(userId, "quickadd.rate_limited")))
        return false
    }

    private fun locale(userId: UUID): Locale = userSettingsService.getLocale(userId)

    private fun userZone(userId: UUID): ZoneId =
        runCatching { ZoneId.of(userSettingsService.getOrCreate(userId).timeZone) }
            .getOrDefault(ZoneId.of("UTC"))

    private fun msg(userId: UUID, key: String, vararg args: Any): String =
        messageSource.getMessage(key, args, locale(userId))

    private fun now() = clock.instant()

    /**
     * Parse the model's ISO-8601 datetime. The model is instructed to include a numeric offset,
     * but we tolerate a missing one by attaching the user's zone — a "5pm tomorrow" with no offset
     * shouldn't tank the whole capture.
     */
    private fun parseDateTime(iso: String, zone: ZoneId): ZonedDateTime? {
        return runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(zone) }
            .recoverCatching { LocalDateTime.parse(iso).atZone(zone) }
            .recoverCatching { LocalDate.parse(iso).atStartOfDay(zone) }
            .getOrElse { e ->
                if (e is DateTimeException) null else throw e
            }
    }

    private fun formatEventTime(start: ZonedDateTime, end: ZonedDateTime?, locale: Locale, zone: ZoneId): String {
        val dateTimeFmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)
        val timeFmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
        val startStr = dateTimeFmt.format(start)
        return if (end == null) {
            startStr
        } else if (end.toLocalDate() == start.toLocalDate()) {
            "$startStr – ${timeFmt.format(end)} ${zone.id}"
        } else {
            "$startStr – ${dateTimeFmt.format(end)} ${zone.id}"
        }
    }

    private fun EventDraft.toOneOffEventDraft(): OneOffEventDraft {
        // Validated upstream — both ISO strings are guaranteed to parse with an offset.
        val start = OffsetDateTime.parse(startIso).toInstant()
        val end = OffsetDateTime.parse(requireNotNull(endIso)).toInstant()
        return OneOffEventDraft(title = title, startsAt = start, endsAt = end, location = location, notes = notes)
    }

    companion object {
        /** Maximum clarifying questions the agent may ask before it must produce a best-guess draft. */
        const val MAX_CLARIFY_ROUNDS = 2

        const val OPTION_SAVE = "quickadd_save"
        const val OPTION_ADJUST = "quickadd_adjust"
        const val OPTION_CANCEL = "quickadd_cancel"
        const val OPTION_EXPLAIN = "quickadd_explain"

        /** Plan hand-off taps. Distinct from `PlanConfirmationRegistry`'s `plan_*` callback ids. */
        const val OPTION_PLAN_DAY = "quickadd_planday_"
        const val OPTION_PLAN_TIME = "quickadd_plantime_"
        const val OPTION_PLAN_SKIP = "quickadd_planskip"

        /**
         * Stands in for the user's request when a media capture yielded no read-back and carried no
         * caption. It only ever reaches the model, on a later revise/clarify round — the user never
         * sees it.
         */
        const val MEDIA_REQUEST_PLACEHOLDER = "(the user sent an attachment with no text)"

        private val DEADLINE_REGEX = Regex("""^\d{4}-\d{2}-\d{2}$""")
        private const val DESCRIPTION_PREVIEW = 200
        private const val DEFAULT_EVENT_MINUTES = 60L
        /** Block length for a planned task the model gave no estimate for. */
        private const val DEFAULT_PLAN_MINUTES = 30L
        /** Allow events that start up to this many seconds ago — guards against tiny clock-skew drops. */
        private const val PAST_GRACE_SECONDS = 3600L
    }
}
