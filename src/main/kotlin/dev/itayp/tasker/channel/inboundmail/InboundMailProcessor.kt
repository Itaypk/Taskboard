package dev.itayp.tasker.channel.inboundmail

import dev.itayp.tasker.ai.InputModalitySupport
import dev.itayp.tasker.ai.access.AiAccessService
import dev.itayp.tasker.ai.access.AiDisabledException
import dev.itayp.tasker.ai.access.AiTierNotGrantedException
import dev.itayp.tasker.ai.access.AiUsageLimitExceededException
import dev.itayp.tasker.capture.QuickAddFlow
import dev.itayp.tasker.capture.SavedCapture
import dev.itayp.tasker.channel.InboundAttachment
import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.jpa.InboundMailEntity
import dev.itayp.tasker.jpa.InboundMailStatus
import dev.itayp.tasker.planning.CapturedItem
import dev.itayp.tasker.planning.SuggestionOutcome
import dev.itayp.tasker.planning.TaskDraft
import dev.itayp.tasker.planning.TaskSuggestionAgent
import dev.itayp.tasker.repository.InboundMailRepository
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Turns queued inbound email into tasks: the fire-and-forget capture of `docs/INBOUND-EMAIL.md`.
 * No confirmation card and no clarifying questions (nobody is there to answer them): the model
 * drafts, the draft is saved, and [InboundMailConfirmation] tells the user what was created.
 *
 * Without AI (switched off, no tier, budget spent, or a model that keeps failing) the email still
 * becomes a task, verbatim: subject as the title, the text as the description. Losing a forwarded
 * email because the model is unavailable would be worse than an unpolished task.
 *
 * The email's images and PDFs go to the model with its text when the capture model can read them
 * (`InputModalitySupport`, failing closed). When it can't, when AI is unavailable, and on the last
 * attempt (in case the attachments are what keeps failing), capture runs from the text alone and
 * the confirmation says the attachments weren't read.
 *
 * Same single-instance polling pattern as `NotificationScheduler`. A failed attempt is retried with
 * backoff; after [MAX_ATTEMPTS] the row is marked FAILED and the user is told nothing was created.
 */
