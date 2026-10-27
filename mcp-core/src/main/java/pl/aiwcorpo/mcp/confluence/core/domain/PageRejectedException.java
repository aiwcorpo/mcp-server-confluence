package pl.aiwcorpo.mcp.confluence.core.domain;

/**
 * Confluence refused to create the page with 400, most often because the space already has a page with that
 * title. Carries no backend text: the response body never leaves the adapter.
 */
public class PageRejectedException extends RuntimeException {

    public PageRejectedException() {
        super("page rejected by confluence", null, false, false);
    }
}
