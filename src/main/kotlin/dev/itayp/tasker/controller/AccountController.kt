package dev.itayp.tasker.controller

import dev.itayp.tasker.model.response.AccountExportResponse
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.AccountService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
@RequestMapping("/api/v1/account")
class AccountController(private val accountService: AccountService) {

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
}
