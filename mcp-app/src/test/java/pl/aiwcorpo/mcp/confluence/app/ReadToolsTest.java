package pl.aiwcorpo.mcp.confluence.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The read tools added beyond search, get_page and get_space: lists, title lookup, versions, comments, labels, attachments. */
class ReadToolsTest extends ServerTestSupport {

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
    void listPagesPagesWithASignedCursorAndNoTotal() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode first = json(call(client, "list_pages", Map.of("space", "BIG")));
            assertThat(first.get("items")).hasSize(25);
            assertThat(first.has("total")).isFalse();
            String cursor = first.get("nextCursor").asString();

            JsonNode second = json(call(client, "list_pages", Map.of("space", "BIG", "cursor", cursor)));
            assertThat(second.get("items")).hasSize(5);
            assertThat(second.get("nextCursor").isNull()).isTrue();
            assertThat(second.get("items").get(0).get("id").asString()).isEqualTo("9025");
        }
        assertThat(BACKEND.requests().stream().filter(r -> r.path().equals("/rest/api/content")).map(r -> r.query().get("start")))
                .containsExactly("0", "25");
    }

    @Test
    void aCursorBelongsToItsCallToolAndCaller() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            String cursor = json(call(client, "list_pages", Map.of("space", "BIG"))).get("nextCursor").asString();

            assertRejectedArgument(call(client, "list_pages", Map.of("space", "ENG", "cursor", cursor)));
            assertRejectedArgument(call(client, "list_pages", Map.of("space", "BIG", "cursor", cursor + "x")));
            assertRejectedArgument(call(client, "list_pages", Map.of("space", "BIG", "cursor", "not.acursor")));
            assertRejectedArgument(call(client, "search_pages", Map.of("space", "BIG", "text", "bulk", "cursor", cursor)));
        }
        try (McpSyncClient other = clientWithToken(user -> user.subject("user-anowak").username("anowak").groups(java.util.List.of(READ_GROUP)), PAT);
             McpSyncClient owner = clientWithCredential(PAT)) {
            String cursor = json(call(owner, "list_pages", Map.of("space", "BIG"))).get("nextCursor").asString();
            assertRejectedArgument(call(other, "list_pages", Map.of("space", "BIG", "cursor", cursor)));
        }
    }

    @Test
    void searchPagesAlsoPagesAndTakesStructuredFilters() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode first = json(call(client, "search_pages",
                    Map.of("space", "BIG", "title", "bulk", "label", "handbook", "orderBy", "title")));
            assertThat(first.get("items")).hasSize(25);
            assertThat(first.get("nextCursor").asString()).isNotBlank();
        }
        assertThat(BACKEND.requests().stream().filter(r -> r.path().endsWith("/search")).findFirst().orElseThrow().query())
                .containsEntry("cql", "(title ~ \"bulk\" AND label = \"handbook\") AND space in (\"BIG\") ORDER BY title ASC");
    }

    @Test
    void searchNeedsAFilterAndRefusesAnUnknownOrder() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertThat(text(call(client, "search_pages", Map.of("space", "ENG")))).contains("\"error\":\"invalid_argument\"");
            assertRejectedArgument(call(client, "search_pages", Map.of("space", "ENG", "text", "x", "orderBy", "relevance DESC")));
            assertRejectedArgument(call(client, "search_pages", Map.of("space", "ENG", "label", "Has Space")));
            assertRejectedArgument(call(client, "search_pages", Map.of("space", "ENG", "label", "a\" OR space = \"HR")));
        }
    }

    @Test
    void childPagesAreListedAndJudgedByTheirNewPlace() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode children = json(call(client, "list_child_pages", Map.of("pageId", "1000")));
            assertThat(children.get("items")).extracting(item -> item.get("id").asString()).contains("1001", "1700");
            assertNotFoundOrDenied(call(client, "list_child_pages", Map.of("pageId", "2000")));
        }
    }

    @Test
    void contentThatIsNotBelowTheJudgedObjectIsNeverHandedOut() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertThat(json(call(client, "list_child_pages", Map.of("pageId", "1700"))).get("items"))
                    .as("page 1000 is not a child of 1700").isEmpty();
            assertNotFoundOrDenied(call(client, "get_page_version", Map.of("pageId", "1700", "version", 1)));
        }
    }

    @Test
    void listSpacesDropsSpacesThePolicyRefusesOrCannotClassify() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            String listing = text(call(client, "list_spaces", Map.of()));

            assertThat(listing).contains("\"key\":\"ENG\"", "\"key\":\"OPS\"")
                    .as("labelled restricted").doesNotContain("\"key\":\"HR\"")
                    .as("labels not reported").doesNotContain("\"key\":\"UNCL\"")
                    .as("label list longer than the answer").doesNotContain("\"key\":\"MANY\"");
        }
        assertThat(BACKEND.requests().stream().filter(r -> r.path().equals("/rest/api/space")).count())
                .as("the list, then one bulk lookup for the policy, not one call per space").isLessThanOrEqualTo(2);
    }

    @Test
    void aPageIsFoundByTitleAndJudgedLikeAnyOther() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode page = json(call(client, "get_page_by_title", Map.of("space", "ENG", "title", "Engineering home")));
            assertThat(page.get("id").asString()).isEqualTo("1000");
            assertThat(page.get("body").get("text").asString()).contains("Welcome to engineering.");

            McpSchema.CallToolResult missing = call(client, "get_page_by_title", Map.of("space", "ENG", "title", "No such page"));
            McpSchema.CallToolResult restrictedSpace = call(client, "get_page_by_title", Map.of("space", "HR", "title", "Salaries"));
            assertNotFoundOrDenied(missing);
            assertThat(text(restrictedSpace)).isEqualTo(text(missing));
        }
    }

    @Test
    void anEarlierVersionIsReadFromTheStoredVersion() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode old = json(call(client, "get_page_version", Map.of("pageId", "1001", "version", 2)));
            assertThat(old.get("version").asInt()).isEqualTo(2);
            assertThat(old.get("body").get("text").asString()).contains("Release 2.0 ships on Monday.");

            assertNotFoundOrDenied(call(client, "get_page_version", Map.of("pageId", "1001", "version", 9)));
            assertRejectedArgument(call(client, "get_page_version", Map.of("pageId", "1001", "version", 0)));
        }
        assertThat(BACKEND.requests()).anyMatch(r -> "historical".equals(r.query().get("status"))
                && "2".equals(r.query().get("version")));
    }

    @Test
    void commentsComeBackAsUntrustedTextWithTheirParentsAndWithoutExpandedMacros() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "list_comments", Map.of("pageId", "1000"));
            JsonNode comments = json(result);

            assertThat(comments.get("items")).hasSize(3);
            assertThat(comments.get("items").get(1).get("parentId").asString()).isEqualTo("7001");
            assertThat(comments.get("items").get(2).get("location").asString()).isEqualTo("inline");
            assertThat(comments.get("items").get(0).get("body").get("kind").asString()).isEqualTo("untrusted-data");
            assertThat(comments.get("omitted")).extracting(JsonNode::asString).containsExactly("unsupported_macro");
            assertThat(text(result)).doesNotContain(StubBackend.HR_SECRET, "Salaries");

            assertNotFoundOrDenied(call(client, "list_comments", Map.of("pageId", "2000")));
        }
    }

    @Test
    void labelsOfThePageListOnlyGlobalOnes() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            String labels = text(call(client, "list_labels", Map.of("pageId", "1000")));

            assertThat(labels).contains("handbook", "release").doesNotContain("private-note");
            assertNotFoundOrDenied(call(client, "list_labels", Map.of("pageId", "2000")));
        }
    }

    @Test
    void attachmentsAreListedAndOnlyTextOnesAreRead() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode list = json(call(client, "list_attachments", Map.of("pageId", "1001")));
            assertThat(list.get("items")).extracting(item -> item.get("id").asString())
                    .containsExactly("att5001", "att5002", "att5003", "att5004");

            JsonNode text = json(call(client, "read_attachment", Map.of("pageId", "1001", "attachmentId", "att5001")));
            assertThat(text.get("content").get("text").asString()).isEqualTo("Deploy at 10:00");
            assertThat(text.get("omitted")).isEmpty();

            JsonNode image = json(call(client, "read_attachment", Map.of("pageId", "1001", "attachmentId", "att5002")));
            assertThat(image.get("omitted").get(0).asString()).isEqualTo("binary_attachment");
            assertThat(image.get("content").get("text").asString()).isEmpty();

            JsonNode big = json(call(client, "read_attachment", Map.of("pageId", "1001", "attachmentId", "att5003")));
            assertThat(big.get("omitted").get(0).asString()).isEqualTo("attachment_too_large");
        }
        assertThat(BACKEND.requests().stream().filter(r -> r.path().startsWith("/download/")))
                .singleElement().satisfies(request -> {
                    assertThat(request.path()).isEqualTo("/download/attachments/1001/notes.txt");
                    assertThat(request.authorization()).isEqualTo(PAT);
                });
    }

    @Test
    void anAttachmentOfAnotherPageIsNotFoundAndHiddenPagesStayHidden() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult otherPage = call(client, "read_attachment",
                    Map.of("pageId", "1001", "attachmentId", "att5100"));
            McpSchema.CallToolResult missing = call(client, "read_attachment",
                    Map.of("pageId", "1001", "attachmentId", "att9999"));
            McpSchema.CallToolResult restricted = call(client, "read_attachment",
                    Map.of("pageId", "2000", "attachmentId", "att5100"));

            assertNotFoundOrDenied(otherPage);
            assertThat(text(missing)).isEqualTo(text(otherPage));
            assertThat(text(restricted)).isEqualTo(text(otherPage));
            assertNotFoundOrDenied(call(client, "list_attachments", Map.of("pageId", "2000")));
            assertRejectedArgument(call(client, "read_attachment", Map.of("pageId", "1001", "attachmentId", "../att5100")));
        }
        assertThat(BACKEND.requests()).noneMatch(r -> r.path().startsWith("/download/"));
    }

    @Test
    void withoutCredentialEveryReadAsksToConnectTheAccount() {
        try (McpSyncClient client = clientWithCredential(null)) {
            assertCredentialRequired(call(client, "list_spaces", Map.of()));
            assertCredentialRequired(call(client, "list_pages", Map.of("space", "ENG")));
            assertCredentialRequired(call(client, "list_comments", Map.of("pageId", "1000")));
            assertCredentialRequired(call(client, "get_page_by_title", Map.of("space", "ENG", "title", "x")));
        }
        assertThat(BACKEND.authorizations()).isEmpty();
    }

    @Test
    void aPageThinnedOutByThePolicyIsFilledFromTheNextWindowsNotReturnedShort() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode result = json(call(client, "search_pages", Map.of("space", "IDX", "text", "moved")));

            assertThat(result.get("items")).as("the first window is all moved to HR; the next one holds the rest")
                    .hasSize(10).allSatisfy(item -> assertThat(item.get("id").asString()).startsWith("803"));
            assertThat(result.get("nextCursor").isNull()).isTrue();
        }
        assertThat(BACKEND.requests().stream().filter(r -> r.path().endsWith("/search") && r.query().get("cql").startsWith("(text"))
                .map(r -> r.query().get("start"))).containsExactly("0", "25");
    }

    @Test
    void labelsReportWhenTheListWasCut() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode many = json(call(client, "list_labels", Map.of("pageId", "1700")));
            assertThat(many.get("labels")).hasSize(200);
            assertThat(many.get("truncated").asBoolean()).isTrue();

            assertThat(json(call(client, "list_labels", Map.of("pageId", "1000"))).get("truncated").asBoolean()).isFalse();
        }
    }

    @Test
    void anAttachmentWhoseDownloadPathHasADotSegmentIsNotFollowed() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "read_attachment", Map.of("pageId", "1001", "attachmentId", "att5004"));

            assertThat(text(result)).contains("\"error\":\"internal_error\"");
        }
        assertThat(BACKEND.requests()).noneMatch(r -> r.path().startsWith("/download/"));
    }
}
