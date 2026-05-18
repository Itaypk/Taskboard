package dev.itayp.tasker.config

import dev.itayp.tasker.service.UserAuthService
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile("dev")
class DevDataInitializer(
    private val userAuthService: UserAuthService,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        log.info("Ensuring dev user '$DEV_USER_ID' exists")
        userAuthService.ensureDevUser(DEV_USER_ID, DEV_USER_TELEGRAM_ID)
    }

    companion object {
        private val log = LoggerFactory.getLogger(DevDataInitializer::class.java)
    }
}
