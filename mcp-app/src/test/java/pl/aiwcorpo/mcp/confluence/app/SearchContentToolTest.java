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

/** search_content: the caller's CQL is confined to one readable space and to pages; hits are judged by where they are. */
class SearchContentToolTest extends ServerTestSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @BeforeEach
    void resetBackend() {
        BACKEND.reset();
    }

    private static JsonNode json(McpSchema.CallToolResult result) {
        assertThat(result.isError()).as(text(result)).isFalse();
        return JSON.readTree(text(result));
    }

    private static List<StubBackend.Request> searches() {
        return BACKEND.requests().stream().filter(r -> r.path().equals("/rest/api/search")).toList();
    }

    @Test
    void theQueryIsConfinedAndOnlyPagesOfTheSpaceComeBackWithTheirExcerpt() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "search_content",
                    Map.of("space", "ENG", "cql", "text ~ \"release\" order by lastmodified desc"));
            JsonNode found = json(result);

            assertThat(found.get("items")).extracting(item -> item.get("id").asString()).containsExactly("1000", "1001");
            JsonNode first = found.get("items").get(0);
            assertThat(first.get("title").get("text").asString()).isEqualTo("Engineering home");
            assertThat(first.get("version").asInt()).isEqualTo(7);
            assertThat(first.get("excerpt").get("kind").asString()).isEqualTo("untrusted-data");
            assertThat(first.get("excerpt").get("text").asString()).isEqualTo("Welcome to engineering. Second line");
            assertThat(text(result)).as("blog post, comment, pages that are in HR or OPS now, unknown page")
                    .doesNotContain("1002", "2000", "4002", "9999", "Salaries", StubBackend.HR_SECRET, "@@@");
        }
        assertThat(BACKEND.requests()).allSatisfy(request -> assertThat(request.authorization()).isEqualTo(PAT));
        assertThat(searches()).singleElement().satisfies(request -> {
            assertThat(request.query()).containsEntry("cql",
                    "(text ~ \"release\") AND space in (\"ENG\") AND type = page ORDER BY lastmodified DESC")
                    .containsEntry("limit", "25").containsEntry("excerpt", "indexed");
        });
    }

    @Test
    void aConditionThatTriesToLeaveTheSpaceIsRefusedBeforeTheSearch() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            for (String cql : new String[] {"text ~ \"a\") OR space = \"HR\" OR (text ~ \"b\"", "space = HR OR (",
                    "text ~ \"a\" OR space = HR; x", "type = comment order by space", "text ~ \"a"}) {
                McpSchema.CallToolResult result = call(client, "search_content", Map.of("space", "ENG", "cql", cql));

                assertThat(text(result)).as(cql).contains("\"error\":\"invalid_argument\"");
            }
            assertRejectedArgument(call(client, "search_content", Map.of("space", "ENG", "cql", "x".repeat(501))));
            assertRejectedArgument(call(client, "search_content", Map.of("space", "ENG", "cql", "a\nb")));
            assertRejectedArgument(call(client, "search_content", Map.of("space", "ENG\" OR space = \"HR", "cql", "text ~ a")));
            assertRejectedArgument(call(client, "search_content", Map.of("space", "ENG", "cql", "text ~ a", "extra", "x")));
        }
        assertThat(searches()).isEmpty();
    }

    @Test
    void aTextThatLooksLikeAnotherSpaceStaysInsideTheScope() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode found = json(call(client, "search_content",
                    Map.of("space", "ENG", "cql", "text ~ \"x\\\" OR space = \\\"HR\"")));

            assertThat(found.get("items")).extracting(item -> item.get("id").asString()).doesNotContain("2000");
        }
        assertThat(searches().getFirst().query().get("cql")).isEqualTo(
                "(text ~ \"x\\\" OR space = \\\"HR\") AND space in (\"ENG\") AND type = page");
    }

    @Test
    void aSpaceTheCallerMayNotReadIsNotFoundOrDeniedAndNothingIsSearched() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult missing = call(client, "search_content", Map.of("space", "NOSUCH", "cql", "text ~ \"a\""));
            McpSchema.CallToolResult restricted = call(client, "search_content", Map.of("space", "HR", "cql", "text ~ \"a\""));
            McpSchema.CallToolResult personal = call(client, "search_content", Map.of("space", "~jkowalski", "cql", "text ~ \"a\""));

            assertNotFoundOrDenied(restricted);
            assertThat(text(restricted)).isEqualTo(text(missing));
            assertThat(personal.isError()).isTrue();
        }
        assertThat(searches()).isEmpty();
    }

    @Test
    void aCallerWithoutTheReadGroupGetsAccessDeniedAndNothingReachesConfluence() {
        try (McpSyncClient client = clientWithToken(user -> user.username("jkowalski").groups(List.of("mcp-nobody")), PAT)) {
            McpSchema.CallToolResult result = call(client, "search_content", Map.of("space", "ENG", "cql", "text ~ \"a\""));

            assertThat(text(result)).contains("\"error\":\"access_denied\"");
            assertThat(BACKEND.authorizations()).isEmpty();
        }
    }

    @Test
    void resultsPageWithTheSignedCursorAndTheCursorBelongsToItsQuery() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode first = json(call(client, "search_content", Map.of("space", "BIG", "cql", "title ~ \"bulk\"")));
            assertThat(first.get("items")).hasSize(25);
            String cursor = first.get("nextCursor").asString();

            JsonNode second = json(call(client, "search_content",
                    Map.of("space", "BIG", "cql", "title ~ \"bulk\"", "cursor", cursor)));
            assertThat(second.get("items")).hasSize(5);
            assertThat(second.get("nextCursor").isNull()).isTrue();

            assertRejectedArgument(call(client, "search_content",
                    Map.of("space", "BIG", "cql", "title ~ \"other\"", "cursor", cursor)));
            assertRejectedArgument(call(client, "search_pages", Map.of("space", "BIG", "text", "bulk", "cursor", cursor)));
        }
        assertThat(searches().stream().map(r -> r.query().get("start"))).containsExactly("0", "25");
    }

    @Test
    void aWindowThinnedOutByTheSpaceCheckIsFilledFromTheNextOne() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode found = json(call(client, "search_content", Map.of("space", "IDX", "cql", "text ~ \"moved\"")));

            assertThat(found.get("items")).hasSize(10).allSatisfy(item -> assertThat(item.get("id").asString()).startsWith("803"));
            assertThat(found.get("nextCursor").isNull()).isTrue();
        }
        assertThat(searches().stream().map(r -> r.query().get("start"))).containsExactly("0", "25");
    }

    @Test
    void confluenceRejectingTheQueryIsInvalidArgumentWithoutItsMessage() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "search_content", Map.of("space", "BAD", "cql", "text ~ \"BAD\""));

            assertThat(text(result)).contains("\"error\":\"invalid_argument\"").doesNotContain(StubBackend.BODY_MARKER);
        }
    }

    @Test
    void aRedirectIsAnErrorNotAnEmptyResult() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "search_content", Map.of("space", "REDIR", "cql", "text ~ \"REDIR\""));

            assertThat(text(result)).contains("\"error\":\"internal_error\"").doesNotContain(StubBackend.BODY_MARKER);
            assertThat(BACKEND.elsewhereHits()).isEmpty();
        }
    }

    @Test
    void withoutCredentialOrWithAnExpiredOneAsksToConnectTheAccount() {
        Map<String, Object> arguments = Map.of("space", "ENG", "cql", "text ~ \"a\"");
        try (McpSyncClient client = clientWithCredential(null)) {
            assertCredentialRequired(call(client, "search_content", arguments));
            assertThat(BACKEND.authorizations()).isEmpty();
        }
        try (McpSyncClient client = clientWithCredential(StubBackend.EXPIRED_CREDENTIAL)) {
            assertCredentialRequired(call(client, "search_content", arguments));
        }
    }
}
