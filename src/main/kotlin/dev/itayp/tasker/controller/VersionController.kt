package dev.itayp.tasker.controller

import dev.itayp.tasker.config.AppVersion
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Public, unauthenticated: which commit this process is running. The deploy in the ops repo
 * (`Itaypk/itayp-dev`, `deploy-app.yml`'s verify-commit) probes it right after a restart and rolls
 * back unless `commit` equals the release's full sha. That catches a stale or wrongly-built JAR that
 * would otherwise come up healthy. The source is public, so the commit reveals nothing new.
 */
@RestController
class VersionController(private val appVersion: AppVersion) {

    @GetMapping("/api/version")
    fun version(): Map<String, String> = mapOf("commit" to appVersion.commit)
}
