package dev.itayp.tasker

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class TaskBoardApplication

fun main(args: Array<String>) {
	runApplication<TaskBoardApplication>(*args)
}
