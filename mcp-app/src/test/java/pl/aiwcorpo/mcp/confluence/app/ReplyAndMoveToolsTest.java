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

/** reply_to_comment and move_page: the same grant rules as the other writes, on every place a move touches. */
class ReplyAndMoveToolsTest extends ServerTestSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @BeforeEach
    void resetBackend() {
        BACKEND.reset();
    }

    private static List<StubBackend.Request> writes() {
        return BACKEND.requests().stream().filter(r -> !"GET".equals(r.method())).toList();
    }

    private McpSyncClient editor(String... groups) {
        return clientWithToken(user -> user.username("jkowalski").groups(List.of(groups)), PAT);
    }

    @Test
    void aReplyIsPostedUnderTheCommentWithEscapedText() {
        try (McpSyncClient client = writerWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "reply_to_comment",
                    Map.of("pageId", "1000", "commentId", "7001", "body", "Thanks <b>all</b>"));

            assertThat(result.isError()).as(text(result)).isFalse();
            assertThat(JSON.readTree(text(result)).get("pageId").asString()).isEqualTo("1000");
        }
        StubBackend.Request post = writes().getFirst();
        assertThat(post.method()).isEqualTo("POST");
        assertThat(post.authorization()).isEqualTo(PAT);
        JsonNode sent = JSON.readTree(post.body());
        assertThat(sent.path("container").path("id").asString()).isEqualTo("1000");
        assertThat(sent.path("ancestors").path(0).path("id").asString()).isEqualTo("7001");
        assertThat(sent.path("body").path("storage").path("value").asString()).isEqualTo("<p>Thanks &lt;b&gt;all&lt;/b&gt;</p>");
    }

    @Test
    void aCommentOfAnotherPageOrAMissingOneIsNotFoundAndNothingIsPosted() {
        try (McpSyncClient client = writerWithCredential(PAT)) {
            McpSchema.CallToolResult otherPage = call(client, "reply_to_comment",
                    Map.of("pageId", "1000", "commentId", "7100", "body", "x"));
            McpSchema.CallToolResult missing = call(client, "reply_to_comment",
                    Map.of("pageId", "1000", "commentId", "7555", "body", "x"));

            assertNotFoundOrDenied(otherPage);
            assertThat(text(missing)).isEqualTo(text(otherPage));
            assertRejectedArgument(call(client, "reply_to_comment", Map.of("pageId", "1000", "commentId", "7001/../1", "body", "x")));
            assertRejectedArgument(call(client, "reply_to_comment", Map.of("pageId", "1000", "body", "x")));
            assertRejectedArgument(call(client, "reply_to_comment", Map.of("pageId", "1000", "commentId", "7001", "body", "")));
        }
        assertThat(writes()).isEmpty();
    }

    @Test
    void aReplyNeedsTheWriteGroupAndAGrantOnThePage() {
        try (McpSyncClient reader = clientWithCredential(PAT)) {
            assertThat(text(call(reader, "reply_to_comment", Map.of("pageId", "1000", "commentId", "7001", "body", "x"))))
                    .contains("\"error\":\"access_denied\"");
        }
        try (McpSyncClient client = writerWithCredential(PAT)) {
            assertNotFoundOrDenied(call(client, "reply_to_comment", Map.of("pageId", "4001", "commentId", "7001", "body", "x")));
        }
        assertThat(writes()).isEmpty();
    }

    @Test
    void aPageIsMovedUnderANewParentWithItsContentUnchanged() {
        try (McpSyncClient client = writerWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "move_page", Map.of("pageId", "1710", "targetParentId", "1001"));

            assertThat(result.isError()).as(text(result)).isFalse();
            JsonNode moved = JSON.readTree(text(result));
            assertThat(moved.get("id").asString()).isEqualTo("1710");
            assertThat(moved.get("parentId").asString()).isEqualTo("1001");
            assertThat(moved.get("spaceKey").asString()).isEqualTo("ENG");
            assertThat(moved.get("version").asInt()).isEqualTo(2);
        }
        StubBackend.Request put = writes().getFirst();
        assertThat(put.method()).isEqualTo("PUT");
        assertThat(put.path()).isEqualTo("/rest/api/content/1710");
        assertThat(put.authorization()).isEqualTo(PAT);
        JsonNode sent = JSON.readTree(put.body());
        assertThat(sent.path("ancestors")).hasSize(1);
        assertThat(sent.path("ancestors").path(0).path("id").asString()).isEqualTo("1001");
        assertThat(sent.path("version").path("number").asInt()).isEqualTo(2);
        assertThat(sent.path("title").asString()).isEqualTo("Draft");
        assertThat(sent.path("body").path("storage").path("value").asString()).isEqualTo("<p>Draft text.</p>");
        assertThat(writes()).hasSize(1);
    }

    @Test
    void aTopLevelPageMovesWhenTheCallerMayUpdateTheSpace() {
        try (McpSyncClient client = writerWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "move_page", Map.of("pageId", "1730", "targetParentId", "1001"));

            assertThat(result.isError()).as(text(result)).isFalse();
        }
        assertThat(writes()).hasSize(1);
    }

    @Test
    void aPageAlreadyUnderThatParentIsNotWrittenAgain() {
        try (McpSyncClient client = writerWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "move_page", Map.of("pageId", "1700", "targetParentId", "1000"));

            assertThat(result.isError()).as(text(result)).isFalse();
            assertThat(JSON.readTree(text(result)).get("parentId").asString()).isEqualTo("1000");
            assertThat(JSON.readTree(text(result)).get("version").asInt()).isEqualTo(5);
        }
        assertThat(writes()).isEmpty();
    }

    @Test
    void aPageCannotGoUnderItselfOrItsDescendantsOrIntoAnotherSpace() {
        try (McpSyncClient client = editor(READ_GROUP, WRITE_GROUP, ENG_SPACE_GROUP, OPS_SPACE_GROUP)) {
            assertThat(text(call(client, "move_page", Map.of("pageId", "1001", "targetParentId", "1001"))))
                    .contains("\"error\":\"invalid_argument\"");
            assertThat(text(call(client, "move_page", Map.of("pageId", "1000", "targetParentId", "1720"))))
                    .contains("\"error\":\"invalid_argument\"");
            assertThat(text(call(client, "move_page", Map.of("pageId", "1710", "targetParentId", "4002"))))
                    .contains("\"error\":\"invalid_argument\"", "same space");
        }
        assertThat(writes()).isEmpty();
    }

    @Test
    void aTargetOrPageOutsideTheWriteGrantIsNotFoundOrDeniedAndNothingIsWritten() {
        try (McpSyncClient client = writerWithCredential(PAT)) {
            McpSchema.CallToolResult otherSpace = call(client, "move_page", Map.of("pageId", "1710", "targetParentId", "4002"));
            McpSchema.CallToolResult restricted = call(client, "move_page", Map.of("pageId", "1710", "targetParentId", "2000"));
            McpSchema.CallToolResult missing = call(client, "move_page", Map.of("pageId", "1710", "targetParentId", "1404"));
            McpSchema.CallToolResult pageOutside = call(client, "move_page", Map.of("pageId", "4001", "targetParentId", "1000"));

            assertNotFoundOrDenied(otherSpace);
            assertThat(text(restricted)).isEqualTo(text(otherSpace));
            assertThat(text(missing)).isEqualTo(text(otherSpace));
            assertThat(text(pageOutside)).isEqualTo(text(otherSpace));
        }
        assertThat(writes()).isEmpty();
    }

    @Test
    void aReaderOrAGranteeWithoutTheWriteGroupCannotMove() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertThat(text(call(client, "move_page", Map.of("pageId", "1710", "targetParentId", "1001"))))
                    .contains("\"error\":\"access_denied\"");
        }
        try (McpSyncClient client = editor(READ_GROUP, ENG_SPACE_GROUP)) {
            assertThat(text(call(client, "move_page", Map.of("pageId", "1710", "targetParentId", "1001"))))
                    .contains("\"error\":\"access_denied\"");
        }
        assertThat(BACKEND.requests()).isEmpty();
    }

    @Test
    void aSubtreeGrantMovesPagesInsideItAndIntoAnotherGrantedTreeButNotTheRootOutOfIt() {
        try (McpSyncClient client = editor(READ_GROUP, WRITE_GROUP, RELEASE_EDITORS_GROUP, CONTENDED_EDITORS_GROUP)) {
            McpSchema.CallToolResult inside = call(client, "move_page", Map.of("pageId", "1720", "targetParentId", "1700"));
            assertThat(inside.isError()).as(text(inside)).isFalse();
            assertThat(writes()).as("the old parent 1001 and the new parent 1700 are both granted").hasSize(1);

            BACKEND.reset();
            McpSchema.CallToolResult leaving = call(client, "move_page", Map.of("pageId", "1001", "targetParentId", "1700"));
            assertNotFoundOrDenied(leaving);
            assertThat(writes()).as("1001 is granted, its parent 1000 is not").isEmpty();
        }
        try (McpSyncClient client = editor(READ_GROUP, WRITE_GROUP, RELEASE_EDITORS_GROUP)) {
            assertNotFoundOrDenied(call(client, "move_page", Map.of("pageId", "1720", "targetParentId", "1700")));
        }
        assertThat(writes()).isEmpty();
    }

    @Test
    void aTopLevelPageNeedsAGrantOnTheSpaceNotOnATree() {
        try (McpSyncClient client = editor(READ_GROUP, WRITE_GROUP, HANDBOOK_EDITORS_GROUP, RELEASE_EDITORS_GROUP)) {
            assertNotFoundOrDenied(call(client, "move_page", Map.of("pageId", "1730", "targetParentId", "1001")));
        }
        assertThat(writes()).isEmpty();
    }

    @Test
    void aBackendThatPutsThePageElsewhereIsAnErrorWithoutItsDetail() {
        try (McpSyncClient client = writerWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "move_page", Map.of("pageId", "1711", "targetParentId", "1001"));

            assertThat(text(result)).contains("\"error\":\"internal_error\"");
        }
    }

    @Test
    void anEditInBetweenIsAConflictAndInvalidIdsAreRejected() {
        try (McpSyncClient client = writerWithCredential(PAT)) {
            McpSchema.CallToolResult raced = call(client, "move_page", Map.of("pageId", "1700", "targetParentId", "1710"));

            assertThat(text(raced)).contains("\"error\":\"invalid_argument\"", "not at the version you read");
            assertRejectedArgument(call(client, "move_page", Map.of("pageId", "1710", "targetParentId", "10/../20")));
            assertRejectedArgument(call(client, "move_page", Map.of("pageId", "1710")));
        }
    }

    @Test
    void withoutCredentialOrWithAnExpiredOneWritesAskToConnectTheAccount() {
        try (McpSyncClient client = writerWithCredential(null)) {
            assertCredentialRequired(call(client, "move_page", Map.of("pageId", "1710", "targetParentId", "1001")));
            assertCredentialRequired(call(client, "reply_to_comment", Map.of("pageId", "1000", "commentId", "7001", "body", "x")));
        }
        try (McpSyncClient client = writerWithCredential(StubBackend.EXPIRED_CREDENTIAL)) {
            assertCredentialRequired(call(client, "move_page", Map.of("pageId", "1710", "targetParentId", "1001")));
        }
        assertThat(writes()).isEmpty();
    }
}
