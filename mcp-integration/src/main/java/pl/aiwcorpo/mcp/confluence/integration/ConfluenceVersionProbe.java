package pl.aiwcorpo.mcp.confluence.integration;

import java.util.Optional;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import pl.aiwcorpo.mcp.platform.capability.Capabilities;
import pl.aiwcorpo.mcp.platform.capability.CapabilityProbe;
import pl.aiwcorpo.mcp.platform.capability.ProductVersion;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the version Confluence reports about itself, without a user: {@code GET /rest/api/server-information} first
 * (JSON with {@code version}, older releases with {@code majorVersion}, {@code minorVersion} and {@code patchLevel}),
 * then the application-links manifest {@code /rest/applinks/1.0/manifest} (XML with {@code version}) for instances that
 * keep the first behind a login. Versions from {@value #MIN_MAJOR}.{@value #MIN_MINOR} up to, but not including,
 * {@value #FIRST_UNTESTED_MAJOR}.0 are the tested range; others are {@code degraded}: the server runs and reads, but does
 * not write.
 */
@Component
public class ConfluenceVersionProbe implements CapabilityProbe {

    /** Name of the probe in {@code BackendCapabilities} and in the health detail. */
    public static final String NAME = "confluence";

    /** Feature present on every version the server talks to. */
    public static final String READ = "read";

    /** Feature of the tested range: creating and changing content. */
    public static final String WRITE = "write";

    static final int MIN_MAJOR = 8;
    static final int MIN_MINOR = 5;
    static final int FIRST_UNTESTED_MAJOR = 11;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ConfluenceResourceAdapter backend;

    public ConfluenceVersionProbe(ConfluenceResourceAdapter backend) {
        this.backend = backend;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Capabilities probe() {
        String reported = fromServerInformation().or(this::fromApplinksManifest).orElse(null);
        if (reported == null) {
            return Capabilities.unavailable("neither server-information nor the applinks manifest gave a version");
        }
        Optional<ProductVersion> parsed = ProductVersion.parse(reported);
        if (parsed.isEmpty()) {
            return Capabilities.unavailable("the reported version is not a version number");
        }
        ProductVersion version = parsed.get();
        if (version.before(MIN_MAJOR, MIN_MINOR)) {
            return Capabilities.degraded(version.toString(), Set.of(READ), "older than " + MIN_MAJOR + "." + MIN_MINOR);
        }
        if (version.major() >= FIRST_UNTESTED_MAJOR) {
            return Capabilities.degraded(version.toString(), Set.of(READ),
                    "newer than the tested " + (FIRST_UNTESTED_MAJOR - 1) + ".x");
        }
        return Capabilities.supported(version.toString(), Set.of(READ, WRITE));
    }

    private Optional<String> fromServerInformation() {
        String body = backend.readAnonymous("/rest/api/server-information", MediaType.APPLICATION_JSON);
        if (body == null) {
            return Optional.empty();
        }
        try {
            JsonNode node = JSON.readTree(body);
            String version = node.path("version").asString("");
            if (!version.isBlank()) {
                return Optional.of(version);
            }
            if (node.hasNonNull("majorVersion") && node.hasNonNull("minorVersion")) {
                return Optional.of(node.path("majorVersion").asInt() + "." + node.path("minorVersion").asInt() + "."
                        + node.path("patchLevel").asInt(0));
            }
        } catch (RuntimeException notJson) {
            // A login page or a proxy answer: the manifest is the next source.
        }
        return Optional.empty();
    }

    private Optional<String> fromApplinksManifest() {
        String body = backend.readAnonymous("/rest/applinks/1.0/manifest", MediaType.APPLICATION_XML);
        if (body == null) {
            return Optional.empty();
        }
        Document manifest = Jsoup.parse(body, "", Parser.xmlParser());
        Element type = manifest.selectFirst("typeId");
        Element version = manifest.selectFirst("version");
        if (version == null || (type != null && !"confluence".equalsIgnoreCase(type.text().strip()))) {
            return Optional.empty();
        }
        return Optional.of(version.text().strip());
    }
}
