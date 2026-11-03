package pl.aiwcorpo.mcp.confluence.core.domain;

/** The page is not at the version the caller read: somebody changed it, or the caller's version was never current. */
public class PageConflictException extends RuntimeException {

    public PageConflictException() {
        super("page version differs from the expected one", null, false, false);
    }
}
