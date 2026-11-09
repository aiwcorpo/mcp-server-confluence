package pl.aiwcorpo.mcp.confluence.core.domain;

/**
 * A hit of Confluence's search as the search endpoint reports it: the id and the text around the match, nothing that
 * says where the page lives. The use case looks the page up by id before it keeps the hit.
 *
 * @param excerpt plain text without highlight markers, may be empty
 */
public record ContentHit(String id, String excerpt) {

    public ContentHit {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        excerpt = excerpt == null ? "" : excerpt;
    }
}
