package pl.aiwcorpo.mcp.confluence.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** list_page_versions and diff_page_versions: history of a page and a server-side diff of two stored bodies. */
class VersionToolsTest extends ServerTestSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @BeforeEach
    void resetBackend() {
        BACKEND.reset();
    }

    private static JsonNode json(McpSchema.CallToolResult result) {
        assertThat(result.isError()).as(text(result)).isFalse();
        return JSON.readTree(text(result));
    }

    @Test
    void versionsAreListedNewestFirstWithoutThePrunedOne() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode list = json(call(client, "list_page_versions", Map.of("pageId", "1000")));

            assertThat(list.get("items")).extracting(item -> item.get("number").asInt()).containsExactly(7, 6, 5, 4, 2, 1);
            assertThat(list.get("items").get(1).get("message").get("text").asString()).isEqualTo("Edit 6");
            assertThat(list.get("items").get(1).get("author").get("kind").asString()).isEqualTo("untrusted-data");
            assertThat(list.get("items").get(1).get("author").get("text").asString()).isEqualTo("Admin");
            assertThat(list.get("items").get(0).get("when").asString()).isEqualTo("2026-10-20T10:15:30.000+02:00");
            assertThat(list.get("nextCursor").isNull()).isTrue();
        }
        assertThat(BACKEND.requests()).allSatisfy(request -> assertThat(request.authorization()).isEqualTo(PAT));
        assertThat(BACKEND.requests().stream().filter(r -> "historical".equals(r.query().get("status")))
                .map(r -> r.query().get("version"))).as("the current version is read as the page, not as history")
                .doesNotContain("7");
    }

    @Test
    void aLongHistoryPagesByVersionNumber() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult other = call(client, "list_page_versions", Map.of("pageId", "1000", "cursor", "not.acursor"));

            assertRejectedArgument(other);
        }
    }

    @Test
    void hiddenAndMissingPagesAreNotFoundOrDenied() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertNotFoundOrDenied(call(client, "list_page_versions", Map.of("pageId", "2000")));
            assertNotFoundOrDenied(call(client, "diff_page_versions", Map.of("pageId", "2000", "fromVersion", 1, "toVersion", 2)));
            assertNotFoundOrDenied(call(client, "diff_page_versions", Map.of("pageId", "1404", "fromVersion", 1, "toVersion", 2)));
        }
    }

    @Test
    void theDiffBetweenAnEarlierAndTheCurrentVersionIsUnified() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode diff = json(call(client, "diff_page_versions", Map.of("pageId", "1000", "fromVersion", 5, "toVersion", 7)));

            assertThat(diff.get("identical").asBoolean()).isFalse();
            assertThat(diff.get("diff").get("kind").asString()).isEqualTo("untrusted-data");
            assertThat(diff.get("diff").get("text").asString())
                    .contains("--- version 5", "+++ version 7", "-Old line.")
                    .doesNotContain("+Old line.");
            assertThat(diff.get("omitted")).isEmpty();
        }
    }

    @Test
    void equalVersionsGiveAnEmptyIdenticalDiff() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode diff = json(call(client, "diff_page_versions", Map.of("pageId", "1000", "fromVersion", 1, "toVersion", 5)));

            assertThat(diff.get("identical").asBoolean()).isTrue();
            assertThat(diff.get("diff").get("text").asString()).isEmpty();
        }
    }

    @Test
    void aVersionThatDoesNotExistIsNotFoundOrDenied() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertNotFoundOrDenied(call(client, "diff_page_versions", Map.of("pageId", "1000", "fromVersion", 3, "toVersion", 7)));
            assertNotFoundOrDenied(call(client, "diff_page_versions", Map.of("pageId", "1000", "fromVersion", 1, "toVersion", 8)));
            assertRejectedArgument(call(client, "diff_page_versions", Map.of("pageId", "1000", "fromVersion", 0, "toVersion", 7)));
        }
    }

    @Test
    void withoutCredentialTheVersionToolsAskToConnectTheAccount() {
        try (McpSyncClient client = clientWithCredential(null)) {
            assertCredentialRequired(call(client, "list_page_versions", Map.of("pageId", "1000")));
            assertCredentialRequired(call(client, "diff_page_versions", Map.of("pageId", "1000", "fromVersion", 1, "toVersion", 2)));
        }
        assertThat(BACKEND.authorizations()).isEmpty();
    }
}
