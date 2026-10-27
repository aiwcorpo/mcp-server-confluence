package pl.aiwcorpo.mcp.confluence.core.domain;

/**
 * A page to create. {@code body} is plain text; the adapter turns it into storage format with every markup
 * character escaped, so no macro or HTML can be smuggled in.
 */
public record NewPage(String spaceKey, String parentId, String title, String body) {
}
