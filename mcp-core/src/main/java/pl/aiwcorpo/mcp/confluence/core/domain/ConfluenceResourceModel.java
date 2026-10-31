package pl.aiwcorpo.mcp.confluence.core.domain;

/** How this server's records map to the resource types of the policy (C7, entity-builder.md). */
public final class ConfluenceResourceModel {

    /** Type of a page: the argument of {@code get_page} and {@code create_page.parentId} and the id of a search hit. */
    public static final String PAGE_TYPE = "Confluence::Page";

    /** Type of a space, the container a search is narrowed to ({@code QueryScope}). */
    public static final String SPACE_TYPE = "Confluence::Space";

    private ConfluenceResourceModel() {
    }
}
