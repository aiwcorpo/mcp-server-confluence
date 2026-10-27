package pl.aiwcorpo.mcp.confluence.app.config;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pl.aiwcorpo.mcp.confluence.tools.ConfluenceTool;

/**
 * The tool set of the server, as an explicit list: the MCP server exposes only tools registered through a
 * {@link ToolCallbackProvider} (an {@code @Tool} bean alone answers "Unknown tool"), and a reviewed list
 * keeps the public surface from growing by accident.
 */
@Configuration
public class McpToolsConfiguration {

    @Bean
    ToolCallbackProvider mcpTools(ConfluenceTool confluenceTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(confluenceTool)
                .build();
    }
}
