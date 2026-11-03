package pl.aiwcorpo.mcp.confluence.app;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import pl.aiwcorpo.mcp.platform.capability.BackendCapabilities;
import pl.aiwcorpo.mcp.platform.capability.Capabilities;
import pl.aiwcorpo.mcp.platform.test.TestGateway;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole server over HTTP, with TestGateway in place of the gateway and {@link StubBackend} in place of
 * the backend system. Shared by the test classes so they run against one application context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
abstract class ServerTestSupport {

    static final String SERVER_ID = "mcp-confluence";
    static final String SYSTEM = "confluence";
    static final String ENDPOINT = "/mcp";
    static final String PAT = "Bearer confluence-pat-of-jkowalski-7f3a";
    static final String READ_GROUP = "mcp-confluence-read";
    static final String WRITE_GROUP = "mcp-confluence-write";
    /** Maps to the scope {@code Confluence::Space/ENG} in the sample bundle of the contracts. */
    static final String ENG_SPACE_GROUP = "mcp-confluence-space-ENG";
    /** Maps to the page tree {@code Confluence::Page/1000} in the sample bundle of the contracts. */
    static final String HANDBOOK_EDITORS_GROUP = "mcp-confluence-handbook-editors";
    static final Set<String> CONTRACT_SYSTEMS = contractSystems();

    static final TestGateway GATEWAY = TestGateway.start(SERVER_ID);
    static final StubBackend BACKEND = StubBackend.start();

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry registry) {
        GATEWAY.serverProperties().forEach((name, value) -> registry.add(name, () -> value));
        registry.add("confluence.backend.base-url", BACKEND::baseUrl);
        // TestGateway accepts user-oauth too; Confluence takes only the PAT, as in application.yml.
        registry.add("aiwcorpo.mcp.platform.manifest.accepted-credential-types", () -> "user-secret");
        // Short limits so the timeout and size tests run quickly; StubBackend's slow and huge pages exceed them.
        registry.add("confluence.backend.read-timeout", () -> "1s");
        registry.add("confluence.backend.max-response-size", () -> "64KB");
        // The version probe repeats every second, so a test that switches the stub to another release sees it quickly.
        registry.add("aiwcorpo.mcp.platform.capability.interval", () -> "1s");
        registry.add("aiwcorpo.mcp.platform.capability.retry-interval", () -> "1s");
    }

    static final String DEFAULT_PROFILE = "confluence-dc-10.2";

    @Autowired
    BackendCapabilities capabilities;

    /** Every test starts against Confluence 10.2, whatever profile the previous one left behind. */
    @BeforeEach
    void backendIsTheDefaultRelease() throws InterruptedException {
        useBackendRelease(DEFAULT_PROFILE, Capabilities.State.SUPPORTED);
    }

    /** Switches the stub to a release and waits until the probe has seen it. */
    void useBackendRelease(String profile, Capabilities.State expected) throws InterruptedException {
        BACKEND.profile(profile);
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!reports(expected, versionOf(profile))) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the probe did not report " + expected + " for " + profile + ": "
                        + capabilities.of("confluence"));
            }
            Thread.sleep(100);
        }
    }

    private boolean reports(Capabilities.State expected, String version) {
        Capabilities now = capabilities.of("confluence");
        return now.state() == expected && now.backendVersion() != null && now.backendVersion().startsWith(version);
    }

    private static String versionOf(String profile) {
        return profile.substring(profile.lastIndexOf('-') + 1);
    }

    @LocalServerPort
    int port;

    String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    /** An MCP client of a user in the read group, reaching the server the way the gateway does, with {@code credential} as C3 header. */
    McpSyncClient clientWithCredential(String credential) {
        return clientWithToken(ServerTestSupport::reader, credential);
    }

    /** A user who may also write: the write group, and the space ENG granted through its group. */
    McpSyncClient writerWithCredential(String credential) {
        return clientWithToken(ServerTestSupport::writer, credential);
    }

    static void reader(TestGateway.Token user) {
        user.username("jkowalski").groups(List.of(READ_GROUP));
    }

    static void writer(TestGateway.Token user) {
        user.username("jkowalski").groups(List.of(READ_GROUP, WRITE_GROUP, ENG_SPACE_GROUP));
    }

    /** As {@link #clientWithCredential}, with the caller's token shaped by {@code user} . */
    McpSyncClient clientWithToken(java.util.function.Consumer<TestGateway.Token> user, String credential) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(baseUrl())
                .endpoint(ENDPOINT)
                .openConnectionOnStartup(false)
                .httpRequestCustomizer(GATEWAY.asGateway(user, credential))
                .build();
        McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20)).build();
        client.initialize();
        return client;
    }

    /**
     * The starter sends only C10 errors valid against {@code tool-error.schema.json}, whose {@code system} is the
     * closed {@code SystemId} list of the contracts. For a system outside it (the template's placeholder, a
     * trial name) a {@code credential_required} becomes {@code internal_error}; for the platform's systems the
     * full error with the portal link is expected.
     */
    static void assertCredentialRequired(McpSchema.CallToolResult result) {
        assertThat(result.isError()).isTrue();
        if (CONTRACT_SYSTEMS.contains(SYSTEM)) {
            assertThat(text(result)).contains("\"error\":\"credential_required\"", "\"system\":\"" + SYSTEM + "\"",
                    "\"connectUrl\":\"http://localhost:18090/connections?system=" + SYSTEM + "\"");
        } else {
            assertThat(text(result)).contains("\"error\":\"internal_error\"");
        }
    }

    /**
     * The argument was refused before Confluence, as C10 {@code invalid_argument}: by the starter's check against
     * the published inputSchema, or, for what the schema allows, by the tool.
     */
    static void assertRejectedArgument(McpSchema.CallToolResult result) {
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("\"error\":\"invalid_argument\"").doesNotContain("input validation failed");
    }

    /** The one answer for a page or space that is missing, hidden or restricted. */
    static void assertNotFoundOrDenied(McpSchema.CallToolResult result) {
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("\"error\":\"not_found_or_denied\"");
    }

    private static Set<String> contractSystems() {
        try (InputStream schema = ServerTestSupport.class.getClassLoader()
                .getResourceAsStream("mcp-contracts/schemas/common.schema.json")) {
            Set<String> systems = new HashSet<>();
            JsonMapper.builder().build().readTree(schema).at("/$defs/SystemId/enum")
                    .forEach(system -> systems.add(system.asString()));
            return Set.copyOf(systems);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    static McpSchema.CallToolResult call(McpSyncClient client, String tool, Map<String, Object> arguments) {
        return client.callTool(McpSchema.CallToolRequest.builder(tool).arguments(arguments).build());
    }

    static String text(McpSchema.CallToolResult result) {
        StringBuilder text = new StringBuilder();
        result.content().forEach(content -> {
            if (content instanceof McpSchema.TextContent t) {
                text.append(t.text());
            }
        });
        return text.toString();
    }
}
