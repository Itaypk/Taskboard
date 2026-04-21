package dev.itayp.tasker.config

import dev.itayp.tasker.repository.BacklogTaskCategoryRepository
import dev.itayp.tasker.service.UserService
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile("dev")
class DevDataInitializer(
    private val userService: UserService,
    private val categoryRepository: BacklogTaskCategoryRepository,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        if (categoryRepository.findAllByUserId(TEST_USER).isEmpty()) {
            log.info("Initializing data for dev user '$TEST_USER'")
            userService.initializeNewUser(TEST_USER)
        }
    }

    companion object {
        private const val TEST_USER = "test"
        private val log = LoggerFactory.getLogger(DevDataInitializer::class.java)
    }
}
