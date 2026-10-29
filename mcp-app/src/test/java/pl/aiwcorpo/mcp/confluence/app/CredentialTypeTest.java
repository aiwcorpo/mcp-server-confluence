package pl.aiwcorpo.mcp.confluence.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.platform.security.BackendCredential;

/** The server accepts only {@code user-secret}: any other type sent with the credential is refused before the backend. */
class CredentialTypeTest extends ServerTestSupport {

    @BeforeEach
    void resetBackend() {
        BACKEND.reset();
    }

    @Test
    void userSecretIsAccepted() {
        try (McpSyncClient client = clientWithType(BackendCredential.Type.USER_SECRET)) {
            assertThat(call(client, "get_space", Map.of("space", "OPS")).isError()).isFalse();
        }
    }

    @Test
    void everyOtherTypeIsRefusedAndNothingReachesConfluence() {
        for (BackendCredential.Type type : new BackendCredential.Type[] {BackendCredential.Type.USER_OAUTH,
                BackendCredential.Type.TOKEN_EXCHANGE, BackendCredential.Type.SERVICE_ACCOUNT}) {
            try (McpSyncClient client = clientWithType(type)) {
                McpSchema.CallToolResult result = call(client, "get_space", Map.of("space", "OPS"));

                assertThat(result.isError()).as(type.name()).isTrue();
                assertThat(text(result)).as(type.name()).contains("\"error\":\"internal_error\"");
            }
        }
        assertThat(BACKEND.authorizations()).isEmpty();
    }

    private McpSyncClient clientWithType(BackendCredential.Type type) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(baseUrl())
                .endpoint(ENDPOINT)
                .openConnectionOnStartup(false)
                .httpRequestCustomizer(GATEWAY.asGateway(ServerTestSupport::reader, PAT, type))
                .build();
        McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20)).build();
        client.initialize();
        return client;
    }
}
