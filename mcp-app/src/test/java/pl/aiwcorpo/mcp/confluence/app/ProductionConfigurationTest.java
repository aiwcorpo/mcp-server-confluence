package pl.aiwcorpo.mcp.confluence.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import pl.aiwcorpo.mcp.confluence.integration.ConfluenceBackendProperties;
import pl.aiwcorpo.mcp.confluence.integration.ConfluenceResourceAdapter;

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
