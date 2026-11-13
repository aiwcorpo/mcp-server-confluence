package pl.aiwcorpo.mcp.confluence.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.catalina.util.ServerInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import pl.aiwcorpo.mcp.confluence.core.domain.ConfluenceIds;
import pl.aiwcorpo.mcp.confluence.integration.ConfluenceBackendProperties;
import pl.aiwcorpo.mcp.confluence.integration.ConfluenceResourceAdapter;
import tools.jackson.core.Version;
import tools.jackson.core.json.PackageVersion;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** What the production configuration must keep, checked without starting a server. */
class ProductionConfigurationTest {

    @Test
    void theServerAsksForNoClientCertificateAndLeavesTlsToTheStarter() throws IOException {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));

        // The gateway proves itself with the internal JWT, not with a certificate; the certificate of the server comes from
        // MCP_TLS_*, or the starter generates one (CC-249), so the file sets neither the client auth nor a bundle.
        assertThat(documents).allSatisfy(document -> {
            assertThat(document.containsProperty("server.ssl.client-auth")).isFalse();
            assertThat(document.containsProperty("server.ssl.bundle")).isFalse();
            assertThat(document.containsProperty("spring.ssl.bundle.jks.mcp-server.keystore.location")).isFalse();
        });
    }

    @Test
    void thePolicyDataIsPushedByTheGatewayAndNoPolicyBundleSettingRemains() throws IOException {
        Map<String, Object> base = (Map<String, Object>) new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml")).getFirst().getSource();

        assertThat(base.containsKey("aiwcorpo.mcp.platform.policy-data.url")).as("pushed by the gateway").isFalse();
        assertThat(base.keySet()).noneMatch(key -> key.startsWith("aiwcorpo.mcp.platform.policy."));
    }

    @Test
    void theIdGrammarsInTheCodeAreTheIdPatternsOfTheDeclaredTypes() throws IOException {
        try (InputStream types = new ClassPathResource("mcp/policies/resource-types.json").getInputStream()) {
            JsonNode declared = JsonMapper.builder().build().readTree(types);
            Map<String, String> patterns = new HashMap<>();
            declared.forEach(type -> patterns.put(type.get("type").asString(), type.get("idPattern").asString()));

            assertThat(patterns.get("Confluence::Space")).isEqualTo(ConfluenceIds.SPACE_KEY);
            assertThat(patterns.get("Confluence::Page")).isEqualTo(ConfluenceIds.PAGE_ID);
        }
    }

    @Test
    void outsideDevTheBackendMustBeHttps() {
        assertThatThrownBy(() -> adapter("http://backend.internal:8080", new MockEnvironment()))
                .hasMessageContaining("https");
        assertThatCode(() -> adapter("https://backend.internal", new MockEnvironment())).doesNotThrowAnyException();

        assertThatCode(() -> new ConfluenceResourceAdapter(
                new ConfluenceBackendProperties("http://confluence.internal:8080", null, null, null, null, true, false),
                new DefaultListableBeanFactory().getBeanProvider(SslBundles.class), new MockEnvironment()))
                .as("explicit opt-in for the dev environment's mocks").doesNotThrowAnyException();

        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        assertThatCode(() -> adapter("http://localhost:18081", dev)).doesNotThrowAnyException();
    }

    @Test
    void devIsReachableOnLoopbackOnly() throws IOException {
        Map<String, Object> dev = (Map<String, Object>) new YamlPropertySourceLoader()
                .load("application-dev.yml", new ClassPathResource("application-dev.yml")).getFirst().getSource();
        assertThat(String.valueOf(dev.get("server.address"))).isEqualTo("127.0.0.1");
    }

    @Test
    void theEmbeddedTomcatAndJacksonIncludeTheirSecurityFixes() {
        // The Boot parent stays on the starter's version, so the pom overrides these two; a parent bump must not lower them.
        int[] tomcat = Arrays.stream(ServerInfo.getServerNumber().split("\\.")).mapToInt(Integer::parseInt).toArray();
        assertThat(Arrays.compare(tomcat, new int[] {11, 0, 27})).as("Tomcat %s", ServerInfo.getServerNumber()).isNotNegative();
        assertThat(PackageVersion.VERSION).isGreaterThanOrEqualTo(new Version(3, 1, 7, null, null, null));
    }

    private static ConfluenceResourceAdapter adapter(String baseUrl, MockEnvironment environment) {
        return new ConfluenceResourceAdapter(new ConfluenceBackendProperties(baseUrl, null, null, null, null, false, false),
                new DefaultListableBeanFactory().getBeanProvider(SslBundles.class), environment);
    }
}
