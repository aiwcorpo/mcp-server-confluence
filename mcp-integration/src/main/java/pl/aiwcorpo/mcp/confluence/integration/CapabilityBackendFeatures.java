package pl.aiwcorpo.mcp.confluence.integration;

import org.springframework.stereotype.Component;
import pl.aiwcorpo.mcp.confluence.core.port.out.BackendFeatures;
import pl.aiwcorpo.mcp.platform.capability.BackendCapabilities;
import pl.aiwcorpo.mcp.platform.capability.Capabilities;

/**
 * Writes follow the capability probe: a tested version has the {@code write} feature, anything else only reads. The
 * operator's flag opens writes in one case only: the probe has answered and could not read a version (Confluence hides
 * it from anonymous callers). A version the probe read and does not know (older than 8.5, 11.x and newer) stays read-only
 * whatever the flag says, and so does the time before the probe's first answer.
 */
@Component
public class CapabilityBackendFeatures implements BackendFeatures {

    /** The detail the starter gives a backend nobody has probed yet; a test pins it to the starter's text. */
    static final String NOT_PROBED = "not probed yet";

    private final BackendCapabilities capabilities;
    private final ConfluenceBackendProperties properties;

    public CapabilityBackendFeatures(BackendCapabilities capabilities, ConfluenceBackendProperties properties) {
        this.capabilities = capabilities;
        this.properties = properties;
    }

    @Override
    public boolean writesAllowed() {
        Capabilities now = capabilities.of(ConfluenceVersionProbe.NAME);
        if (now.supports(ConfluenceVersionProbe.WRITE)) {
            return true;
        }
        return properties.allowWritesOnUnknownVersion() && now.state() == Capabilities.State.UNAVAILABLE
                && !NOT_PROBED.equals(now.detail());
    }
}
