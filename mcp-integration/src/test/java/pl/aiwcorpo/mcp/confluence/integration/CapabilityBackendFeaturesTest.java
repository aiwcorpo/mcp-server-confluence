package pl.aiwcorpo.mcp.confluence.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.platform.capability.BackendCapabilities;

class CapabilityBackendFeaturesTest {

    private static ConfluenceBackendProperties properties(boolean writesOnUnknownVersion) {
        return new ConfluenceBackendProperties("https://confluence.internal", null, null, null, null, false,
                writesOnUnknownVersion);
    }

    @Test
    void beforeTheFirstProbeAnswerWritesAreOff() {
        assertThat(new CapabilityBackendFeatures(new BackendCapabilities(), properties(false)).writesAllowed()).isFalse();
    }

    @Test
    void theOperatorCanSwitchWritesOnForAnUnverifiedRelease() {
        assertThat(new CapabilityBackendFeatures(new BackendCapabilities(), properties(true)).writesAllowed()).isTrue();
    }
}
