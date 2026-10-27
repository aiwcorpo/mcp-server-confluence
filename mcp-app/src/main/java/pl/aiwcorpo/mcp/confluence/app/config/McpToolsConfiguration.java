package pl.aiwcorpo.mcp.confluence.app.config;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pl.aiwcorpo.mcp.confluence.tools.ConfluencePageTools;
import pl.aiwcorpo.mcp.confluence.tools.ConstrainedToolCallbacks;

/**
 * The tool set of the server, as an explicit list: the MCP server exposes only tools registered through a
 * {@link ToolCallbackProvider} (an {@code @Tool} bean alone answers "Unknown tool"), and a reviewed list
 * keeps the public surface from growing by accident. {@link ConstrainedToolCallbacks} instead of
 * {@code MethodToolCallbackProvider} so the published inputSchema carries the argument limits.
 */
@Configuration
public class McpToolsConfiguration {

    @Bean
    ToolCallbackProvider mcpTools(ConfluencePageTools pageTools) {
        return ToolCallbackProvider.from(ConstrainedToolCallbacks.of(pageTools));
    }
}
