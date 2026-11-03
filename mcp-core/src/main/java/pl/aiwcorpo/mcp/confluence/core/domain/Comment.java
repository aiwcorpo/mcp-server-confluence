package pl.aiwcorpo.mcp.confluence.core.domain;

/**
 * A comment of a page.
 *
 * @param containerId id of the page the comment belongs to, as Confluence reports it
 * @param parentId    id of the comment this one replies to, null for a top-level comment
 * @param author      display name of the author of the current version, may be empty
 * @param location    "footer" or "inline"
 */
public record Comment(String id, String containerId, String parentId, String author, String created,
                      String location, PageText body) {

    public Comment {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
    }
}
