package pl.aiwcorpo.mcp.confluence.core.domain;

/** Metadata of an attachment; the file itself is not part of it. */
public record Attachment(String id, String title, String mediaType, long fileSize, int version) {

    public Attachment {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
    }
}
