package dev.itayp.tasker.planning

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus

/**
 * The user asked to start a planning session but has nothing the planner could schedule — an empty
 * backlog, or one holding only tutorial cards, future-dated tasks and assistant-hidden tasks.
 *
 * A 409 rather than a 400: the request is well-formed, the account state just isn't ready for it.
 * Revising an existing plan is unaffected — that needs no candidates.
 */
@ResponseStatus(HttpStatus.CONFLICT)
class NoPlannableTasksException : RuntimeException("There's nothing to plan yet — add a task first.")
