package pl.aiwcorpo.mcp.confluence.integration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Where the backend system is and how long and how much the server reads from it.
 *
 * @param baseUrl         base URL of the backend API ({@code MCP_CONFLUENCE_URL}); {@code https} outside the dev profile
 * @param sslBundle       Spring SSL bundle trusted for an HTTPS backend (internal CA); empty = JDK default trust
 * @param connectTimeout  time to open the connection
 * @param readTimeout     time for the whole response; the gateway gives up on the call soon after anyway
 * @param maxResponseSize larger bodies are cut off with an error instead of filling the heap
 * @param allowPlainHttp  {@code http} outside the dev profile, for the mocks of mcp-platform-dev only
 *                        ({@code MCP_CONFLUENCE_ALLOW_PLAIN_HTTP}); the credential then crosses the network in clear text
 * @param allowWritesOnUnknownVersion writes also when the capability probe found no tested version (a Confluence that
 *                        hides its version, or one newer than 10.x); the operator takes the responsibility
 *                        ({@code MCP_CONFLUENCE_WRITES_ON_UNKNOWN_VERSION})
 */
@ConfigurationProperties("confluence.backend")
public record ConfluenceBackendProperties(String baseUrl, String sslBundle, Duration connectTimeout, Duration readTimeout,
        DataSize maxResponseSize, boolean allowPlainHttp,
        boolean allowWritesOnUnknownVersion) {

    public ConfluenceBackendProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
        maxResponseSize = maxResponseSize == null ? DataSize.ofMegabytes(1) : maxResponseSize;
    }
}
