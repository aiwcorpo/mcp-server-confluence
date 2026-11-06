package pl.aiwcorpo.mcp.confluence.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** update_page, add_comment, add_label and delete_page: scope of the grant, safe repeat, escaped text, no leaks. */
class WriteToolsTest extends ServerTestSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @BeforeEach
    void resetBackend() {
        BACKEND.reset();
    }

    private static StubBackend.Request only(String method) {
        return BACKEND.requests().stream().filter(r -> method.equals(r.method())).findFirst().orElseThrow();
    }

    private static boolean wrote() {
        return BACKEND.requests().stream().anyMatch(r -> !"GET".equals(r.method()));
    }

    @Test
    void appendKeepsTheExistingContentAndRaisesTheVersionByOne() {
        try (McpSyncClient client = writerWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "update_page", Map.of("pageId", "1000", "expectedVersion", 7,
                    "mode", "append", "body", "Addendum <b>1</b>"));

            assertThat(result.isError()).as(text(result)).isFalse();
            JsonNode written = JSON.readTree(text(result));
            assertThat(written.get("id").asString()).isEqualTo("1000");
            assertThat(written.get("version").asInt()).isEqualTo(8);
            assertThat(text(result)).doesNotContain("Welcome");
        }
        StubBackend.Request put = only("PUT");
        assertThat(put.path()).isEqualTo("/rest/api/content/1000");
        assertThat(put.authorization()).isEqualTo(PAT);
        JsonNode sent = JSON.readTree(put.body());
        assertThat(sent.path("version").path("number").asInt()).isEqualTo(8);
        assertThat(sent.path("title").asString()).isEqualTo("Engineering home");
        assertThat(sent.path("body").path("storage").path("value").asString())
                .isEqualTo("<p>Welcome to engineering.</p><p>Addendum &lt;b&gt;1&lt;/b&gt;</p>");
    }

    @Test
    void replaceWorksOnAPlainPageAndCanRenameIt() {
        try (McpSyncClient client = writerWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "update_page", Map.of("pageId", "1000", "expectedVersion", 7,
                    "mode", "replace", "body", "New text", "title", "Engineering start"));

            assertThat(result.isError()).as(text(result)).isFalse();
            assertThat(text(result)).contains("Engineering start");
        }
        JsonNode sent = JSON.readTree(only("PUT").body());
        assertThat(sent.path("body").path("storage").path("value").asString()).isEqualTo("<p>New text</p>");
        assertThat(sent.path("title").asString()).isEqualTo("Engineering start");
    }

    @Test
    void replaceIsRefusedForAPageWithMacrosAndNothingIsWritten() {
        try (McpSyncClient client = writerWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "update_page", Map.of("pageId", "1001", "expectedVersion", 3,
                    "mode", "replace", "body", "Overwrite"));

            assertThat(text(result)).contains("\"error\":\"invalid_argument\"", "mode append");
            assertThat(call(client, "update_page", Map.of("pageId", "1001", "expectedVersion", 3, "mode", "append",
                    "body", "Fine")).isError()).isFalse();
        }
        assertThat(BACKEND.requests().stream().filter(r -> "PUT".equals(r.method()))).hasSize(1);
        assertThat(JSON.readTree(only("PUT").body()).path("body").path("storage").path("value").asString())
                .contains("ac:structured-macro", "<p>Fine</p>");
    }

    @Test
    void aStaleVersionIsAConflictAndNothingIsWritten() {
        try (McpSyncClient client = writerWithCredential(PAT)) {
            McpSchema.CallToolResult stale = call(client, "update_page", Map.of("pageId", "1000", "expectedVersion", 6,
                    "mode", "append", "body", "x"));
            McpSchema.CallToolResult raced = call(client, "update_page", Map.of("pageId", "1700", "expectedVersion", 5,
                    "mode", "append", "body", "x"));

            assertThat(text(stale)).contains("\"error\":\"invalid_argument\"", "not at the version you read");
            assertThat(text(raced)).as("409 from Confluence between the read and the write").isEqualTo(text(stale));
        }
        assertThat(BACKEND.requests().stream().filter(r -> "PUT".equals(r.method())))
                .as("only the raced one reached Confluence").hasSize(1);
    }

    @Test
    void aDuplicateTitleIsInvalidArgumentWithoutConfluenceText() {
        try (McpSyncClient client = writerWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "update_page", Map.of("pageId", "1000", "expectedVersion", 7,
                    "mode", "append", "body", "x", "title", "Existing"));

            assertThat(text(result)).contains("\"error\":\"invalid_argument\"").doesNotContain("A page already exists");
        }
    }

    @Test
    void pagesOutsideTheWriteGrantAreNotFoundOrDeniedAndNothingIsWritten() {
        try (McpSyncClient client = writerWithCredential(PAT)) {
            McpSchema.CallToolResult otherSpace = call(client, "update_page", Map.of("pageId", "4001", "expectedVersion", 4,
                    "mode", "append", "body", "x"));
            McpSchema.CallToolResult restricted = call(client, "update_page", Map.of("pageId", "2000", "expectedVersion", 2,
                    "mode", "append", "body", "x"));
            McpSchema.CallToolResult missing = call(client, "update_page", Map.of("pageId", "1404", "expectedVersion", 2,
                    "mode", "append", "body", "x"));

            assertNotFoundOrDenied(otherSpace);
            assertThat(text(restricted)).isEqualTo(text(otherSpace));
            assertThat(text(missing)).isEqualTo(text(otherSpace));
        }
        assertThat(wrote()).isFalse();
    }

    @Test
    void aReaderCannotCallAWriteTool() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            for (Map.Entry<String, Map<String, Object>> call : Map.of(
                    "update_page", Map.<String, Object>of("pageId", "1000", "expectedVersion", 7, "mode", "append", "body", "x"),
                    "add_comment", Map.<String, Object>of("pageId", "1000", "body", "x"),
                    "add_label", Map.<String, Object>of("pageId", "1000", "label", "x")).entrySet()) {
                assertThat(text(call(client, call.getKey(), call.getValue()))).as(call.getKey())
                        .contains("\"error\":\"access_denied\"");
            }
        }
        assertThat(BACKEND.requests()).isEmpty();
    }

    @Test
    void invalidArgumentsOfUpdateAreRejectedBeforeConfluence() {
        try (McpSyncClient client = writerWithCredential(PAT)) {
            Map<String, Object> base = Map.of("pageId", "1000", "expectedVersion", 7, "mode", "append", "body", "x");
            for (Map<String, Object> bad : java.util.List.of(
                    with(base, "mode", "merge"), with(base, "mode", "replace now"), with(base, "expectedVersion", 0),
                    with(base, "expectedVersion", 1_000_001), with(base, "body", ""), with(base, "body", "x".repeat(20_001)),
                    with(base, "body", "bell\u0007"), with(base, "title", "two\nlines"), with(base, "pageId", "10/../20"),
                    with(base, "extra", "field"))) {
                assertRejectedArgument(call(client, "update_page", bad));
            }
        }
        assertThat(BACKEND.requests()).isEmpty();
    }

    private static Map<String, Object> with(Map<String, Object> base, String key, Object value) {
        Map<String, Object> copy = new java.util.HashMap<>(base);
        copy.put(key, value);
        return copy;
    }

    @Test
    void aCommentIsAddedWithEscapedTextAndARepliesStaysOnItsPage() {
        try (McpSyncClient client = writerWithCredential(PAT)) {
            McpSchema.CallToolResult added = call(client, "add_comment", Map.of("pageId", "1000", "body", "Done <i>now</i>"));
            assertThat(added.isError()).as(text(added)).isFalse();
            assertThat(JSON.readTree(text(added)).get("pageId").asString()).isEqualTo("1000");

            assertThat(call(client, "add_comment", Map.of("pageId", "1000", "body", "Reply", "parentCommentId", "7001"))
                    .isError()).isFalse();
        }
        JsonNode first = JSON.readTree(BACKEND.requests().stream().filter(r -> "POST".equals(r.method())).findFirst().orElseThrow().body());
        assertThat(first.path("type").asString()).isEqualTo("comment");
        assertThat(first.path("container").path("id").asString()).isEqualTo("1000");
        assertThat(first.path("body").path("storage").path("value").asString()).isEqualTo("<p>Done &lt;i&gt;now&lt;/i&gt;</p>");
        JsonNode reply = JSON.readTree(BACKEND.requests().stream().filter(r -> "POST".equals(r.method())).skip(1).findFirst().orElseThrow().body());
        assertThat(reply.path("ancestors").path(0).path("id").asString()).isEqualTo("7001");
    }

    @Test
    void aReplyToACommentOfAnotherPageOrAMissingOneIsNotFoundAndNothingIsPosted() {
        try (McpSyncClient client = writerWithCredential(PAT)) {
            McpSchema.CallToolResult otherPage = call(client, "add_comment",
                    Map.of("pageId", "1000", "body", "x", "parentCommentId", "7100"));
            McpSchema.CallToolResult missing = call(client, "add_comment",
                    Map.of("pageId", "1000", "body", "x", "parentCommentId", "7555"));

            assertNotFoundOrDenied(otherPage);
            assertThat(text(missing)).isEqualTo(text(otherPage));
            assertRejectedArgument(call(client, "add_comment", Map.of("pageId", "1000", "body", "x", "parentCommentId", "7001/../1")));
        }
        assertThat(wrote()).isFalse();
    }

    @Test
    void aLabelIsAddedAsGlobalAndTheCallCanBeRepeated() {
        try (McpSyncClient client = writerWithCredential(PAT)) {
            for (int i = 0; i < 2; i++) {
                McpSchema.CallToolResult added = call(client, "add_label", Map.of("pageId", "1000", "label", "reviewed"));
                assertThat(added.isError()).as(text(added)).isFalse();
                assertThat(text(added)).contains("\"label\":\"reviewed\"");
            }
            for (String bad : new String[] {"Has Space", "UPPER", "a,b", "", "x".repeat(101), "-lead", "a\" OR 1"}) {
                assertRejectedArgument(call(client, "add_label", Map.of("pageId", "1000", "label", bad)));
            }
            assertNotFoundOrDenied(call(client, "add_label", Map.of("pageId", "2000", "label", "x")));
        }
        JsonNode sent = JSON.readTree(only("POST").body());
        assertThat(sent.path(0).path("prefix").asString()).isEqualTo("global");
        assertThat(sent.path(0).path("name").asString()).isEqualTo("reviewed");
        assertThat(BACKEND.requests().stream().filter(r -> "POST".equals(r.method()))).hasSize(2);
    }

    @Test
    void deletingIsDestructiveAndAWriterWithoutTheDestructiveGroupIsRefused() {
        try (McpSyncClient client = writerWithCredential(PAT)) {
            McpSchema.Tool tool = client.listTools().tools().stream()
                    .filter(t -> t.name().equals("delete_page")).findFirst().orElseThrow();
            assertThat(tool.annotations().destructiveHint()).isTrue();

            assertThat(text(call(client, "delete_page", Map.of("pageId", "1000")))).contains("\"error\":\"access_denied\"");
        }
        assertThat(wrote()).isFalse();
    }

    @Test
    void withoutCredentialOrWithAnExpiredOneWritesAskToConnectTheAccount() {
        Map<String, Object> update = Map.of("pageId", "1000", "expectedVersion", 7, "mode", "append", "body", "x");
        try (McpSyncClient client = writerWithCredential(null)) {
            assertCredentialRequired(call(client, "update_page", update));
            assertCredentialRequired(call(client, "add_label", Map.of("pageId", "1000", "label", "x")));
        }
        try (McpSyncClient client = writerWithCredential(StubBackend.EXPIRED_CREDENTIAL)) {
            assertCredentialRequired(call(client, "update_page", update));
        }
        assertThat(wrote()).isFalse();
    }
}
