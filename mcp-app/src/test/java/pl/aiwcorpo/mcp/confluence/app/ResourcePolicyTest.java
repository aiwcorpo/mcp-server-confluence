package pl.aiwcorpo.mcp.confluence.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.platform.test.TestGateway;

/**
 * The policy (C7) on pages and spaces: a page is judged by the space Confluence says it is in and by the labels of that
 * space, whatever the argument looks like, and every refusal looks like a missing page.
 */
class ResourcePolicyTest extends ServerTestSupport {

    @BeforeEach
    void resetBackend() {
        BACKEND.reset();
    }

    @Test
    void aPageInARestrictedSpaceIsNotFoundOrDeniedLikeAMissingOne() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult missing = call(client, "get_page", Map.of("pageId", "1404"));
            McpSchema.CallToolResult restricted = call(client, "get_page", Map.of("pageId", "2000"));

            assertNotFoundOrDenied(restricted);
            assertThat(text(restricted)).isEqualTo(text(missing)).doesNotContain(StubBackend.HR_SECRET);
        }
        assertThat(BACKEND.requests()).as("the body of a refused page is never requested")
                .noneMatch(request -> request.query().getOrDefault("expand", "").contains("body.storage"));
    }

    @Test
    void aSpaceLabelledRestrictedIsNotFoundOrDeniedLikeAMissingOne() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult missing = call(client, "get_space", Map.of("space", "NOSUCH"));
            McpSchema.CallToolResult restricted = call(client, "get_space", Map.of("space", "HR"));

            assertNotFoundOrDenied(restricted);
            assertThat(text(restricted)).isEqualTo(text(missing));
        }
    }

    @Test
    void aVisibleSpaceIsReturnedWithoutItsLabels() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "get_space", Map.of("space", "OPS"));

            assertThat(result.isError()).isFalse();
            assertThat(text(result)).contains("\"key\":\"OPS\"", "OPS space").doesNotContain("team-x", "labels");
        }
    }

    @Test
    void aPageWhoseSpaceLabelsAreNotKnownIsRefusedNotTreatedAsUnclassified() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult noList = call(client, "get_page", Map.of("pageId", "4000"));
            McpSchema.CallToolResult longList = call(client, "get_page", Map.of("pageId", "4003"));
            McpSchema.CallToolResult space = call(client, "get_space", Map.of("space", "UNCL"));

            for (McpSchema.CallToolResult result : List.of(noList, longList)) {
                assertNotFoundOrDenied(result);
            }
            assertThat(space.isError()).isTrue();
            assertThat(text(space)).contains("\"error\":\"internal_error\"");
            for (McpSchema.CallToolResult result : List.of(noList, longList, space)) {
                assertThat(text(result)).doesNotContain("no labels known", "unclear");
            }
        }
    }

    @Test
    void aReaderWithNoSpaceOrPageScopeStillReadsAPageTheReadPolicyAllows() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertThat(call(client, "get_page", Map.of("pageId", "1000")).isError()).isFalse();
            assertNotFoundOrDenied(call(client, "get_page", Map.of("pageId", "2000")));
        }
    }

    @Test
    void aCallerWithoutTheGroupOfTheToolGetsAccessDeniedAndNothingReachesConfluence() {
        try (McpSyncClient client = clientWithToken(user -> user.username("jkowalski").groups(List.of("mcp-nobody")), PAT)) {
            for (Map.Entry<String, Map<String, Object>> call : Map.<String, Map<String, Object>>of(
                    "get_page", Map.of("pageId", "1000"),
                    "get_space", Map.of("space", "ENG"),
                    "search_pages", Map.of("space", "ENG", "text", "release"),
                    "search_content", Map.of("space", "ENG", "cql", "text ~ \"release\"")).entrySet()) {
                McpSchema.CallToolResult result = call(client, call.getKey(), call.getValue());

                assertThat(result.isError()).as(call.getKey()).isTrue();
                assertThat(text(result)).as(call.getKey()).contains("\"error\":\"access_denied\"");
            }
            assertThat(BACKEND.authorizations()).isEmpty();
        }
    }

    @Test
    void aReaderWithoutAScopeGroupReadsPagesOfOpenSpaces() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertThat(call(client, "get_page", Map.of("pageId", "1000")).isError()).isFalse();
            assertThat(call(client, "get_page", Map.of("pageId", "4001")).isError()).isFalse();
        }
    }

    @Test
    void searchHitsAreJudgedByWhereThePageIsNowNotByTheIndex() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "search_pages", Map.of("space", "ENG", "text", "release"));

            assertThat(result.isError()).isFalse();
            assertThat(text(result)).as("open pages stay").contains("\"id\":\"1000\"", "\"id\":\"1001\"")
                    .as("indexed under ENG, now in the restricted HR").doesNotContain("\"id\":\"2000\"", "\"id\":\"2002\"",
                            "Bonus rules", "Salaries");
        }
    }

    @Test
    void searchingARestrictedSpaceIsNotFoundOrDeniedLikeAMissingOne() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult restricted = call(client, "search_pages", Map.of("space", "HR", "text", "salaries"));
            McpSchema.CallToolResult missing = call(client, "search_pages", Map.of("space", "NOSUCH", "text", "salaries"));

            assertNotFoundOrDenied(restricted);
            assertThat(text(restricted)).isEqualTo(text(missing));
        }
        assertThat(BACKEND.requests()).noneMatch(request -> request.path().endsWith("/search"));
    }

    @Test
    void aSpaceKeyInOtherCaseStillFindsThePagesOfTheCanonicalSpace() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "search_pages", Map.of("space", "eng", "text", "release"));

            assertThat(result.isError()).isFalse();
            assertThat(text(result)).contains("\"id\":\"1000\"");
        }
    }

    @Test
    void aLabelListThatFillsItsPageIsUnknownClassificationNotAnEmptyOne() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult page = call(client, "get_page", Map.of("pageId", "4004"));
            McpSchema.CallToolResult space = call(client, "get_space", Map.of("space", "FULL"));

            assertNotFoundOrDenied(page);
            assertThat(space.isError()).isTrue();
            assertThat(text(space)).contains("\"error\":\"internal_error\"");
            for (McpSchema.CallToolResult result : List.of(page, space)) {
                assertThat(text(result)).doesNotContain("full");
            }
        }
    }

    @Test
    void aHitThatCannotBeResolvedIsDroppedAndTheSearchStillAnswers() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "search_pages", Map.of("space", "FAIL", "text", "FAIL"));

            assertThat(result.isError()).isFalse();
            assertThat(text(result)).contains("\"items\":[]").doesNotContain("Failing");
        }
    }

    @Test
    void aNewPageNeedsAWriteGrantOnTheSpace() {
        Map<String, Object> inEng = Map.of("space", "ENG", "parentId", "1001", "title", "Retro", "body", "text");
        try (McpSyncClient granted = writerWithCredential(PAT)) {
            assertThat(call(granted, "create_page", inEng).isError()).isFalse();
            assertThat(call(granted, "create_page",
                    Map.of("space", "OPS", "parentId", "4002", "title", "Retro", "body", "text")).isError())
                    .as("OPS is not granted").isTrue();
        }
        BACKEND.reset();
        try (McpSyncClient treeOnly = clientWithToken(user -> user.username("jkowalski")
                .groups(List.of(READ_GROUP, WRITE_GROUP, HANDBOOK_EDITORS_GROUP)), PAT);
             McpSyncClient noGrant = clientWithToken(user -> user.username("jkowalski")
                .groups(List.of(READ_GROUP, WRITE_GROUP)), PAT)) {
            assertThat(call(treeOnly, "create_page", inEng).isError()).as("a page tree grant is not a grant on the space").isTrue();
            assertThat(call(noGrant, "create_page", inEng).isError()).isTrue();
        }
        assertThat(BACKEND.requests()).as("the grant is judged before the resolver asks Confluence").isEmpty();
    }

    @Test
    void aReaderCannotCreatePagesAndConfluenceIsNotAsked() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "create_page",
                    Map.of("space", "ENG", "parentId", "1000", "title", "T", "body", "x"));

            assertThat(text(result)).contains("\"error\":\"access_denied\"");
        }
        assertThat(BACKEND.authorizations()).isEmpty();
    }

    @Test
    void aWriteInASpaceWithoutAGrantIsRefusedByTheScopeCheckBeforeAnythingIsLookedUp() {
        try (McpSyncClient client = writerWithCredential(PAT)) {
            McpSchema.CallToolResult ungranted = call(client, "create_page",
                    Map.of("space", "OPS", "parentId", "1000", "title", "T", "body", "x"));
            McpSchema.CallToolResult ungrantedMissingParent = call(client, "create_page",
                    Map.of("space", "OPS", "parentId", "1404", "title", "T", "body", "x"));

            assertThat(text(ungranted)).contains("\"error\":\"access_denied\"").isEqualTo(text(ungrantedMissingParent));
        }
        assertThat(BACKEND.requests()).isEmpty();
    }

    @Test
    void aParentOfAnotherSpaceThanTheNamedOneIsNotFoundAndNothingIsCreated() {
        try (McpSyncClient client = writerWithCredential(PAT)) {
            McpSchema.CallToolResult mismatch = call(client, "create_page",
                    Map.of("space", "ENG", "parentId", "4002", "title", "T", "body", "x"));
            McpSchema.CallToolResult missing = call(client, "create_page",
                    Map.of("space", "ENG", "parentId", "1404", "title", "T", "body", "x"));

            assertNotFoundOrDenied(mismatch);
            assertThat(text(mismatch)).isEqualTo(text(missing));
        }
        assertThat(BACKEND.requests()).noneMatch(request -> "POST".equals(request.method()));
    }

    @Test
    void aParentInARestrictedSpaceIsNotFoundOrDeniedEvenForAWriterOfThatSpace() {
        try (McpSyncClient client = clientWithToken(user -> user.username("jkowalski")
                .groups(List.of(READ_GROUP, WRITE_GROUP, HR_SPACE_GROUP)), PAT)) {
            McpSchema.CallToolResult restricted = call(client, "create_page",
                    Map.of("space", "HR", "parentId", "2000", "title", "T", "body", "x"));
            McpSchema.CallToolResult missing = call(client, "create_page",
                    Map.of("space", "HR", "parentId", "1404", "title", "T", "body", "x"));

            assertNotFoundOrDenied(restricted);
            assertThat(text(restricted)).isEqualTo(text(missing));
        }
        assertThat(BACKEND.requests()).noneMatch(request -> "POST".equals(request.method()));
    }
}
