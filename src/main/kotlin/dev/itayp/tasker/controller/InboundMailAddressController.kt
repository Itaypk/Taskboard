package dev.itayp.tasker.controller

import dev.itayp.tasker.config.InboundMailProperties
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.InboundMailAddress
import dev.itayp.tasker.service.InboundMailAddressService
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** [address] and [createdAt] are null when the user has no address (never created, or turned off). */
data class InboundMailAddressResponse(
    val address: String?,
    val createdAt: String?,
)

/**
 * The signed-in user's inbound email address. On the session chain (with CSRF), like the API
 * tokens: only the user, in the app, can see, rotate or turn off the address. Every route answers
 * 404 while the feature is off on this instance (`tasker.inbound-mail`).
 */
@RestController
@RequestMapping("/api/v1/inbound-mail-address")
class InboundMailAddressController(
    private val inboundMailAddressService: InboundMailAddressService,
    private val properties: InboundMailProperties,
) {

    @GetMapping
    fun getAddress(@AuthenticationPrincipal principal: TaskerPrincipal): ResponseEntity<*> {
        if (!properties.active) return notEnabled()
        return ResponseEntity.ok(inboundMailAddressService.getAddress(principal.userId).toResponse())
    }

    /** Creates the address, or replaces the current one (the old one stops working at once). */
    @PostMapping
    fun rotateAddress(@AuthenticationPrincipal principal: TaskerPrincipal): ResponseEntity<*> {
        if (!properties.active) return notEnabled()
        return ResponseEntity.ok(inboundMailAddressService.rotate(principal.userId).toResponse())
    }

    /** Turns the address off. Idempotent: 204 whether or not there was one. */
    @DeleteMapping
    fun revokeAddress(@AuthenticationPrincipal principal: TaskerPrincipal): ResponseEntity<*> {
        if (!properties.active) return notEnabled()
        inboundMailAddressService.revoke(principal.userId)
        return ResponseEntity.noContent().build<Void>()
    }

    private fun notEnabled(): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Inbound email is not enabled on this instance.")
        )
}

private fun InboundMailAddress?.toResponse() = InboundMailAddressResponse(
    address = this?.address,
    createdAt = this?.createdAt?.toString(),
)
