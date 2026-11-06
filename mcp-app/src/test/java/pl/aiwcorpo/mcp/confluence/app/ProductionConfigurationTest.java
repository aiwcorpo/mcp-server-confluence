package pl.aiwcorpo.mcp.confluence.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** What the production configuration must keep, checked without starting a server. */
class ProductionConfigurationTest {

    @Test
    void outsideDevTheServerDemandsAClientCertificate() throws IOException {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));

        PropertySource<?> production = documents.stream()
                .filter(document -> "!dev".equals(document.getProperty("spring.config.activate.on-profile")))
                .findFirst().orElseThrow();
        assertThat(String.valueOf(production.getProperty("server.ssl.client-auth"))).isEqualTo("need");
        assertThat(String.valueOf(production.getProperty("server.ssl.bundle"))).isEqualTo("mcp-server");
        assertThat(documents).allSatisfy(document -> {
            Object clientAuth = document.getProperty("server.ssl.client-auth");
            assertThat(clientAuth == null || "need".equals(String.valueOf(clientAuth))).isTrue();
        });
    }

    @Test
    void thePolicyDataComeFromTheGatewayAndNoPolicyBundleSettingRemains() throws IOException {
        Map<String, Object> base = (Map<String, Object>) new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml")).getFirst().getSource();

        assertThat(String.valueOf(base.get("aiwcorpo.mcp.platform.policy-data.url"))).isEqualTo("${MCP_GATEWAY_POLICY_DATA_URL:}");
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

    private static ConfluenceResourceAdapter adapter(String baseUrl, MockEnvironment environment) {
        return new ConfluenceResourceAdapter(new ConfluenceBackendProperties(baseUrl, null, null, null, null, false, false),
                new DefaultListableBeanFactory().getBeanProvider(SslBundles.class), environment);
    }
}
