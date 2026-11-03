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

/** The public surface: the reviewed tool set, risk hints, closed input schemas with limits. */
class ToolListingTest extends ServerTestSupport {

    @Test
    @SuppressWarnings("unchecked")
    void listsTheReviewedToolsWithClosedSchemasAndLimits() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            Map<String, McpSchema.Tool> tools = client.listTools().tools().stream()
                    .collect(Collectors.toMap(McpSchema.Tool::name, Function.identity()));

            assertThat(tools).containsOnlyKeys(ManifestTest.RISKS.keySet());
            ManifestTest.RISKS.forEach((name, risk) -> {
                McpSchema.ToolAnnotations hints = tools.get(name).annotations();
                assertThat(hints.readOnlyHint()).as(name).isEqualTo("READ_ONLY".equals(risk));
                assertThat(hints.destructiveHint()).as(name).isEqualTo("DESTRUCTIVE".equals(risk));
            });
            tools.values().forEach(tool -> {
                assertThat(tool.inputSchema().get("additionalProperties")).as(tool.name()).isEqualTo(false);
                assertThat(tool.description()).doesNotContainIgnoringCase("always").doesNotContainIgnoringCase("must");
                properties(tool).forEach((name, property) -> {
                    Map<String, Object> schema = (Map<String, Object>) property;
                    boolean bounded = "string".equals(schema.get("type")) ? schema.containsKey("maxLength")
                            : schema.containsKey("maximum");
                    assertThat(bounded).as(tool.name() + "." + name + " has a limit").isTrue();
                });
            });

            assertProperty(tools.get("search_pages"), "space", 255, ConfluenceIds.SPACE_KEY);
            assertProperty(tools.get("search_pages"), "text", 200, null);
            assertProperty(tools.get("get_page"), "pageId", 19, ConfluenceIds.PAGE_ID);
            assertProperty(tools.get("get_space"), "space", 255, ConfluenceIds.SPACE_KEY);
            assertProperty(tools.get("create_page"), "space", 255, ConfluenceIds.SPACE_KEY);
            assertProperty(tools.get("create_page"), "parentId", 19, ConfluenceIds.PAGE_ID);
            assertProperty(tools.get("create_page"), "title", 255, null);
            assertProperty(tools.get("create_page"), "body", 20_000, null);
            assertProperty(tools.get("update_page"), "body", 20_000, null);
            assertProperty(tools.get("list_pages"), "cursor", 1024, null);
            assertThat((List<Object>) tools.get("create_page").inputSchema().get("required"))
                    .containsExactlyInAnyOrder("space", "parentId", "title", "body");
            assertThat((List<Object>) tools.get("search_pages").inputSchema().get("required")).containsExactly("space");
            assertThat((List<Object>) tools.get("update_page").inputSchema().get("required"))
                    .containsExactlyInAnyOrder("pageId", "expectedVersion", "mode", "body");
            assertThat(properties(tools.get("search_pages"))).containsOnlyKeys("space", "text", "title", "label",
                    "orderBy", "cursor");
            assertThat(properties(tools.get("get_page"))).containsOnlyKeys("pageId");
            assertThat(properties(tools.get("get_space"))).containsOnlyKeys("space");
            assertThat(properties(tools.get("update_page"))).containsOnlyKeys("pageId", "expectedVersion", "mode",
                    "body", "title");
            Map<String, Object> version = (Map<String, Object>) properties(tools.get("update_page")).get("expectedVersion");
            assertThat(version).containsEntry("type", "integer").containsKeys("minimum", "maximum");
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertProperty(McpSchema.Tool tool, String name, int maxLength, String pattern) {
        Map<String, Object> property = (Map<String, Object>) properties(tool).get(name);
        assertThat(property).as(tool.name() + "." + name).containsEntry("type", "string")
                .containsEntry("maxLength", maxLength).containsKey("pattern");
        if (pattern != null) {
            assertThat(property).containsEntry("pattern", pattern);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(McpSchema.Tool tool) {
        return (Map<String, Object>) tool.inputSchema().get("properties");
    }
}
