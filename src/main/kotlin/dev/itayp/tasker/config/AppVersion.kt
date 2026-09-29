package dev.itayp.tasker.config

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.stereotype.Component

/**
 * The running build's identity: the full git commit baked into `build-info.properties` by
 * `buildInfo()` in `build.gradle.kts`, falling back to the build time, then "dev" (dev/test runs
 * have no generated build-info). One value, read by both the SPA's redeploy check (the sync poll)
 * and the ops repo's deploy, which asserts the restarted process reports the commit it shipped.
 */
@Component
class AppVersion(buildProperties: ObjectProvider<BuildProperties>) {
    val commit: String = buildProperties.getIfAvailable()
        ?.let { it.get("commit") ?: it.time?.toString() }
        ?: "dev"
}
