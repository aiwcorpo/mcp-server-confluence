package pl.aiwcorpo.mcp.confluence.core.domain;

/**
 * A record of the backend system, reduced to the fields the tools hand out. Anything else the backend
 * returns stays in the adapter: a field the model never sees cannot leak.
 */
public record ConfluenceRecord(String id, String project, String title, String status) {

    public ConfluenceRecord {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
    }
}
