package pl.aiwcorpo.mcp.confluence.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.platform.capability.BackendCapabilities;
import pl.aiwcorpo.mcp.platform.capability.BackendCapabilitiesAccess;
import pl.aiwcorpo.mcp.platform.capability.Capabilities;

/** Who may write: the tested range always, the operator's flag only for a version nobody could read. */
class CapabilityBackendFeaturesTest {

    private static ConfluenceBackendProperties properties(boolean flag) {
        return new ConfluenceBackendProperties("https://confluence.internal", null, null, null, null, false, flag);
    }

    private static boolean writes(Capabilities answer, boolean flag) {
        BackendCapabilities capabilities = new BackendCapabilities();
        if (answer != null) {
            BackendCapabilitiesAccess.store(capabilities, ConfluenceVersionProbe.NAME, answer);
        }
        return new CapabilityBackendFeatures(capabilities, properties(flag)).writesAllowed();
    }

    @Test
    void theNotProbedTextIsTheStarters() {
        assertThat(new BackendCapabilities().of(ConfluenceVersionProbe.NAME).detail())
                .isEqualTo(CapabilityBackendFeatures.NOT_PROBED);
    }

    @Test
    void beforeTheFirstProbeAnswerWritesAreClosedEvenWithTheFlag() {
        assertThat(writes(null, false)).isFalse();
        assertThat(writes(null, true)).isFalse();
    }

    @Test
    void aTestedVersionWritesWithoutTheFlag() {
        assertThat(writes(Capabilities.supported("10.2.1", Set.of("read", "write")), false)).isTrue();
    }

    @Test
    void theFlagOpensWritesOnlyWhenTheProbeCouldNotReadAVersion() {
        Capabilities unreadable = Capabilities.unavailable("the reported version is not a version number");
        assertThat(writes(unreadable, false)).isFalse();
        assertThat(writes(unreadable, true)).isTrue();
    }

    @Test
    void aVersionTheProbeReadAndDoesNotKnowStaysReadOnlyWhateverTheFlagSays() {
        assertThat(writes(Capabilities.degraded("11.0.0", Set.of("read"), "newer than the tested 10.x"), true)).isFalse();
        assertThat(writes(Capabilities.degraded("7.19.2", Set.of("read"), "older than 8.5"), true)).isFalse();
    }
}
