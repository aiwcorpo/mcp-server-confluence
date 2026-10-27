package pl.aiwcorpo.mcp.confluence.core.domain;

/**
 * A page as listed by search and returned after creation.
 *
 * @param lastUpdated time of the current version as Confluence reports it (ISO 8601 with offset)
 */
public record PageSummary(String id, String spaceKey, String title, int version, String lastUpdated) {

    public PageSummary {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
    }
}
