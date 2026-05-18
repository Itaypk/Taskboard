package dev.itayp.tasker.config

import dev.itayp.tasker.ai.tool.AiTool
import dev.itayp.tasker.ai.tool.ToolRegistry
import org.springframework.beans.factory.InitializingBean
import org.springframework.context.annotation.Configuration

/**
 * Registers every [AiTool] bean discovered in the application context into the
 * shared [ToolRegistry]. This avoids each tool needing its own boilerplate
 * `@PostConstruct` and keeps tool wiring purely declarative.
 */
@Configuration
class AiToolsConfiguration(
    private val toolRegistry: ToolRegistry,
    private val tools: List<AiTool>,
) : InitializingBean {

    override fun afterPropertiesSet() {
        tools.forEach(toolRegistry::register)
    }
}
