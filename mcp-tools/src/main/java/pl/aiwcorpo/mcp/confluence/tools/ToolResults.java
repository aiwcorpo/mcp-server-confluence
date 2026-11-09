package pl.aiwcorpo.mcp.confluence.tools;

import java.util.List;
import org.springframework.lang.Nullable;
import pl.aiwcorpo.mcp.confluence.core.domain.Attachment;
import pl.aiwcorpo.mcp.confluence.core.domain.AttachmentText;
import pl.aiwcorpo.mcp.confluence.core.domain.Comment;
import pl.aiwcorpo.mcp.confluence.core.domain.Page;
import pl.aiwcorpo.mcp.confluence.core.domain.PageRestrictions;
import pl.aiwcorpo.mcp.confluence.core.domain.PageSummary;
import pl.aiwcorpo.mcp.confluence.core.domain.PageVersion;
import pl.aiwcorpo.mcp.confluence.core.domain.SearchHit;
import pl.aiwcorpo.mcp.confluence.core.domain.Space;
import pl.aiwcorpo.mcp.confluence.core.domain.VersionDiff;
import pl.aiwcorpo.mcp.platform.resource.ResourceRef;
import pl.aiwcorpo.mcp.platform.text.UntrustedText;

/**
 * What the tools return, field by field. Nothing from Confluence reaches the model outside these records. Text
 * written by Confluence users is an {@link UntrustedText} (CC-072); a field Confluence may leave empty is
 * {@code @Nullable}, otherwise the result schema makes it required. No Jackson annotations on the components:
 * the result schema is built from their names.
 */
public final class ToolResults {

    static final int TITLE_MAX = 255;
    static final int MEDIA_TYPE_MAX = 100;
    static final int EXCERPT_MAX = 500;
    static final int VERSION_MESSAGE_MAX = 500;

    private ToolResults() {
    }

    /** {@code id} is a {@code @ResourceRef}: the starter drops the hits the caller's policy does not allow. */
    public record PageHit(@ResourceRef(type = "Confluence::Page") String id, UntrustedText title, int version, @Nullable String lastUpdated) {

        static PageHit of(PageSummary page) {
            return new PageHit(page.id(), titleText(page.title()), page.version(), page.lastUpdated());
        }
    }

    /** A {@link PageHit} with the text Confluence found around the match; the excerpt is text from a page, so untrusted. */
    public record ContentSearchHit(@ResourceRef(type = "Confluence::Page") String id, UntrustedText title, int version,
                                   @Nullable String lastUpdated, UntrustedText excerpt) {

        static ContentSearchHit of(SearchHit hit) {
            PageSummary page = hit.page();
            return new ContentSearchHit(page.id(), titleText(page.title()), page.version(), page.lastUpdated(),
                    UntrustedText.plain(hit.excerpt(), EXCERPT_MAX));
        }
    }

    /** One page of the space tree; {@code parentId} is null for a root, {@code depth} counts from 0 at the roots. */
    public record TreeItem(@ResourceRef(type = "Confluence::Page") String id, UntrustedText title,
                           @Nullable String parentId, int depth) {

        static TreeItem of(SpacePageTree.Node node) {
            return new TreeItem(node.page().id(), titleText(node.page().title()), node.parentId(), node.depth());
        }
    }

    /** {@code key} is a {@code @ResourceRef}: a space the caller's policy does not allow is dropped from the list. */
    public record SpaceHit(@ResourceRef(type = "Confluence::Space") String key, UntrustedText name) {

        static SpaceHit of(Space space) {
            return new SpaceHit(space.key(), UntrustedText.plain(space.name(), TITLE_MAX));
        }
    }

    /** {@code omittedMacros} sits next to {@code body}: the common {@code UntrustedText} has no such field. */
    public record PageResult(String id, String spaceKey, UntrustedText title, int version,
                             @Nullable String lastUpdated, List<String> ancestorIds, UntrustedText body,
                             List<String> omittedMacros) {

        static PageResult of(Page page) {
            return new PageResult(page.id(), page.spaceKey(), titleText(page.title()), page.version(),
                    page.lastUpdated(), page.ancestorIds(),
                    UntrustedText.plainCut(page.body().text(), page.body().truncated()),
                    page.body().omittedMacros());
        }
    }

    public record SpaceResult(String key, UntrustedText name) {

        static SpaceResult of(Space space) {
            return new SpaceResult(space.key(), UntrustedText.plain(space.name(), TITLE_MAX));
        }
    }

