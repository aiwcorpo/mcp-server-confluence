package pl.aiwcorpo.mcp.confluence.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code search_pages}: the server builds the CQL for one space; the model never writes CQL. */
class SearchPagesToolTest extends ServerTestSupport {

    @BeforeEach
    void resetBackend() {
        BACKEND.reset();
    }

    @Test
    void searchesOneSpaceAndReturnsOnlyItsPages() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "search_pages",
                    Map.of("space", "ENG", "text", "release notes"));

            assertThat(result.isError()).isFalse();
            assertThat(text(result)).contains("\"spaceKey\":\"ENG\"", "\"id\":\"1000\"", "\"id\":\"1001\"",
                    "\"title\":{\"kind\":\"untrusted-data\",\"format\":\"text/plain\",\"text\":\"Release notes\",\"truncated\":false}", "\"lastUpdated\":\"2026-10-20T10:15:30.000+02:00\"")
                    .as("blog posts and hits from other spaces are dropped").doesNotContain("1002", "2000", "Salaries")
                    .doesNotContain("_links", "webui");
        }
        assertThat(BACKEND.requests()).allSatisfy(request -> assertThat(request.authorization()).isEqualTo(PAT));
        assertThat(BACKEND.requests().stream().filter(r -> r.path().equals("/rest/api/content/search")).findFirst().orElseThrow())
                .satisfies(request -> {
            assertThat(request.path()).isEqualTo("/rest/api/content/search");
            assertThat(request.query()).containsEntry("cql", "(text ~ \"release notes\") AND space in (\"ENG\")")
                    .containsEntry("limit", "25").containsEntry("expand", "space,version");
        });
    }

    @Test
    void cqlInTheTextCannotWidenTheSearch() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "search_pages",
                    Map.of("space", "ENG", "text", "x\" OR space = \"HR\" OR text ~ \"*"));

            assertThat(result.isError()).isFalse();
        }
        assertThat(BACKEND.requests().stream().filter(r -> r.path().equals("/rest/api/content/search")).findFirst().orElseThrow().query().get("cql"))
                .isEqualTo("(text ~ \"x OR space HR OR text\") AND space in (\"ENG\")");
    }

    @Test
    void aSpaceWithoutVisiblePagesGivesAnEmptyList() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "search_pages", Map.of("space", "SECRET", "text", "pay"));

            assertThat(result.isError()).isFalse();
            assertThat(text(result)).contains("\"spaceKey\":\"SECRET\"", "\"pages\":[]");
        }
    }

    @Test
    void withoutCredentialOrWithAnExpiredOneAsksToConnectTheAccount() {
        try (McpSyncClient client = clientWithCredential(null)) {
            assertCredentialRequired(call(client, "search_pages", Map.of("space", "ENG", "text", "release")));
            assertThat(BACKEND.authorizations()).isEmpty();
        }
        try (McpSyncClient client = clientWithCredential(StubBackend.EXPIRED_CREDENTIAL)) {
            assertCredentialRequired(call(client, "search_pages", Map.of("space", "ENG", "text", "release")));
        }
    }

    @Test
    void invalidSpaceOrTextIsRejectedBeforeConfluence() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            for (String space : new String[] {"~jkowalski", "ENG\" OR space = \"HR", "ENG,HR", "EN G", "",
                    "A".repeat(256)}) {
                assertRejectedArgument(call(client, "search_pages", Map.of("space", space, "text", "x")));
            }
            for (String text : new String[] {"", "   ", "a\nb", "a".repeat(201)}) {
                assertRejectedArgument(call(client, "search_pages", Map.of("space", "ENG", "text", text)));
            }
            McpSchema.CallToolResult cql = call(client, "search_pages",
                    Map.of("space", "ENG", "text", "x", "cql", "type = page"));
            assertThat(cql.isError()).isTrue();
            assertThat(BACKEND.authorizations()).isEmpty();
            assertThat(text(call(client, "search_pages", Map.of("space", "ENG", "text", "*?~"))))
                    .contains("\"error\":\"invalid_argument\"", "text must contain at least one letter or digit");
        }
    }

    @Test
    void aRedirectIsAnErrorNotAnEmptyResult() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "search_pages", Map.of("space", "REDIR", "text", "REDIR"));

            assertThat(text(result)).contains("\"error\":\"internal_error\"").doesNotContain(StubBackend.BODY_MARKER);
            assertThat(BACKEND.elsewhereHits()).isEmpty();
        }
    }

    @Test
    void wordsThatConfluenceCannotParseAreInvalidArgumentWithoutItsMessage() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "search_pages", Map.of("space", "BAD", "text", "BAD"));

            assertThat(text(result)).contains("\"error\":\"invalid_argument\"").doesNotContain(StubBackend.BODY_MARKER);
        }
    }

    @Test
    void titlesComeBackAsUntrustedData() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "search_pages", Map.of("space", "ENG", "text", "release"));

            assertThat(text(result)).doesNotContain("\"title\":\"");
        }
    }
}
