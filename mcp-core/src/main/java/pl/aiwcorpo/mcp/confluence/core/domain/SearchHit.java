package pl.aiwcorpo.mcp.confluence.core.domain;

/** A page found by a search, as it is now, with the text Confluence shows around the match. */
public record SearchHit(PageSummary page, String excerpt) {
}