    public record CommentItem(String id, @Nullable String parentId, UntrustedText author, @Nullable String created,
                              String location, UntrustedText body, List<String> omittedMacros) {

        static CommentItem of(Comment comment) {
            return new CommentItem(comment.id(), comment.parentId(), UntrustedText.plain(comment.author(), TITLE_MAX),
                    comment.created(), comment.location(),
                    UntrustedText.plainCut(comment.body().text(), comment.body().truncated()),
                    comment.body().omittedMacros());
        }
    }

    public record VersionItem(int number, UntrustedText author, @Nullable String when, UntrustedText message) {

        static VersionItem of(PageVersion version) {
            return new VersionItem(version.number(), UntrustedText.plain(version.author(), TITLE_MAX), version.when(),
                    UntrustedText.plain(version.message(), VERSION_MESSAGE_MAX));
        }
    }

    /** {@code omitted} holds fixed codes: body_truncated, diff_too_large. */
    public record DiffResult(int fromVersion, int toVersion, UntrustedText diff, boolean identical,
                             List<String> omitted) {

        static DiffResult of(VersionDiff diff) {
            return new DiffResult(diff.fromVersion(), diff.toVersion(), UntrustedText.plainCut(diff.diff(), diff.truncated()),
                    diff.identical(), diff.omitted());
        }
    }

    /** Names of the users and groups an operation is restricted to; empty lists mean it is not restricted on this page. */
    public record RestrictedTo(List<UntrustedText> users, List<UntrustedText> groups, boolean truncated) {

        static RestrictedTo of(PageRestrictions.Subjects subjects) {
            return new RestrictedTo(subjects.users().stream().map(name -> UntrustedText.plain(name, TITLE_MAX)).toList(),
                    subjects.groups().stream().map(name -> UntrustedText.plain(name, TITLE_MAX)).toList(),
                    subjects.truncated());
        }
    }

    public record RestrictionsResult(String pageId, RestrictedTo read, RestrictedTo update) {

        static RestrictionsResult of(String pageId, PageRestrictions restrictions) {
            return new RestrictionsResult(pageId, RestrictedTo.of(restrictions.read()), RestrictedTo.of(restrictions.update()));
        }
    }

    public record LabelItem(UntrustedText name) {
    }

    /** {@code truncated} is true when the page has more labels than the 200 listed. */
    public record LabelsResult(List<LabelItem> labels, boolean truncated) {

        static LabelsResult of(List<String> names, boolean truncated) {
            return new LabelsResult(names.stream().map(name -> new LabelItem(UntrustedText.plain(name, 100))).toList(),
                    truncated);
        }
    }

    public record AttachmentItem(String id, UntrustedText title, UntrustedText mediaType, long fileSize, int version) {

        static AttachmentItem of(Attachment attachment) {
            return new AttachmentItem(attachment.id(), titleText(attachment.title()),
                    UntrustedText.plain(attachment.mediaType(), MEDIA_TYPE_MAX), attachment.fileSize(),
                    attachment.version());
        }
    }

    /** {@code omitted} is empty when {@code content} is the file's text, else one fixed code saying why it is not. */
    public record AttachmentContent(String id, UntrustedText title, UntrustedText mediaType, UntrustedText content,
                                    List<String> omitted) {

        static AttachmentContent of(AttachmentText file) {
            Attachment attachment = file.attachment();
            return new AttachmentContent(attachment.id(), titleText(attachment.title()),
                    UntrustedText.plain(attachment.mediaType(), MEDIA_TYPE_MAX),
                    UntrustedText.plainCut(file.text(), file.truncated()),
                    file.omittedReason() == null ? List.of() : List.of(file.omittedReason()));
        }
    }

    public record WrittenPage(String id, String spaceKey, UntrustedText title, int version) {

        /**
         * A reply without {@code space} must not turn a page that exists into an error: the space asked for is
         * the one the parent was checked against, so it stands in.
         */
        static WrittenPage of(PageSummary page, String requestedSpaceKey) {
            String spaceKey = page.spaceKey() == null ? requestedSpaceKey : page.spaceKey();
            return new WrittenPage(page.id(), spaceKey, titleText(page.title()), page.version());
        }
    }

    public record AddedComment(String id, String pageId) {
    }

    public record AddedLabel(String pageId, String label) {
    }

    public record TrashedPage(String id) {
    }

    private static UntrustedText titleText(String title) {
        return UntrustedText.plain(title == null ? "" : title, TITLE_MAX);
    }
}
