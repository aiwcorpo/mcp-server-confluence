package pl.aiwcorpo.mcp.confluence.core.domain;

/**
 * Confluence runs a version this server was not tested with (or does not answer about its version), so only reads are
 * served. Writes wait for a supported version or for the operator's explicit permission.
 */
public class WritesNotVerifiedException extends RuntimeException {

    public WritesNotVerifiedException() {
        super("writes are not enabled for this Confluence version", null, false, false);
    }
}
