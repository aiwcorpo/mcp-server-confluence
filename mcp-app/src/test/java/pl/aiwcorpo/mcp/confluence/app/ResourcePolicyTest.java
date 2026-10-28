package pl.aiwcorpo.mcp.confluence.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.platform.test.TestGateway;

/**
 * The policy (C7) on pages and spaces: a page is judged by the space Confluence says it is in and by the labels of that
 * space, whatever the argument looks like, and every refusal looks like a missing page. A virtual server link only narrows.
 */
class ResourcePolicyTest extends ServerTestSupport {

    private static final Consumer<TestGateway.Token> LINK_TO_ENG = user -> {
        reader(user);
        user.virtualServer("eng-only", Map.of("confluenceSpaces", List.of("ENG")));
    };

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

            for (McpSchema.CallToolResult result : List.of(noList, longList, space)) {
                assertThat(result.isError()).isTrue();
                assertThat(text(result)).contains("\"error\":\"internal_error\"").doesNotContain("no labels known", "unclear");
            }
        }
    }

    @Test
    void aCallerWithoutTheGroupOfTheToolGetsAccessDeniedAndNothingReachesConfluence() {
        try (McpSyncClient client = clientWithToken(user -> user.username("jkowalski").groups(List.of("mcp-nobody")), PAT)) {
            for (Map.Entry<String, Map<String, Object>> call : Map.<String, Map<String, Object>>of(
                    "get_page", Map.of("pageId", "1000"),
                    "get_space", Map.of("space", "ENG"),
                    "search_pages", Map.of("space", "ENG", "text", "release")).entrySet()) {
                McpSchema.CallToolResult result = call(client, call.getKey(), call.getValue());

                assertThat(result.isError()).as(call.getKey()).isTrue();
                assertThat(text(result)).as(call.getKey()).contains("\"error\":\"access_denied\"");
            }
            assertThat(BACKEND.authorizations()).isEmpty();
        }
    }

    @Test
    void aLinkThatNarrowsToOneSpaceHidesPagesAndSpacesOutsideIt() {
        try (McpSyncClient client = clientWithToken(LINK_TO_ENG, PAT)) {
            McpSchema.CallToolResult missing = call(client, "get_page", Map.of("pageId", "1404"));
            McpSchema.CallToolResult inside = call(client, "get_page", Map.of("pageId", "1000"));
            McpSchema.CallToolResult outside = call(client, "get_page", Map.of("pageId", "4001"));
            McpSchema.CallToolResult spaceInside = call(client, "get_space", Map.of("space", "ENG"));
            McpSchema.CallToolResult spaceOutside = call(client, "get_space", Map.of("space", "OPS"));

            assertThat(inside.isError()).isFalse();
            assertThat(spaceInside.isError()).isFalse();
            assertNotFoundOrDenied(outside);
            assertThat(text(outside)).isEqualTo(text(missing)).doesNotContain("Restart the job");
            assertThat(spaceOutside.isError()).isTrue();
        }
    }

    @Test
    void searchingOutsideTheLinkIsAnEmptyListAndDoesNotAskConfluence() {
        try (McpSyncClient client = clientWithToken(LINK_TO_ENG, PAT)) {
            McpSchema.CallToolResult result = call(client, "search_pages", Map.of("space", "OPS", "text", "runbook"));

            assertThat(result.isError()).isFalse();
            assertThat(text(result)).contains("\"pages\":[]");
        }
        assertThat(BACKEND.requests()).isEmpty();
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
    void withALinkTheSearchAlsoDropsAHitThatMovedOutOfTheLinkedSpace() {
        try (McpSyncClient everything = clientWithCredential(PAT);
             McpSyncClient linked = clientWithToken(LINK_TO_ENG, PAT)) {
            McpSchema.CallToolResult open = call(everything, "search_pages", Map.of("space", "ENG", "text", "release"));
            McpSchema.CallToolResult narrowed = call(linked, "search_pages", Map.of("space", "ENG", "text", "release"));

            assertThat(text(open)).as("moved to OPS, which is open").contains("\"id\":\"4002\"");
            assertThat(text(narrowed)).contains("\"id\":\"1000\"").doesNotContain("\"id\":\"4002\"", "Operations home");
        }
    }

    @Test
    void searchingARestrictedSpaceGivesAnEmptyListLikeAnEmptySpace() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult restricted = call(client, "search_pages", Map.of("space", "HR", "text", "salaries"));
            McpSchema.CallToolResult empty = call(client, "search_pages", Map.of("space", "SECRET", "text", "salaries"));

            assertThat(restricted.isError()).isFalse();
            assertThat(text(restricted)).contains("\"pages\":[]").doesNotContain("Salaries", "2000");
            assertThat(text(restricted).replace("HR", "SECRET")).isEqualTo(text(empty));
        }
    }

    @Test
    void aHitThatCannotBeResolvedIsDroppedAndTheSearchStillAnswers() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "search_pages", Map.of("space", "FAIL", "text", "x"));

            assertThat(result.isError()).isFalse();
            assertThat(text(result)).contains("\"pages\":[]").doesNotContain("Failing");
        }
    }

    @Test
    void aNewPageNeedsAWriteGrantOnTheParentsSpaceOrTree() {
        Map<String, Object> underHandbook = Map.of("space", "ENG", "parentId", "1001", "title", "Retro", "body", "text");
        try (McpSyncClient handbook = clientWithToken(user -> user.username("jkowalski")
                .groups(List.of(READ_GROUP, WRITE_GROUP, HANDBOOK_EDITORS_GROUP)), PAT)) {
            assertThat(call(handbook, "create_page", underHandbook).isError()).as("page tree 1000 covers 1001").isFalse();
            McpSchema.CallToolResult elsewhere = call(handbook, "create_page",
                    Map.of("space", "OPS", "parentId", "4002", "title", "Retro", "body", "text"));
            assertThat(elsewhere.isError()).as("not granted").isTrue();
        }
        BACKEND.reset();
        try (McpSyncClient noGrant = clientWithToken(user -> user.username("jkowalski")
                .groups(List.of(READ_GROUP, WRITE_GROUP)), PAT)) {
            McpSchema.CallToolResult result = call(noGrant, "create_page", underHandbook);

            assertThat(result.isError()).isTrue();
        }
        assertThat(BACKEND.requests()).noneMatch(request -> "POST".equals(request.method()));
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
    void aParentOfAnotherSpaceThanTheNamedOneIsNotFoundAndNothingIsCreated() {
        try (McpSyncClient client = writerWithCredential(PAT)) {
            McpSchema.CallToolResult mismatch = call(client, "create_page",
                    Map.of("space", "OPS", "parentId", "1000", "title", "T", "body", "x"));
            McpSchema.CallToolResult missing = call(client, "create_page",
                    Map.of("space", "OPS", "parentId", "1404", "title", "T", "body", "x"));

            assertNotFoundOrDenied(mismatch);
            assertThat(text(mismatch)).isEqualTo(text(missing));
        }
        assertThat(BACKEND.requests()).noneMatch(request -> "POST".equals(request.method()));
    }

    @Test
    void aParentInARestrictedSpaceIsNotFoundOrDeniedEvenForAWriterOfThatSpace() {
        try (McpSyncClient client = clientWithToken(user -> user.username("jkowalski")
                .groups(List.of(READ_GROUP, WRITE_GROUP, ENG_SPACE_GROUP)), PAT)) {
            McpSchema.CallToolResult restricted = call(client, "create_page",
                    Map.of("space", "HR", "parentId", "2000", "title", "T", "body", "x"));
            McpSchema.CallToolResult missing = call(client, "create_page",
                    Map.of("space", "HR", "parentId", "1404", "title", "T", "body", "x"));

            assertThat(text(restricted)).isEqualTo(text(missing));
        }
        assertThat(BACKEND.requests()).noneMatch(request -> "POST".equals(request.method()));
    }
}
