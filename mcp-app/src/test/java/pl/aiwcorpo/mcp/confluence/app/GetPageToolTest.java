package pl.aiwcorpo.mcp.confluence.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import pl.aiwcorpo.mcp.confluence.core.port.out.ConfluenceResourcePort;
import pl.aiwcorpo.mcp.platform.security.CredentialRequiredException;

/** {@code get_page} through the test gateway: the C3 credential reaches Confluence, errors are C10. */
@ExtendWith(OutputCaptureExtension.class)
class GetPageToolTest extends ServerTestSupport {

    @Autowired
    ConfluenceResourcePort resourcePort;

    @BeforeEach
    void resetBackend() {
        BACKEND.reset();
    }

    @Test
    void readsThePageWithTheCredentialFromTheGateway() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "get_page", Map.of("pageId", "1000"));

            assertThat(result.isError()).isFalse();
            assertThat(text(result)).contains("\"id\":\"1000\"", "\"spaceKey\":\"ENG\"",
                    "\"title\":{\"kind\":\"untrusted-data\",\"format\":\"text/plain\",\"text\":\"Engineering home\",\"truncated\":false}", "\"version\":7", "\"kind\":\"untrusted-data\"",
                    "\"text\":\"Welcome to engineering.\"")
                    .as("Confluence fields outside the result stay in the adapter")
                    .doesNotContain("_links", "_expandable", "webui", "displayName", "<p>");
            assertThat(BACKEND.authorizations()).as("page, labels of its space, body: all as the user").containsOnly(PAT).hasSize(3);
        }
    }

    @Test
    void acceptsTheVirtualServerClaimsOfACallThroughALink() {
        try (McpSyncClient client = clientWithToken(
                user -> { reader(user); user.virtualServer("team-docs", Map.of()); }, PAT)) {
            McpSchema.CallToolResult result = call(client, "get_page", Map.of("pageId", "1000"));

            assertThat(result.isError()).isFalse();
            assertThat(text(result)).contains("\"id\":\"1000\"");
        }
    }

    @Test
    void contentIncludedFromOtherPagesIsNeverReturned() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "get_page", Map.of("pageId", "1001"));

            assertThat(result.isError()).isFalse();
            assertThat(text(result))
                    .contains("Release 2.1 ships on Friday.", "Freeze starts Thursday.", "\"ancestorIds\":[\"1000\"]",
                            "\"omittedMacros\":[\"include\",\"excerpt-include\"]", "[macro omitted: include]")
                    .doesNotContain(StubBackend.HR_SECRET, "Salaries", "Bonuses", "\"HR\"");
            assertThat(tools.jackson.databind.json.JsonMapper.builder().build().readTree(text(result)).at("/body").has("omittedMacros"))
                    .as("omittedMacros sits next to body, not inside it").isFalse();
        }
        assertThat(BACKEND.requests()).extracting(StubBackend.Request::path)
                .containsExactly("/rest/api/content/1001", "/rest/api/space/ENG", "/rest/api/content/1001");
        assertThat(BACKEND.requests().getLast().query()).containsEntry("expand", "body.storage,space,version,ancestors");
    }

    @Test
    void blogPostsAndPersonalSpacesAreNotFound() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult missing = call(client, "get_page", Map.of("pageId", "1404"));
            McpSchema.CallToolResult blogPost = call(client, "get_page", Map.of("pageId", "1002"));
            McpSchema.CallToolResult personal = call(client, "get_page", Map.of("pageId", "3000"));

            assertThat(text(missing)).contains("\"error\":\"not_found_or_denied\"");
            assertThat(text(blogPost)).isEqualTo(text(missing));
            assertThat(text(personal)).isEqualTo(text(missing)).doesNotContain("private");
        }
    }

    @Test
    void missingAndForbiddenPagesLookTheSame() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult missing = call(client, "get_page", Map.of("pageId", "1404"));
            McpSchema.CallToolResult forbidden = call(client, "get_page", Map.of("pageId", "1403"));

            assertThat(missing.isError()).isTrue();
            assertThat(text(missing)).contains("\"error\":\"not_found_or_denied\"").doesNotContain("ContentId");
            assertThat(text(forbidden)).isEqualTo(text(missing));
        }
    }

    @Test
    void withoutCredentialAsksToConnectTheAccountAndDoesNotCallConfluence() {
        try (McpSyncClient client = clientWithCredential(null)) {
            assertCredentialRequired(call(client, "get_page", Map.of("pageId", "1000")));
            assertThat(BACKEND.authorizations()).isEmpty();
        }
    }

    @Test
    void credentialRejectedByConfluenceAsksToReconnect(CapturedOutput output) {
        try (McpSyncClient client = clientWithCredential(StubBackend.EXPIRED_CREDENTIAL)) {
            assertCredentialRequired(call(client, "get_page", Map.of("pageId", "1000")));
        }
        assertThat(output.getAll()).contains("CredentialRequiredException");
    }

    @Test
    void aCredentialThatIsNotBearerIsNotSentToConfluence() {
        try (McpSyncClient client = clientWithCredential("Basic amtvd2Fsc2tpOnNlY3JldA==")) {
            assertCredentialRequired(call(client, "get_page", Map.of("pageId", "1000")));
            assertThat(BACKEND.authorizations()).isEmpty();
        }
    }

    @Test
    void theAdapterHasNoCredentialOutsideAGatewayRequest() {
        assertThatThrownBy(() -> resourcePort.findPage("1000")).isInstanceOf(CredentialRequiredException.class);
        assertThat(BACKEND.authorizations()).isEmpty();
    }

    @Test
    void anInvalidPageIdIsRejectedBeforeConfluence() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            for (String pageId : new String[] {"../1000", "1000?expand=body.view", "0", "01000", "-1", "1e3",
                    "1".repeat(20), "", " 1000"}) {
                assertRejectedArgument(call(client, "get_page", Map.of("pageId", pageId)));
            }
            assertThat(BACKEND.authorizations()).isEmpty();
        }
    }

    @Test
    void anUnreadableBodyStaysOutOfTheResultAndTheLog(CapturedOutput output) {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "get_page", Map.of("pageId", "1601"));

            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("\"error\":\"internal_error\"").doesNotContain(StubBackend.BODY_MARKER);
        }
        assertThat(output.getAll()).contains("BackendResponseException").doesNotContain(StubBackend.BODY_MARKER);
    }

    @Test
    void aPageWithoutIdOrWithoutBodyIsTheBackendsFaultNotAMissingPage() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            for (String pageId : new String[] {"1602", "1604"}) {
                McpSchema.CallToolResult result = call(client, "get_page", Map.of("pageId", pageId));

                assertThat(result.isError()).isTrue();
                assertThat(text(result)).contains("\"error\":\"internal_error\"").doesNotContain(StubBackend.BODY_MARKER);
            }
        }
    }

    @Test
    void aRedirectIsNotFollowedAndNotReadAsMissing(CapturedOutput output) {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "get_page", Map.of("pageId", "1600"));

            assertThat(text(result)).contains("\"error\":\"internal_error\"").doesNotContain(StubBackend.BODY_MARKER);
            assertThat(BACKEND.elsewhereHits()).as("the credential must not follow the Location").isEmpty();
        }
        assertThat(output.getAll()).doesNotContain(StubBackend.BODY_MARKER);
    }

    @Test
    void anOversizedResponseIsCutOffBeforeItIsParsed() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "get_page", Map.of("pageId", "1603"));

            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("\"error\":\"internal_error\"").doesNotContain("xxxx");
        }
    }

    @Test
    void anUnknownArgumentIsRejectedWithoutBeingNamedInTheLog(CapturedOutput output) {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "get_page",
                    Map.of("pageId", "1000", "forged\nevent=tool_call", "x"));

            assertRejectedArgument(result);
            assertThat(BACKEND.authorizations()).isEmpty();
        }
        assertThat(output.getAll()).doesNotContain("forged");
    }

    @Test
    void theCredentialAndConfluenceErrorsNeverAppearInTheResultOrTheLog(CapturedOutput output) {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "get_page", Map.of("pageId", "1500"));

            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("\"error\":\"internal_error\"").doesNotContain("confluence-pat");
        }
        assertThat(output.getAll()).contains("\"tool\":\"get_page\"").doesNotContain("confluence-pat")
                .as("Confluence's error body stays out of the log").doesNotContain("boom");
    }
}
