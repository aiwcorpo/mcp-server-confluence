package pl.aiwcorpo.mcp.confluence.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** get_space_page_tree: pages of a space in tree order, bounded in depth and count, judged page by page. */
class SpacePageTreeToolTest extends ServerTestSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @BeforeEach
    void resetBackend() {
        BACKEND.reset();
    }

    private static JsonNode json(McpSchema.CallToolResult result) {
        assertThat(result.isError()).as(text(result)).isFalse();
        return JSON.readTree(text(result));
    }

    private static JsonNode item(JsonNode tree, String id) {
        return tree.get("items").valueStream().filter(i -> id.equals(i.get("id").asString())).findFirst().orElseThrow();
    }

    @Test
    void aSpaceComesBackInTreeOrderWithParentsAndDepths() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode tree = json(call(client, "get_space_page_tree", Map.of("space", "ENG")));

            assertThat(tree.get("items")).extracting(i -> i.get("id").asString()).startsWith("1000", "1001", "1700")
                    .as("the blog post of the space is not a page").doesNotContain("1002");
            assertThat(item(tree, "1000").get("parentId").isNull()).isTrue();
            assertThat(item(tree, "1000").get("depth").asInt()).isZero();
            assertThat(item(tree, "1001").get("parentId").asString()).isEqualTo("1000");
            assertThat(item(tree, "1001").get("depth").asInt()).isEqualTo(1);
            assertThat(item(tree, "1001").get("title").get("kind").asString()).isEqualTo("untrusted-data");
            assertThat(tree.get("truncated").asBoolean()).isFalse();
            assertThat(tree.get("omitted")).isEmpty();
            assertThat(tree.get("nextCursor").isNull()).isTrue();
        }
        assertThat(BACKEND.requests().stream().filter(r -> r.path().equals("/rest/api/content")
                && "ENG".equals(r.query().get("spaceKey"))).findFirst().orElseThrow().query())
                .containsEntry("type", "page").containsEntry("limit", "50").containsEntry("expand", "space,ancestors");
        assertThat(BACKEND.requests()).allSatisfy(request -> assertThat(request.authorization()).isEqualTo(PAT));
    }

    @Test
    void theDepthIsCappedAndTheCutIsReported() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode tree = json(call(client, "get_space_page_tree", Map.of("space", "ENG", "maxDepth", 1)));

            assertThat(tree.get("items")).extracting(i -> i.get("id").asString()).containsExactly("1000");
            assertThat(tree.get("truncated").asBoolean()).isTrue();
            assertThat(tree.get("omitted")).extracting(JsonNode::asString).containsExactly("depth_limit");
        }
    }

    @Test
    void aFlatSpaceListsEveryPageAsARoot() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode tree = json(call(client, "get_space_page_tree", Map.of("space", "BIG")));

            assertThat(tree.get("items")).hasSize(30).allSatisfy(i -> {
                assertThat(i.get("depth").asInt()).isZero();
                assertThat(i.get("parentId").isNull()).isTrue();
            });
            assertThat(tree.get("truncated").asBoolean()).isFalse();
        }
    }

    @Test
    void aHugeSpaceIsCutAtTwoHundredPagesAndFlagged() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode tree = json(call(client, "get_space_page_tree", Map.of("space", "HUGE")));

            assertThat(tree.get("items")).hasSize(200);
            assertThat(tree.get("truncated").asBoolean()).isTrue();
            assertThat(tree.get("omitted")).extracting(JsonNode::asString).containsExactly("page_limit");
        }
        assertThat(BACKEND.requests().stream().filter(r -> r.path().equals("/rest/api/content")
                && "HUGE".equals(r.query().get("spaceKey"))).map(r -> r.query().get("start"))).containsExactly("0", "50", "100", "150", "200");
    }

    @Test
    void aSpaceTheCallerMayNotReadIsNotFoundOrDenied() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult missing = call(client, "get_space_page_tree", Map.of("space", "NOSUCH"));
            McpSchema.CallToolResult restricted = call(client, "get_space_page_tree", Map.of("space", "HR"));

            assertNotFoundOrDenied(restricted);
            assertThat(text(restricted)).isEqualTo(text(missing));
        }
        assertThat(BACKEND.requests()).noneMatch(r -> r.path().equals("/rest/api/content"));
    }

    @Test
    void invalidArgumentsAreRejectedBeforeConfluence() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertRejectedArgument(call(client, "get_space_page_tree", Map.of("space", "ENG", "maxDepth", 0)));
            assertRejectedArgument(call(client, "get_space_page_tree", Map.of("space", "ENG", "maxDepth", 11)));
            assertRejectedArgument(call(client, "get_space_page_tree", Map.of("space", "~jkowalski")));
            assertRejectedArgument(call(client, "get_space_page_tree", Map.of("space", "ENG", "cursor", "x")));
        }
        assertThat(BACKEND.authorizations()).isEmpty();
    }

    @Test
    void aCallerWithoutTheReadGroupIsDeniedAndWithoutCredentialAsksToConnect() {
        try (McpSyncClient client = clientWithToken(user -> user.username("jkowalski").groups(List.of("mcp-nobody")), PAT)) {
            assertThat(text(call(client, "get_space_page_tree", Map.of("space", "ENG")))).contains("\"error\":\"access_denied\"");
        }
        try (McpSyncClient client = clientWithCredential(null)) {
            assertCredentialRequired(call(client, "get_space_page_tree", Map.of("space", "ENG")));
        }
        assertThat(BACKEND.authorizations()).isEmpty();
    }
}
