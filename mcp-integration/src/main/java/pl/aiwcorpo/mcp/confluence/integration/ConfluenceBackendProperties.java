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
 * @param allowPlainHttp  {@code http} outside the dev profile, for the mocks of the dev stack only
 *                        ({@code MCP_CONFLUENCE_ALLOW_PLAIN_HTTP}); the credential then crosses the network in clear text
 * @param allowWritesOnUnknownVersion opens writes only when the capability probe ended in {@code unavailable} (no
 *                        version number could be read) and has answered at least once; before the first answer writes
 *                        stay closed. A version the probe read but did not test (older than 8.5, 11.x) stays read
 *                        only whatever the flag says; the operator takes the responsibility for the opened case
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
