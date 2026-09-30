package dev.itayp.tasker

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.test.util.TestPropertyValues
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.testcontainers.containers.JdbcDatabaseContainer
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.postgresql.PostgreSQLContainer

class AbstractIntegrationTest {

    internal class Initializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
        override fun initialize(configurableApplicationContext: ConfigurableApplicationContext) {
            postgresContainer.start()

            val jdbcUrl = "jdbc:postgresql://localhost:${postgresContainer.firstMappedPort}/$DATABASE_NAME"

            TestPropertyValues.of(
                "spring.datasource.username=${postgresContainer.username}",
                "spring.datasource.password=${postgresContainer.password}",
                "spring.datasource.driverClassName=org.postgresql.Driver",
                "spring.datasource.url=$jdbcUrl",
                "tasker.telegram.enabled=false",
                // Required under the prod profile (ProductionConfigValidator).
                "tasker.app.base-url=https://test.invalid",
                // Prod profile fails fast if TASKER_DATA_KEK is unset; inject a
                // deterministic test key so encryption-aware code paths exercise
                // real crypto without leaking the prod KEK into test fixtures.
                "tasker.encryption.kek=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
            ).applyTo(configurableApplicationContext.environment)
        }
    }

    companion object {
        private val logger: Logger = LoggerFactory.getLogger(AbstractIntegrationTest::class.java)

        private const val DATABASE_USERNAME = "taskboard_test"
        private const val DATABASE_PASSWORD = "Password"
        private const val DATABASE_NAME = "taskboard"

        val postgresContainer: JdbcDatabaseContainer<*> = PostgreSQLContainer(PostgreSQLContainer.IMAGE)
            .withDatabaseName(DATABASE_NAME)
            .withUsername(DATABASE_USERNAME)
            .withPassword(DATABASE_PASSWORD)
            .withLogConsumer(Slf4jLogConsumer(logger))
    }
}
