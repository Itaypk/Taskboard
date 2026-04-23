package pk.itay.whoami

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
                "spring.sql.init.data-locations=classpath:sql/data-postgresql.sql"
            ).applyTo(configurableApplicationContext.environment)
        }
    }

    companion object {
        private val logger: Logger = LoggerFactory.getLogger(WhoAmIApplicationIT::class.java)

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
