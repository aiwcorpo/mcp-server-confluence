package pl.aiwcorpo.mcp.confluence.core.port.in;

import java.util.List;
import java.util.Optional;
import pl.aiwcorpo.mcp.confluence.core.domain.Attachment;
import pl.aiwcorpo.mcp.confluence.core.domain.AttachmentText;
import pl.aiwcorpo.mcp.confluence.core.domain.Comment;
import pl.aiwcorpo.mcp.confluence.core.domain.Page;
import pl.aiwcorpo.mcp.confluence.core.domain.PageEdit;
import pl.aiwcorpo.mcp.confluence.core.domain.PageRecord;
import pl.aiwcorpo.mcp.confluence.core.domain.PageSummary;
import pl.aiwcorpo.mcp.confluence.core.domain.SearchHit;
import pl.aiwcorpo.mcp.confluence.core.domain.PageVersion;
import pl.aiwcorpo.mcp.confluence.core.domain.Slice;
import pl.aiwcorpo.mcp.confluence.core.domain.Space;
import pl.aiwcorpo.mcp.confluence.core.domain.VersionDiff;

/**
 * Inbound port: what the server can do, in business terms. Implemented in mcp-application, called by mcp-tools. Records
 * named {@code judged} or {@code page} are what the policy was evaluated on; use cases compare the stored state with
 * them, so a page that moved to another space or tree in between is not served or changed.
 */
public interface ConfluenceUseCase {

    /**
     * Pages of {@code spaceKey} that the server-built {@code cql} finds; the space is mandatory, there is no search
     * across spaces. Hits from other spaces are dropped from the window.
     */
    Slice<PageSummary> searchPages(String spaceKey, String cql, int start);

    /**
     * Pages of {@code spaceKey} found by a confined query written by the caller ({@code CqlConfinement}), each with its
     * excerpt. A hit is kept only when a lookup by id puts the page in {@code spaceKey} now, whatever the index says;
     * which of those the caller may see is the starter's result filter.
     */
    Slice<SearchHit> searchContent(String spaceKey, String cql, int start);

    Slice<PageSummary> listPages(String spaceKey, int start);

    Slice<PageSummary> listChildPages(PageRecord parent, int start);

    Slice<Space> listSpaces(int start);

    /** @return the page of that title in the space; the caller must still have it judged by the policy */
    Optional<PageRecord> findPageByTitle(String spaceKey, String title);

    /**
     * The page with its body.
     *
     * @throws pl.aiwcorpo.mcp.confluence.core.domain.PageNotFoundException when the page is not visible any more or
     *         differs from {@code judged}
     */
    Page getPage(PageRecord judged);

    /** A stored version of the page; empty when that version does not exist. */
    Optional<Page> getPageVersion(PageRecord judged, int version);

    /**
     * Versions of the page, newest first, one window at a time.
     *
     * @param start the version number to continue from, or 0 for the newest; {@code nextStart} of the slice is the
     *              number to pass next
     */
    Slice<PageVersion> listPageVersions(PageRecord page, int start);

    /**
     * Unified line diff between the plain-text bodies of two versions, read the way {@code get_page} reads a body.
     *
     * @return empty when one of the versions does not exist
     */
    Optional<VersionDiff> diffPageVersions(PageRecord judged, int fromVersion, int toVersion);

    Slice<Comment> listComments(PageRecord page, int start);

    Slice<String> listLabels(PageRecord page);

    Slice<Attachment> listAttachments(PageRecord page, int start);

    /** Text of an attachment, or the reason there is none; empty when the page has no such attachment. */
    Optional<AttachmentText> readAttachment(PageRecord page, String attachmentId);

    /**
     * @param parent the parent page as the policy judged it
     * @throws pl.aiwcorpo.mcp.confluence.core.domain.PageNotFoundException when {@code parent} is not a page of
     *         {@code spaceKey}, or Confluence refuses to show the space
     * @throws pl.aiwcorpo.mcp.confluence.core.domain.PageRejectedException when Confluence refuses the page
     * @throws pl.aiwcorpo.mcp.confluence.core.domain.WritesNotVerifiedException on a version not enabled for writes
     */
    PageSummary createPage(String spaceKey, PageRecord parent, String title, String body);

    /** See {@link pl.aiwcorpo.mcp.confluence.core.port.out.ConfluenceResourcePort#updatePage}. */
    PageSummary updatePage(PageEdit edit);

    /**
     * @param parentCommentId a comment of the same page to reply to, or null
     * @throws pl.aiwcorpo.mcp.confluence.core.domain.PageNotFoundException when the parent comment is not on the page
     */
    Comment addComment(PageRecord page, String parentCommentId, String body);

    void addLabel(PageRecord page, String label);

    void trashPage(PageRecord judged);
}
