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
        assertThat(BACKEND.requests()).isEmpty();
    }

    @Test
    void aMissingOrMisspelledTypeHeaderIsRefusedAndNothingReachesConfluence() {
        for (String header : new String[] {null, "User-Secret", "USER_SECRET", "unknown"}) {
            try (McpSyncClient client = clientWithHeader(header)) {
                McpSchema.CallToolResult result = call(client, "get_space", Map.of("space", "OPS"));

                assertThat(result.isError()).as(String.valueOf(header)).isTrue();
                assertThat(text(result)).as(String.valueOf(header)).contains("\"error\":\"internal_error\"");
            }
        }
        assertThat(BACKEND.requests()).isEmpty();
    }

    private McpSyncClient clientWithHeader(String typeHeader) {
        var gateway = GATEWAY.asGateway(ServerTestSupport::reader, PAT, BackendCredential.Type.USER_SECRET);
        var customizer = (io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer)
                (request, method, uri, body, context) -> {
            var builder = (java.net.http.HttpRequest.Builder) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {java.net.http.HttpRequest.Builder.class},
                    (proxy, call, args) -> {
                        if (call.getName().equals("setHeader") && "X-MCP-Credential-Type".equals(args[0])) {
                            return typeHeader == null ? proxy : call.invoke(request, args[0], typeHeader);
                        }
                        return call.invoke(request, args);
                    });
            gateway.customize(builder, method, uri, body, context);
        };
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(baseUrl())
                .endpoint(ENDPOINT).openConnectionOnStartup(false).httpRequestCustomizer(customizer).build();
        McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20)).build();
        client.initialize();
        return client;
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
