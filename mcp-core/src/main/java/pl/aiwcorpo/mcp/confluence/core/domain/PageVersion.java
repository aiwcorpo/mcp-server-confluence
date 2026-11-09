package pl.aiwcorpo.mcp.confluence.core.domain;

/**
 * One entry of a page's version history.
 *
 * @param author  display name of the person who saved the version, may be empty
 * @param when    time of the version as Confluence reports it (ISO 8601 with offset)
 * @param message the version comment the author typed, may be empty
 */
public record PageVersion(int number, String author, String when, String message) {
}
