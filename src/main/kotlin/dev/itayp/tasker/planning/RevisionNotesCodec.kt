package dev.itayp.tasker.planning

import org.slf4j.LoggerFactory
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue

/**
 * (De)serializes the plaintext of `planning_session.revision_notes`: a JSON array of [RevisionNote]
 * in append order. Encryption happens around it, in [PlanningSessionService].
 *
 * A standalone mapper rather than the injected one, because [toDomain] is a plain extension function
 * and the format is private to this column.
 */
internal object RevisionNotesCodec {
    private val log = LoggerFactory.getLogger(RevisionNotesCodec::class.java)
    private val mapper = jacksonObjectMapper()

    fun encode(notes: List<RevisionNote>): String = mapper.writeValueAsString(notes)

    /**
     * For appending: throws on unreadable input, so an append never replaces notes it couldn't read
     * with a one-element list.
     */
    fun decodeStrict(json: String?): List<RevisionNote> =
        if (json.isNullOrBlank()) emptyList() else mapper.readValue<List<RevisionNote>>(json)

    /** For display: unreadable input yields no notes, since hiding them beats failing to load the plan. */
    fun decode(json: String?): List<RevisionNote> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching { decodeStrict(json) }
            .getOrElse { e ->
                log.warn("Unreadable revision notes, ignoring them: {}", e.javaClass.simpleName)
                emptyList()
            }
    }
}
