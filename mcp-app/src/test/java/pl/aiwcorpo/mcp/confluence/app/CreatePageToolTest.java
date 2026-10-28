package pl.aiwcorpo.mcp.confluence.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@code create_page}: plain text body only, parent checked to be in the named space. */
class CreatePageToolTest extends ServerTestSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @BeforeEach
    void resetBackend() {
        BACKEND.reset();
    }

    @Test
    void createsThePageUnderTheParentWithTheBodyEscaped() {
        try (McpSyncClient client = writerWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "create_page", Map.of("space", "ENG", "parentId", "1000",
                    "title", "Retro 2.1", "body", """
                            Went well: <b>release</b>
                            <ac:structured-macro ac:name="include"><ac:parameter ac:name="">HR:Salaries</ac:parameter></ac:structured-macro>

                            Next: & more"""));

            assertThat(result.isError()).isFalse();
            assertThat(text(result)).contains("\"id\":\"5001\"", "\"spaceKey\":\"ENG\"", "\"title\":{\"kind\":\"untrusted-data\",\"format\":\"text/plain\",\"text\":\"Retro 2.1\",\"truncated\":false}",
                    "\"version\":1").doesNotContain("Went well", "_links");
        }
        assertThat(BACKEND.requests()).hasSize(3).allSatisfy(request -> assertThat(request.authorization()).isEqualTo(PAT));
        assertThat(BACKEND.requests().get(0).path()).as("the parent as the policy judges it").isEqualTo("/rest/api/content/1000");
        assertThat(BACKEND.requests().get(1).path()).isEqualTo("/rest/api/space/ENG");
        StubBackend.Request create = BACKEND.requests().get(2);
        assertThat(create.method()).isEqualTo("POST");
        JsonNode sent = JSON.readTree(create.body());
        assertThat(sent.path("type").asString()).isEqualTo("page");
        assertThat(sent.path("space").path("key").asString()).isEqualTo("ENG");
        assertThat(sent.path("ancestors").path(0).path("id").asString()).isEqualTo("1000");
        assertThat(sent.path("body").path("storage").path("representation").asString()).isEqualTo("storage");
        assertThat(sent.path("body").path("storage").path("value").asString()).isEqualTo(
                "<p>Went well: &lt;b&gt;release&lt;/b&gt;<br />&lt;ac:structured-macro ac:name=&quot;include&quot;&gt;"
                + "&lt;ac:parameter ac:name=&quot;&quot;&gt;HR:Salaries&lt;/ac:parameter&gt;&lt;/ac:structured-macro&gt;</p>"
                + "<p>Next: &amp; more</p>");
    }

    @Test
    void aReplyWithoutTheSpaceStillReportsTheCreatedPage() {
        try (McpSyncClient client = writerWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "create_page", Map.of("space", "ENG", "parentId", "1000",
                    "title", "No space in reply", "body", "text"));

            assertThat(result.isError()).isFalse();
            assertThat(text(result)).contains("\"id\":\"5001\"", "\"spaceKey\":\"ENG\"");
        }
    }

    @Test
    void isListedAsAnUpdateNotAsReadOnly() {
        try (McpSyncClient client = writerWithCredential(PAT)) {
            McpSchema.Tool tool = client.listTools().tools().stream()
                    .filter(t -> t.name().equals("create_page")).findFirst().orElseThrow();

            assertThat(tool.annotations().readOnlyHint()).isFalse();
            assertThat(tool.annotations().destructiveHint()).isFalse();
        }
    }

    @Test
    void aParentFromAnotherSpaceIsNotFoundAndNothingIsCreated() {
        try (McpSyncClient client = writerWithCredential(PAT)) {
            McpSchema.CallToolResult otherSpace = call(client, "create_page",
                    Map.of("space", "ENG", "parentId", "2000", "title", "Leak", "body", "x"));
            McpSchema.CallToolResult missing = call(client, "create_page",
                    Map.of("space", "ENG", "parentId", "1404", "title", "Leak", "body", "x"));
            McpSchema.CallToolResult forbidden = call(client, "create_page",
                    Map.of("space", "ENG", "parentId", "1403", "title", "Leak", "body", "x"));

            assertThat(text(otherSpace)).contains("\"error\":\"not_found_or_denied\"").doesNotContain("HR");
            assertThat(text(missing)).isEqualTo(text(otherSpace));
            assertThat(text(forbidden)).isEqualTo(text(otherSpace));
        }
        assertThat(BACKEND.requests()).noneMatch(request -> "POST".equals(request.method()));
    }

    @Test
    void confluenceRefusalsMapToC10WithoutConfluenceText() {
        try (McpSyncClient client = writerWithCredential(PAT)) {
            McpSchema.CallToolResult duplicate = call(client, "create_page",
                    Map.of("space", "ENG", "parentId", "1000", "title", "Existing", "body", "x"));
            assertThat(text(duplicate)).contains("\"error\":\"invalid_argument\"", "already has a page with this title")
                    .doesNotContain("A page already exists");
        }
    }

    @Test
    void withoutCredentialOrWithAnExpiredOneAsksToConnectTheAccount() {
        Map<String, Object> arguments = Map.of("space", "ENG", "parentId", "1000", "title", "T", "body", "x");
        try (McpSyncClient client = writerWithCredential(null)) {
            assertCredentialRequired(call(client, "create_page", arguments));
            assertThat(BACKEND.authorizations()).isEmpty();
        }
        try (McpSyncClient client = writerWithCredential(StubBackend.EXPIRED_CREDENTIAL)) {
            assertCredentialRequired(call(client, "create_page", arguments));
        }
        assertThat(BACKEND.requests()).noneMatch(request -> "POST".equals(request.method()));
    }

    @Test
    void invalidArgumentsAreRejectedBeforeConfluence() {
        try (McpSyncClient client = writerWithCredential(PAT)) {
            assertInvalid(client, Map.of("space", "~jkowalski", "parentId", "1000", "title", "T", "body", "x"));
            assertInvalid(client, Map.of("space", "ENG", "parentId", "1000/../2000", "title", "T", "body", "x"));
            assertInvalid(client, Map.of("space", "ENG", "parentId", "0", "title", "T", "body", "x"));
            assertInvalid(client, Map.of("space", "ENG", "parentId", "1000", "title", "two\nlines", "body", "x"));
            assertInvalid(client, Map.of("space", "ENG", "parentId", "1000", "title", "  ", "body", "x"));
            assertInvalid(client, Map.of("space", "ENG", "parentId", "1000", "title", "T".repeat(256), "body", "x"));
            assertInvalid(client, Map.of("space", "ENG", "parentId", "1000", "title", "T", "body", "x".repeat(20_001)));
            assertInvalid(client, Map.of("space", "ENG", "parentId", "1000", "title", "T", "body", "bell\u0007"));
            assertInvalid(client, Map.of("space", "ENG", "parentId", "1000", "title", "T", "body", ""));
            assertThat(BACKEND.authorizations()).isEmpty();
        }
    }

    private static void assertInvalid(McpSyncClient client, Map<String, Object> arguments) {
        assertRejectedArgument(call(client, "create_page", arguments));
    }
}
