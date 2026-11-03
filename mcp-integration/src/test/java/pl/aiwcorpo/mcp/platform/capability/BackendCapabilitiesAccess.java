package pl.aiwcorpo.mcp.platform.capability;

/** The starter lets only its runner store a probe answer; tests of this server need to play that part. */
public final class BackendCapabilitiesAccess {

    private BackendCapabilitiesAccess() {
    }

    public static void store(BackendCapabilities capabilities, String name, Capabilities answer) {
        capabilities.update(name, answer);
    }
}
