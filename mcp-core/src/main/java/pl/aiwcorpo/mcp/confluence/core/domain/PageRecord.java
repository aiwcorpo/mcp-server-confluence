package pl.aiwcorpo.mcp.confluence.core.domain;

import java.util.List;

/**
 * A page as the policy needs to see it, without the body: what the resolver fetches once per call and what the
 * hits of a search are judged by.
 *
 * @param ancestorIds ids of the ancestor pages, root first (Confluence order); they decide which page-tree grants apply
 * @param spaceLabels labels of the page's space, or null when Confluence did not report them; the policy refuses a
 *                    page whose space labels are unknown, so a failed lookup never reads as "not classified"
 */
public record PageRecord(String id, String spaceKey, String title, int version, String lastUpdated,
                         List<String> ancestorIds, List<String> spaceLabels) {

    public PageRecord {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        ancestorIds = List.copyOf(ancestorIds);
        spaceLabels = spaceLabels == null ? null : List.copyOf(spaceLabels);
    }

    public PageSummary summary() {
        return new PageSummary(id, spaceKey, title, version, lastUpdated);
    }
}
