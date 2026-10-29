package pl.aiwcorpo.mcp.confluence.app;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import pl.aiwcorpo.mcp.platform.test.TestGateway;

/**
 * Writes {@code target/mcp-manifest.json} in the {@code package} phase (exec-maven-plugin in mcp-app/pom.xml), so the
 * artefact that CI registers at the gateway exists after any build, also with {@code -DskipTests}. The server starts in
 * the {@code dev} profile (the only one that runs without the production environment) behind a TestGateway for the
 * JWKS and the policy bundle, but every {@code manifest.*} property comes from application.yml: the real version from
 * the pom, the internal URL and the accepted credential types, never the values TestGateway sets for tests.
 */
public final class ManifestArtefact {

    private ManifestArtefact() {
    }

    public static void main(String[] args) {
        try (TestGateway gateway = TestGateway.start("mcp-confluence")) {
            List<String> arguments = new ArrayList<>();
            gateway.serverProperties().forEach((name, value) -> {
                if (!name.startsWith("aiwcorpo.mcp.platform.manifest.")) {
                    arguments.add("--" + name + "=" + value);
                }
            });
            arguments.add("--aiwcorpo.mcp.platform.manifest.output-file=" + args[0]);
            arguments.add("--server.port=0");
            arguments.add("--spring.profiles.active=dev");
            try (ConfigurableApplicationContext context = SpringApplication.run(McpServerApplication.class,
                    arguments.toArray(String[]::new))) {
            }
        }
    }
}
