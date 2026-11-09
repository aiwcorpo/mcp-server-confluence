package pl.aiwcorpo.mcp.confluence.core.domain;

/** Too many diffs are being computed at once; the call can be repeated in a moment. */
public class DiffBusyException extends RuntimeException {

    public DiffBusyException() {
        super("diff busy", null, false, false);
    }
}
