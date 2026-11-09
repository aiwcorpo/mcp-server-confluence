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

/** get_page_restrictions: users and groups by name, never an e-mail address or a user key. */
class PageRestrictionsToolTest extends ServerTestSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @BeforeEach
    void resetBackend() {
        BACKEND.reset();
    }

    @Test
    void usersAndGroupsAreReturnedByNameAndNothingElseOfTheUser() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "get_page_restrictions", Map.of("pageId", "1000"));
            assertThat(result.isError()).as(text(result)).isFalse();
            JsonNode restrictions = JSON.readTree(text(result));

            assertThat(restrictions.get("pageId").asString()).isEqualTo("1000");
            assertThat(restrictions.get("read").get("users")).extracting(user -> user.get("text").asString()).containsExactly("Anna Nowak");
            assertThat(restrictions.get("read").get("users").get(0).get("kind").asString()).isEqualTo("untrusted-data");
            assertThat(restrictions.get("read").get("groups")).extracting(group -> group.get("text").asString()).containsExactly("engineering");
            assertThat(restrictions.get("update").get("users")).extracting(user -> user.get("text").asString()).containsExactly("Jan Kowalski");
            assertThat(restrictions.get("update").get("groups")).isEmpty();
            assertThat(restrictions.get("read").get("truncated").asBoolean()).isFalse();
            assertThat(text(result)).doesNotContain("corp.invalid", "anowak", "jkowalski", "8a7f808a", "email", "userKey");
        }
        assertThat(BACKEND.requests().stream().filter(r -> r.path().endsWith("/restriction/byOperation")).findFirst().orElseThrow())
                .satisfies(request -> {
                    assertThat(request.authorization()).isEqualTo(PAT);
                    assertThat(request.query()).containsEntry("expand", "restrictions.user,restrictions.group");
                });
    }

    @Test
    void aPageWithoutRestrictionsHasEmptyLists() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode restrictions = JSON.readTree(text(call(client, "get_page_restrictions", Map.of("pageId", "1001"))));

            assertThat(restrictions.get("read").get("users")).isEmpty();
            assertThat(restrictions.get("update").get("groups")).isEmpty();
        }
    }

    @Test
    void aListThatStoppedAtItsLimitIsFlagged() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode restrictions = JSON.readTree(text(call(client, "get_page_restrictions", Map.of("pageId", "1700"))));

            assertThat(restrictions.get("update").get("groups")).hasSize(100);
            assertThat(restrictions.get("update").get("truncated").asBoolean()).isTrue();
            assertThat(restrictions.get("read").get("truncated").asBoolean()).isFalse();
        }
    }

    @Test
    void hiddenAndMissingPagesAreNotFoundOrDeniedAndNothingIsAskedFor() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult missing = call(client, "get_page_restrictions", Map.of("pageId", "1404"));
            McpSchema.CallToolResult restricted = call(client, "get_page_restrictions", Map.of("pageId", "2000"));

            assertNotFoundOrDenied(restricted);
            assertThat(text(restricted)).isEqualTo(text(missing));
        }
        assertThat(BACKEND.requests()).noneMatch(r -> r.path().endsWith("/restriction/byOperation"));
    }

    @Test
    void invalidIdsAreRejectedAndTheGroupAndCredentialAreRequired() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertRejectedArgument(call(client, "get_page_restrictions", Map.of("pageId", "10/../20")));
        }
        try (McpSyncClient client = clientWithToken(user -> user.username("jkowalski").groups(List.of("mcp-nobody")), PAT)) {
            assertThat(text(call(client, "get_page_restrictions", Map.of("pageId", "1000")))).contains("\"error\":\"access_denied\"");
        }
        BACKEND.reset();
        try (McpSyncClient client = clientWithCredential(null)) {
            assertCredentialRequired(call(client, "get_page_restrictions", Map.of("pageId", "1000")));
        }
        assertThat(BACKEND.authorizations()).isEmpty();
    }
}
