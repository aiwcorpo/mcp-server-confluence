package pl.aiwcorpo.mcp.confluence.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import pl.aiwcorpo.mcp.confluence.integration.ConfluenceVersionProbe;
import pl.aiwcorpo.mcp.platform.capability.Capabilities;

/**
 * What the server does on each release it can meet: the fixtures under {@code fixtures/confluence-dc-*} give the
 * answers of {@code server-information} and of the application-links manifest, the probe turns them into a state, and
 * the state decides whether the server writes.
 */
class VersionCoverageTest extends ServerTestSupport {

    @Autowired
    ConfluenceVersionProbe probe;

    @Test
    void release85ReportsItsVersionAsMajorMinorPatchAndIsSupported() {
        BACKEND.profile("confluence-dc-8.5");

        Capabilities capabilities = probe.probe();

        assertThat(capabilities.state()).isEqualTo(Capabilities.State.SUPPORTED);
        assertThat(capabilities.backendVersion()).isEqualTo("8.5.4");
        assertThat(capabilities.features()).containsExactlyInAnyOrder("read", "write");
    }

    @Test
    void release92HidesServerInformationSoTheManifestGivesTheVersion() {
        BACKEND.profile("confluence-dc-9.2");

        Capabilities capabilities = probe.probe();

        assertThat(capabilities.state()).isEqualTo(Capabilities.State.SUPPORTED);
        assertThat(capabilities.backendVersion()).isEqualTo("9.2.3");
    }

    @Test
    void release102ReportsAVersionString() {
        Capabilities capabilities = probe.probe();

        assertThat(capabilities.state()).isEqualTo(Capabilities.State.SUPPORTED);
        assertThat(capabilities.backendVersion()).isEqualTo("10.2.1");
    }

    @Test
    void aReleaseOlderThanTheMinimumReadsOnly() {
        BACKEND.profile("confluence-dc-7.19");

        Capabilities capabilities = probe.probe();

        assertThat(capabilities.state()).isEqualTo(Capabilities.State.DEGRADED);
        assertThat(capabilities.features()).containsExactly("read");
        assertThat(capabilities.detail()).contains("older than 8.5");
    }

    @Test
    void aReleaseNewerThanTestedReadsOnly() {
        BACKEND.profile("confluence-dc-11.0");

        Capabilities capabilities = probe.probe();

        assertThat(capabilities.state()).isEqualTo(Capabilities.State.DEGRADED);
        assertThat(capabilities.features()).containsExactly("read");
    }

    @Test
    void anAnswerThatIsNotAVersionIsUnavailableNotGuessed() {
        BACKEND.profile("confluence-dc-unknown");

        assertThat(probe.probe().state()).isEqualTo(Capabilities.State.UNAVAILABLE);
    }

    @Test
    void theProbeSendsNoCredential() {
        BACKEND.profile("confluence-dc-9.2");
        probe.probe();

        assertThat(BACKEND.probeAuthorizations()).isNotEmpty().allMatch("null"::equals);
    }

    @Test
    void onAnUnverifiedReleaseTheServerStillReadsButDoesNotWrite() throws Exception {
        useBackendRelease("confluence-dc-11.0", Capabilities.State.DEGRADED);
        BACKEND.reset();
        try (McpSyncClient client = writerWithCredential(PAT)) {
            assertThat(call(client, "get_page", Map.of("pageId", "1000")).isError()).isFalse();
            assertThat(call(client, "list_labels", Map.of("pageId", "1000")).isError()).isFalse();

            McpSchema.CallToolResult update = call(client, "update_page", Map.of("pageId", "1000",
                    "expectedVersion", 7, "mode", "append", "body", "more"));
            McpSchema.CallToolResult create = call(client, "create_page", Map.of("space", "ENG", "parentId", "1000",
                    "title", "T", "body", "x"));
            McpSchema.CallToolResult label = call(client, "add_label", Map.of("pageId", "1000", "label", "x"));
            McpSchema.CallToolResult comment = call(client, "add_comment", Map.of("pageId", "1000", "body", "x"));

            for (McpSchema.CallToolResult refused : new McpSchema.CallToolResult[] {update, create, label, comment}) {
                assertThat(refused.isError()).isTrue();
                assertThat(text(refused)).contains("\"error\":\"upstream_unavailable\"", "Reading still works");
            }
        }
        assertThat(BACKEND.requests()).noneMatch(request -> !"GET".equals(request.method()));
    }
}
