package dev.itayp.tasker.config

import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@Configuration
@EnableJpaRepositories(basePackages = [
    "dev.itayp.tasker.repository",
    "dev.itayp.tasker.ai.conversation",
    "dev.itayp.tasker.planning",
    "dev.itayp.tasker.crypto",
])
class JpaConfiguration