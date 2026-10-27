package pl.aiwcorpo.mcp.confluence.core.domain;

/**
 * The page (or space) does not exist, the caller may not see it, or it lies outside the space the call
 * named. One exception on purpose: the client gets the same {@code not_found_or_denied} for all of them, so
 * it cannot probe which pages exist.
 */
public class PageNotFoundException extends RuntimeException {

    public PageNotFoundException() {
        super("page not found or not visible", null, false, false);
    }
}
