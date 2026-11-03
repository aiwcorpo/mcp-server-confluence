package pl.aiwcorpo.mcp.confluence.core.domain;

/**
 * Replacing the body would drop content that plain text cannot express (macros, tables, links, images), so the page can
 * only be appended to.
 */
public class PageNotEditableException extends RuntimeException {

    public PageNotEditableException() {
        super("page has content that a replace would remove", null, false, false);
    }
}