@Component
class InboundMailProcessor(
    private val repository: InboundMailRepository,
    private val userCryptoService: UserCryptoService,
    private val objectMapper: ObjectMapper,
    private val suggestionAgent: TaskSuggestionAgent,
    private val quickAddFlow: QuickAddFlow,
    private val aiAccessService: AiAccessService,
    private val inputModalitySupport: InputModalitySupport,
    private val confirmation: InboundMailConfirmation,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(InboundMailProcessor::class.java)

    @Scheduled(fixedDelay = POLL_INTERVAL_SECONDS, initialDelay = POLL_INTERVAL_SECONDS, timeUnit = TimeUnit.SECONDS)
    fun poll() {
        val due = repository.findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            InboundMailStatus.PENDING, clock.instant(), PageRequest.of(0, BATCH_SIZE),
        )
        for (mail in due) {
            try {
                process(mail)
            } catch (e: Exception) {
                log.error("Inbound mail {} could not be processed", mail.id, e)
            }
        }
    }

    /** Hourly: processed rows are kept only as dedup keys (see changeset 018). */
    @Scheduled(fixedDelay = 1, initialDelay = 1, timeUnit = TimeUnit.HOURS)
    @Transactional
    fun purgeProcessed() {
        val removed = repository.deleteProcessedBefore(clock.instant().minus(DEDUP_RETENTION))
        if (removed > 0) log.debug("Purged {} processed inbound mail row(s)", removed)
    }

    fun process(mail: InboundMailEntity) {
        val userId = checkNotNull(mail.userId)
        val payload = try {
            objectMapper.readValue(checkNotNull(userCryptoService.decrypt(userId, mail.payload)), InboundMailPayload::class.java)
        } catch (e: Exception) {
            // Retrying can't fix an unreadable payload; failing once beats an error every poll.
            finish(mail, InboundMailStatus.FAILED)
            count("failed")
            log.error("Inbound mail {} for user {} has an unreadable payload", mail.id, userId, e)
            return
        }
        val attachments = readAttachments(mail)
        val lastAttempt = mail.attempts + 1 >= MAX_ATTEMPTS
        val captured = try {
            capture(mail, payload, attachments, lastAttempt)
        } catch (e: Exception) {
            mail.attempts += 1
            if (!lastAttempt) {
                mail.nextAttemptAt = clock.instant().plus(BACKOFF[mail.attempts - 1])
                repository.save(mail)
                log.warn("Inbound mail {} for user {} failed (attempt {}), will retry: {}", mail.id, userId, mail.attempts, e.message)
                return
            }
            finish(mail, InboundMailStatus.FAILED)
            count("failed")
            log.error("Inbound mail {} for user {} failed for good after {} attempts", mail.id, userId, mail.attempts, e)
            confirmation.sendFailure(userId, payload)
            return
        }
        finish(mail, InboundMailStatus.DONE)
        countAttachments(attachments, payload, captured.attachmentsRead)
        log.info(
            "Inbound mail {} for user {} became {} item(s) (attachments read: {})",
            mail.id, userId, captured.saved.titles.size, captured.attachmentsRead,
        )
        confirmation.sendSaved(userId, payload, captured.saved, captured.attachmentsRead)
    }

    /** An unreadable attachments column costs the attachments, not the email: capture from the text. */
    private fun readAttachments(mail: InboundMailEntity): List<InboundAttachment> {
        val stored = mail.attachments ?: return emptyList()
        return try {
            val json = checkNotNull(userCryptoService.decrypt(checkNotNull(mail.userId), stored))
            objectMapper.readValue(json, object : TypeReference<List<InboundAttachment>>() {})
        } catch (e: Exception) {
            log.error("Inbound mail {} has unreadable attachments; capturing from the text only", mail.id, e)
            emptyList()
        }
    }

    private class Captured(val saved: SavedCapture, val attachmentsRead: Boolean)

    /**
     * The AI draft when AI is available and produces one; the verbatim task otherwise. A model
     * error is worth a retry until the last attempt, which falls back rather than lose the email.
     */
    private fun capture(
        mail: InboundMailEntity,
        payload: InboundMailPayload,
        attachments: List<InboundAttachment>,
        lastAttempt: Boolean,
    ): Captured {
        val userId = checkNotNull(mail.userId)
        if (aiAccessService.isAiAvailableForUser(userId)) {
            val readable = attachments.takeIf {
                it.isNotEmpty() && !lastAttempt && inputModalitySupport.supportsAll(it.map { attachment -> attachment.kind })
            }.orEmpty()
            val outcome = try {
                suggestionAgent.quickAddDraftFromEmail(userId, renderForModel(payload), readable)
            } catch (e: AiDisabledException) {
                null
            } catch (e: AiTierNotGrantedException) {
                null
            } catch (e: AiUsageLimitExceededException) {
                null
            } catch (e: Exception) {
                if (!lastAttempt) throw e
                log.warn("Inbound mail {}: drafting failed on the last attempt, saving verbatim: {}", mail.id, e.message)
                null
            }
            if (outcome is SuggestionOutcome.Draft) {
                quickAddFlow.saveWithoutConfirmation(userId, outcome.items)?.let {
                    count("drafted")
                    return Captured(it, attachmentsRead = readable.isNotEmpty())
                }
            }
        }
        count("verbatim")
        val saved = checkNotNull(quickAddFlow.saveWithoutConfirmation(userId, listOf(verbatimTask(payload)))) {
            "the verbatim task did not validate"
        }
        return Captured(saved, attachmentsRead = false)
    }

    private fun finish(mail: InboundMailEntity, status: InboundMailStatus) {
        mail.status = status
        mail.payload = null
        mail.attachments = null
        mail.processedAt = clock.instant()
        repository.save(mail)
    }

    private fun renderForModel(payload: InboundMailPayload): String = buildString {
        append("Subject: ").append(payload.subject.ifBlank { "(no subject)" }).append('\n')
        payload.fromAddress?.let { address ->
            append("From: ")
            payload.fromName?.let { append(it).append(' ') }
            append('<').append(address).append(">\n")
        }
        append('\n').append(payload.body.take(MODEL_BODY_CHARS))
        if (payload.body.length > MODEL_BODY_CHARS) append("\n[…the rest of the email is cut off]")
    }

    private fun verbatimTask(payload: InboundMailPayload): CapturedItem.Task {
        val title = payload.subject.ifBlank { payload.body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty() }
            .trim().take(TITLE_CHARS).ifBlank { "Email" }
        val description = buildString {
            payload.fromAddress?.let { append("From: ").append(payload.fromName?.let { name -> "$name <$it>" } ?: it).append("\n\n") }
            append(payload.body.take(DESCRIPTION_CHARS))
        }.trim()
        return CapturedItem.Task(TaskDraft(title = title, description = description.ifBlank { null }))
    }

    private fun count(result: String) =
        meterRegistry.counter("tasker.inbound_mail.processed", "result", result).increment()

    /** `read` / `not_read` per stored attachment and kind; `skipped` for the ones the parser left out. */
    private fun countAttachments(attachments: List<InboundAttachment>, payload: InboundMailPayload, read: Boolean) {
        val result = if (read) "read" else "not_read"
        attachments.groupingBy { it.kind.name.lowercase() }.eachCount().forEach { (kind, n) ->
            meterRegistry.counter("tasker.inbound_mail.attachments", "kind", kind, "result", result).increment(n.toDouble())
        }
        if (payload.unreadAttachments.isNotEmpty()) {
            meterRegistry.counter("tasker.inbound_mail.attachments", "kind", "other", "result", "skipped")
                .increment(payload.unreadAttachments.size.toDouble())
        }
    }

    companion object {
        private const val POLL_INTERVAL_SECONDS = 15L
        private const val BATCH_SIZE = 20
        const val MAX_ATTEMPTS = 4
        private val BACKOFF = listOf(Duration.ofMinutes(1), Duration.ofMinutes(10), Duration.ofHours(1))
        private val DEDUP_RETENTION = Duration.ofDays(7)

        /** What the model reads: enough for a long forward, bounded for cost. */
        private const val MODEL_BODY_CHARS = 8_000
        private const val TITLE_CHARS = 200
        private const val DESCRIPTION_CHARS = 4_000
    }
}
