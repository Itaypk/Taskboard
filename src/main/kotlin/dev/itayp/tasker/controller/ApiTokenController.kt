package dev.itayp.tasker.controller

import dev.itayp.tasker.jpa.ApiTokenEntity
import dev.itayp.tasker.jpa.ApiTokenScope
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.ApiTokenService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class CreateApiTokenRequest(
    @field:NotBlank @field:Size(max = 100) val name: String,
    /** `read` or `write`. Defaults to read — the safer choice if a caller omits it. */
    val scope: String = ApiTokenScope.READ,
)

data class ApiTokenResponse(
    val id: String,
    val name: String,
    val prefix: String,
    val scope: String,
    val createdAt: String,
    val lastUsedAt: String?,
    val expiresAt: String?,
)

/** Returned only from create — [token] is the one and only time the plaintext is available. */
data class CreatedApiTokenResponse(
    val token: String,
    val apiToken: ApiTokenResponse,
)

/**
 * Manages the API tokens used by the external API.
 *
 * Deliberately mounted on `/api/v1/**` — the **session** chain — and not under `/api/external/**`.
 * Minting a token therefore requires a logged-in browser session with a valid CSRF token, so a
 * leaked API token can never mint another one, widen its own scope, or enumerate its siblings.
 */
@RestController
@RequestMapping("/api/v1/api-tokens")
class ApiTokenController(
    private val apiTokenService: ApiTokenService,
) {

    @GetMapping
    fun listTokens(
        @AuthenticationPrincipal principal: TaskerPrincipal,
    ): ResponseEntity<List<ApiTokenResponse>> =
        ResponseEntity.ok(apiTokenService.listTokens(principal.userId).map { it.toResponse() })

    @PostMapping
    fun createToken(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @Valid @RequestBody request: CreateApiTokenRequest,
    ): ResponseEntity<*> {
        val scope = request.scope.lowercase()
        if (scope !in ApiTokenScope.allowedValues) {
            return ResponseEntity.badRequest().body(
                ProblemDetail.forStatusAndDetail(
                    HttpStatus.BAD_REQUEST,
                    "Unknown scope '${request.scope}'. Allowed: ${ApiTokenScope.allowedValues.joinToString()}.",
                )
            )
        }
        val created = apiTokenService.createToken(principal.userId, request.name, scope)
        return ResponseEntity.status(HttpStatus.CREATED).body(
            CreatedApiTokenResponse(token = created.plaintext, apiToken = created.token.toResponse())
        )
    }

    @DeleteMapping("/{id}")
    fun revokeToken(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable id: UUID,
    ): ResponseEntity<Void> =
        if (apiTokenService.revokeToken(principal.userId, id)) ResponseEntity.noContent().build()
        else ResponseEntity.notFound().build()
}

private fun ApiTokenEntity.toResponse() = ApiTokenResponse(
    id = id.toString(),
    name = name ?: "",
    prefix = prefix ?: "",
    scope = scope ?: ApiTokenScope.READ,
    createdAt = createdAt?.toString() ?: "",
    lastUsedAt = lastUsedAt?.toString(),
    expiresAt = expiresAt?.toString(),
)
