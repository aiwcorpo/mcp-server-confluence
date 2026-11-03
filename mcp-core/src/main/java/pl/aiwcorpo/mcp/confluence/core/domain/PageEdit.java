package pl.aiwcorpo.mcp.confluence.core.domain;

/**
 * A change to an existing page, made against a version the caller has seen.
 *
 * @param judged          the page as the policy judged it; the adapter compares the stored page with it
 * @param expectedVersion version the caller read; the edit is applied only on top of exactly this version
 * @param title           new title, or null to keep the current one
 * @param mode            how {@code body} meets the existing content
 * @param body            plain text, escaped into storage format
 */
public record PageEdit(PageRecord judged, int expectedVersion, String title, Mode mode, String body) {

    /** {@code APPEND} keeps the existing storage untouched; {@code REPLACE} is allowed only for pages of plain paragraphs. */
    public enum Mode { APPEND, REPLACE }
}
