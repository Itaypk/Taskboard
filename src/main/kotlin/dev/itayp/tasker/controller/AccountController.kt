package dev.itayp.tasker.controller

import dev.itayp.tasker.model.response.AccountExportResponse
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.AccountImportService
import dev.itayp.tasker.service.AccountService
import dev.itayp.tasker.service.ImportErrorCategory
import dev.itayp.tasker.service.ImportException
import dev.itayp.tasker.service.ImportSummary
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
@RequestMapping("/api/v1/account")
class AccountController(
    private val accountService: AccountService,
    private val accountImportService: AccountImportService,
) {
    private val log = LoggerFactory.getLogger(AccountController::class.java)

    @DeleteMapping
    fun deleteAccount(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<Void> {
        accountService.deleteAccount(principal.userId)
        // Clear in-process session state; DB row is already gone.
        SecurityContextHolder.clearContext()
        request.getSession(false)?.invalidate()
        response.addHeader("Set-Cookie", "SESSION=; Max-Age=0; Path=/; HttpOnly")
        response.addHeader("Set-Cookie", "XSRF-TOKEN=; Max-Age=0; Path=/")
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/export")
    fun exportAccount(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        response: HttpServletResponse,
    ): AccountExportResponse {
        val filename = "backlog-fyi-export-${LocalDate.now()}.json"
        response.setHeader("Content-Disposition", "attachment; filename=\"$filename\"")
        return accountService.exportAccount(principal.userId)
    }

    @PostMapping("/import")
    fun importAccount(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @Valid @RequestBody payload: AccountExportResponse,
    ): ResponseEntity<ImportSummary> {
        val summary = accountImportService.import(principal.userId, payload)
        return ResponseEntity.ok(summary)
    }

    /**
     * A categorized import rejection. The body carries both the technical [ImportException.message]
     * (for the dialog's secondary line and our logs) and a stable `code` the frontend maps to a
     * plain-language headline. The category also picks the HTTP status.
     */
    @ExceptionHandler(ImportException::class)
    fun handleImportException(ex: ImportException): ResponseEntity<Map<String, String>> {
        val status = when (ex.category) {
            ImportErrorCategory.UNSUPPORTED_VERSION,
            ImportErrorCategory.CORRUPTED_FILE -> HttpStatus.BAD_REQUEST
            ImportErrorCategory.ACCOUNT_NOT_EMPTY -> HttpStatus.CONFLICT
            ImportErrorCategory.INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR
        }
        log.info("Account import rejected ({}): {}", ex.category, ex.message)
        return ResponseEntity.status(status)
            .body(mapOf("error" to (ex.message ?: "Import failed"), "code" to ex.category.name))
    }

    /**
     * Content-validation failures inside the import service still throw plain [IllegalArgumentException]
     * (unknown swatch/color, out-of-range index, bad date, invalid settings). They all mean the same
     * thing to a user — the file can't be read — so map them to the `CORRUPTED_FILE` category.
     */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadImport(ex: IllegalArgumentException): ResponseEntity<Map<String, String>> {
        log.info("Account import rejected as malformed: {}", ex.message)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(mapOf("error" to (ex.message ?: "Invalid import payload"), "code" to ImportErrorCategory.CORRUPTED_FILE.name))
    }

    /**
     * Safety net for a unique-constraint clash during import (e.g. an email already held by another
     * account that the service's own guard somehow missed). Map to 409 with a generic message
     * rather than leaking a raw DB error as a 500. We don't echo the constraint detail — it can
     * carry another user's email hash. Tagged `INTERNAL_ERROR` so the UI offers a support link.
     */
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrity(ex: DataIntegrityViolationException): ResponseEntity<Map<String, String>> {
        log.warn("Account import hit a data-integrity violation", ex)
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(mapOf(
                "error" to "Import conflicts with existing data and could not be completed.",
                "code" to ImportErrorCategory.INTERNAL_ERROR.name,
            ))
    }
}
