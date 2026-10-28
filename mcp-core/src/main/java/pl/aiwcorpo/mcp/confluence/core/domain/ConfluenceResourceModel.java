package pl.aiwcorpo.mcp.confluence.core.domain;

import java.util.Collection;

/** How this server's records map to the resource types of the policy (C7, entity-builder.md). */
public final class ConfluenceResourceModel {

    /** Type of a page: the argument of {@code get_page} and {@code create_page.parentId} and the id of a search hit. */
    public static final String PAGE_TYPE = "Confluence::Page";

    /** Type of a space, the one a virtual server link narrows ({@code QueryScope}). */
    public static final String SPACE_TYPE = "Confluence::Space";

    private ConfluenceResourceModel() {
    }

    /** Whether a search in {@code spaceKey} stays inside the spaces the link narrows to. */
    public static boolean withinLink(Collection<String> linkSpaceKeys, String spaceKey) {
        return linkSpaceKeys.contains(spaceKey);
    }
}
