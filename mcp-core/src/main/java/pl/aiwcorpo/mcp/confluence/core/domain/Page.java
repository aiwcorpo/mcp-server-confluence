package pl.aiwcorpo.mcp.confluence.core.domain;

import java.util.List;

/**
 * One page with its body, reduced to the fields {@code get_page} hands out.
 *
 * @param ancestorIds ids of the ancestor pages, root first (Confluence order)
 */
public record Page(String id, String spaceKey, String title, int version, String lastUpdated,
                   List<String> ancestorIds, PageText body) {

    public Page {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        ancestorIds = List.copyOf(ancestorIds);
    }
}
