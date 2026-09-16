package dev.itayp.tasker.model.request

/**
 * The rule for a task's `url`, in one place because three callers need it: the create DTO's bean
 * validation, [dev.itayp.tasker.service.BacklogTaskService] (where the update rule has to live —
 * it depends on the stored value, which bean validation can't see), and the external API's
 * up-front check.
 *
 * Only `http(s)` links may be **written**. `TutorialSeeder` writes in-app deep links
 * (`/settings/general`, `app:open-planner`) straight to the repository, and the SPA resolves those
 * into navigation and actions — so a caller able to set one could plant a link that runs an in-app
 * action in another board member's session. An update carrying the stored value back **unchanged**
 * is accepted, or those seeded cards could never be completed or edited: every task write is a
 * full replace that echoes the link.
 */
object TaskUrl {
    /** Blank or an `http(s)` link. Referenced from an annotation, so it must stay a `const`. */
    const val WEB_URL_REGEX = "^$|^https?://.*"

    const val REQUIREMENT_MESSAGE = "Link must start with http:// or https://"

    private val webUrl = Regex(WEB_URL_REGEX)

    /** Whether [candidate] may be stored. [current] is the stored value, or null when creating. */
    fun isAcceptable(candidate: String?, current: String? = null): Boolean =
        candidate == null || webUrl.matches(candidate) || candidate == current
}
