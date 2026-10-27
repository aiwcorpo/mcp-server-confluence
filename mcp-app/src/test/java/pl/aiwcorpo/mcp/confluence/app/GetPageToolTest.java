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
                    "\"title\":\"Engineering home\"", "\"version\":7", "\"kind\":\"untrusted-data\"",
                    "\"text\":\"Welcome to engineering.\"")
                    .as("Confluence fields outside the result stay in the adapter")
                    .doesNotContain("_links", "_expandable", "webui", "displayName", "<p>");
            assertThat(BACKEND.authorizations()).containsExactly(PAT);
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
        }
        assertThat(BACKEND.requests()).singleElement().satisfies(request -> {
            assertThat(request.path()).isEqualTo("/rest/api/content/1001");
            assertThat(request.query()).containsEntry("expand", "body.storage,space,version,ancestors");
        });
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
                assertRejectedArgument(call(client, "get_page", Map.of("pageId", pageId)), "pageId",
                        "pageId must be a page id");
            }
            assertThat(BACKEND.authorizations()).isEmpty();
        }
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
