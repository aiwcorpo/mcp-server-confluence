package pl.aiwcorpo.mcp.confluence.core.domain;

/**
 * A comment to add. {@code body} is plain text, escaped into storage format by the adapter.
 *
 * @param page        the page as the policy judged it
 * @param parentId    comment to reply to, or null; it must belong to {@code page}
 */
public record NewComment(PageRecord page, String parentId, String body) {
}
