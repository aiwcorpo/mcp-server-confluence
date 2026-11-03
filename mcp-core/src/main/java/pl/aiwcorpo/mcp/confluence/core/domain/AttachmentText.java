package pl.aiwcorpo.mcp.confluence.core.domain;

/**
 * The text of a text attachment.
 *
 * @param omittedReason null when {@code text} is the content; otherwise a fixed snake case code
 *                      ({@code binary_attachment}, {@code attachment_too_large}) saying why there is no text
 */
public record AttachmentText(Attachment attachment, String text, boolean truncated, String omittedReason) {

    public AttachmentText {
        text = text == null ? "" : text;
    }
}
