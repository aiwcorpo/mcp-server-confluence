package pl.aiwcorpo.mcp.confluence.integration;

import org.springframework.stereotype.Component;
import pl.aiwcorpo.mcp.confluence.core.port.out.BackendFeatures;
import pl.aiwcorpo.mcp.platform.capability.BackendCapabilities;

/** Writes follow the capability probe: a tested version has the {@code write} feature, anything else only reads. */
@Component
public class CapabilityBackendFeatures implements BackendFeatures {

    private final BackendCapabilities capabilities;
    private final ConfluenceBackendProperties properties;

    public CapabilityBackendFeatures(BackendCapabilities capabilities, ConfluenceBackendProperties properties) {
        this.capabilities = capabilities;
        this.properties = properties;
    }

    @Override
    public boolean writesAllowed() {
        return capabilities.of(ConfluenceVersionProbe.NAME).supports(ConfluenceVersionProbe.WRITE)
                || properties.allowWritesOnUnknownVersion();
    }
}
