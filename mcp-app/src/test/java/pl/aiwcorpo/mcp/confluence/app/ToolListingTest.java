package pl.aiwcorpo.mcp.confluence.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.confluence.core.domain.ConfluenceIds;

/** The public surface: exactly four tools, risk hints, closed input schemas with limits. */
class ToolListingTest extends ServerTestSupport {

    @Test
    @SuppressWarnings("unchecked")
    void listsTheFourToolsWithClosedSchemasAndLimits() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            Map<String, McpSchema.Tool> tools = client.listTools().tools().stream()
                    .collect(Collectors.toMap(McpSchema.Tool::name, Function.identity()));

            assertThat(tools).containsOnlyKeys("search_pages", "get_page", "get_space", "create_page");
            assertThat(tools.get("search_pages").annotations().readOnlyHint()).isTrue();
            assertThat(tools.get("get_page").annotations().readOnlyHint()).isTrue();
            assertThat(tools.get("get_space").annotations().readOnlyHint()).isTrue();
            tools.values().forEach(tool -> {
                assertThat(tool.inputSchema().get("additionalProperties")).as(tool.name()).isEqualTo(false);
                assertThat(tool.description()).doesNotContainIgnoringCase("always").doesNotContainIgnoringCase("must");
            });

            assertProperty(tools.get("search_pages"), "space", 255, ConfluenceIds.SPACE_KEY);
            assertProperty(tools.get("search_pages"), "text", 200, null);
            assertProperty(tools.get("get_page"), "pageId", 19, ConfluenceIds.PAGE_ID);
            assertProperty(tools.get("get_space"), "space", 255, ConfluenceIds.SPACE_KEY);
            assertProperty(tools.get("create_page"), "space", 255, ConfluenceIds.SPACE_KEY);
            assertProperty(tools.get("create_page"), "parentId", 19, ConfluenceIds.PAGE_ID);
            assertProperty(tools.get("create_page"), "title", 255, null);
            assertProperty(tools.get("create_page"), "body", 20_000, null);
            assertThat((List<Object>) tools.get("create_page").inputSchema().get("required"))
                    .containsExactlyInAnyOrder("space", "parentId", "title", "body");
            assertThat(properties(tools.get("search_pages"))).containsOnlyKeys("space", "text");
            assertThat(properties(tools.get("get_page"))).containsOnlyKeys("pageId");
            assertThat(properties(tools.get("get_space"))).containsOnlyKeys("space");
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertProperty(McpSchema.Tool tool, String name, int maxLength, String pattern) {
        Map<String, Object> property = (Map<String, Object>) properties(tool).get(name);
        assertThat(property).as(tool.name() + "." + name).containsEntry("type", "string")
                .containsEntry("minLength", 1).containsEntry("maxLength", maxLength).containsKey("pattern");
        if (pattern != null) {
            assertThat(property).containsEntry("pattern", pattern);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(McpSchema.Tool tool) {
        return (Map<String, Object>) tool.inputSchema().get("properties");
    }
}
