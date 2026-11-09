package pl.aiwcorpo.mcp.confluence.core.port.out;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import pl.aiwcorpo.mcp.confluence.core.domain.Attachment;
import pl.aiwcorpo.mcp.confluence.core.domain.AttachmentText;
import pl.aiwcorpo.mcp.confluence.core.domain.Comment;
import pl.aiwcorpo.mcp.confluence.core.domain.NewComment;
import pl.aiwcorpo.mcp.confluence.core.domain.NewPage;
import pl.aiwcorpo.mcp.confluence.core.domain.Page;
import pl.aiwcorpo.mcp.confluence.core.domain.PageEdit;
import pl.aiwcorpo.mcp.confluence.core.domain.PageRecord;
import pl.aiwcorpo.mcp.confluence.core.domain.PageSummary;
import pl.aiwcorpo.mcp.confluence.core.domain.PageVersion;
import pl.aiwcorpo.mcp.confluence.core.domain.Slice;
import pl.aiwcorpo.mcp.confluence.core.domain.Space;

/**
 * Outbound port to Confluence, implemented in mcp-integration. Every call runs as the calling user. Methods that read
 * something by id return empty (or an empty slice) when Confluence does not show it to the caller, whether it exists
 * or not; writes throw {@link pl.aiwcorpo.mcp.confluence.core.domain.PageNotFoundException} in that case.
 */
public interface ConfluenceResourcePort {

    /** Current pages matching {@code cql}, a query the server built (never text from the model). */
    Slice<PageSummary> searchPages(String cql, int start, int limit);

    /** Current pages of one space in Confluence's own order. */
    Slice<PageSummary> listPages(String spaceKey, int start, int limit);

    /** Direct children (pages) of a page. */
    Slice<PageSummary> listChildPages(String pageId, int start, int limit);

    /** Global spaces the caller can see, with their labels. */
    Slice<Space> listSpaces(int start, int limit);

    /** The page without its body, with the labels of its space. */
    Optional<PageRecord> findPageRecord(String pageId);

    /** Like {@link #findPageRecord} for many pages with one query; pages that are not shown are left out of the map. */
    Map<String, PageRecord> findPageRecords(Collection<String> pageIds);

    /** The current page of a space with exactly this title. */
    Optional<PageRecord> findPageRecordByTitle(String spaceKey, String title);

    /** The space with its labels. */
    Optional<Space> findSpace(String spaceKey);

    /** Like {@link #findSpace} for many spaces with one query. */
    Map<String, Space> findSpaces(Collection<String> spaceKeys);

    Optional<Page> findPage(String pageId);

    /** An earlier version of a page with its body; empty when the version does not exist. */
    Optional<Page> findPageVersion(String pageId, int version);

    /**
     * Author, time and comment of one stored version, without its body. The public REST API has no version list, so the
     * caller asks for the numbers one by one. {@code current} says that {@code version} is the page's current one, which
     * Confluence does not serve as a historical version. Empty when that version does not exist or is not shown.
     */
    Optional<PageVersion> findPageVersionInfo(String pageId, int version, boolean current);

    Slice<Comment> listComments(String pageId, int start, int limit);

    Optional<Comment> findComment(String commentId);

    /** @throws pl.aiwcorpo.mcp.confluence.core.domain.PageRejectedException Confluence refused the comment (400) */
    Comment addComment(NewComment comment);

    /** Global labels, at most 200; {@code nextStart} is non-null when the page has more. */
    Slice<String> listLabels(String pageId);

    /** Adding a label the page already has is not an error. */
    void addLabel(String pageId, String label);

    Slice<Attachment> listAttachments(String pageId, int start, int limit);

    /** Empty when the attachment does not exist on that page. */
    Optional<AttachmentText> readAttachment(String pageId, String attachmentId, int maxChars);

    /**
     * @throws pl.aiwcorpo.mcp.confluence.core.domain.PageNotFoundException  space or parent not visible, or no
     *         permission to add pages
     * @throws pl.aiwcorpo.mcp.confluence.core.domain.PageRejectedException Confluence refused the page (400)
     */
    PageSummary createPage(NewPage page);

    /**
     * @throws pl.aiwcorpo.mcp.confluence.core.domain.PageConflictException    the stored version is not the expected one
     * @throws pl.aiwcorpo.mcp.confluence.core.domain.PageNotEditableException a replace would drop rich content
     * @throws pl.aiwcorpo.mcp.confluence.core.domain.PageNotFoundException    the page is not visible, was moved
     *         out of the judged place, or the caller may not edit it
     */
    PageSummary updatePage(PageEdit edit);

    /** Moves the page to the trash of its space (Confluence's delete; the page can be restored by a space admin). */
    void trashPage(String pageId);
}
