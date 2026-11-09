package pl.aiwcorpo.mcp.confluence.application.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import pl.aiwcorpo.mcp.confluence.core.domain.Attachment;
import pl.aiwcorpo.mcp.confluence.core.domain.AttachmentText;
import pl.aiwcorpo.mcp.confluence.core.domain.Comment;
import pl.aiwcorpo.mcp.confluence.core.domain.ContentHit;
import pl.aiwcorpo.mcp.confluence.core.domain.NewComment;
import pl.aiwcorpo.mcp.confluence.core.domain.NewPage;
import pl.aiwcorpo.mcp.confluence.core.domain.Page;
import pl.aiwcorpo.mcp.confluence.core.domain.PageEdit;
import pl.aiwcorpo.mcp.confluence.core.domain.PageNotFoundException;
import pl.aiwcorpo.mcp.confluence.core.domain.PageRecord;
import pl.aiwcorpo.mcp.confluence.core.domain.PageSummary;
import pl.aiwcorpo.mcp.confluence.core.domain.PageVersion;
import pl.aiwcorpo.mcp.confluence.core.domain.SearchHit;
import pl.aiwcorpo.mcp.confluence.core.domain.Slice;
import pl.aiwcorpo.mcp.confluence.core.domain.Space;
import pl.aiwcorpo.mcp.confluence.core.domain.VersionDiff;
import pl.aiwcorpo.mcp.confluence.core.domain.WritesNotVerifiedException;
import pl.aiwcorpo.mcp.confluence.core.port.in.ConfluenceUseCase;
import pl.aiwcorpo.mcp.confluence.core.port.out.BackendFeatures;
import pl.aiwcorpo.mcp.confluence.core.port.out.ConfluenceResourcePort;

/** Use cases of the server. A plain class: mcp-app makes it a bean, so this module stays free of Spring. */
public class ConfluenceService implements ConfluenceUseCase {

    /** Window size of every list; also what Confluence is asked for. */
    static final int PAGE_SIZE = 25;

    /** Window of the page tree: ids and titles only, so larger than the other lists. */
    static final int TREE_WINDOW = 50;

    /** Comments are long, so their window is smaller: 10 comments of at most 4000 characters stay under 50 KB. */
    static final int COMMENT_WINDOW = 10;

    /** Version numbers asked for in one window of the history; each one is a request to Confluence. */
    static final int VERSION_WINDOW = 20;

    /** Longest text, in lines, that is compared; longer bodies are reported instead of diffed. */
    static final int DIFF_MAX_LINES = 2_000;

    /** Size of the diff handed to the model, in characters. */
    static final int DIFF_TEXT_MAX = 50_000;

    /** Text of an attachment handed to the model, in characters. */
    static final int ATTACHMENT_TEXT_MAX = 50_000;

    private final ConfluenceResourcePort resource;
    private final BackendFeatures features;

    public ConfluenceService(ConfluenceResourcePort resource, BackendFeatures features) {
        this.resource = resource;
        this.features = features;
    }

    /**
     * The query is the tool's (built from the scope of the call and structured filters), this method still drops what
     * Confluence returns beyond the named space. Which of the remaining hits the caller may see is the starter's
     * result filter, which judges every hit by its page id.
     */
    @Override
    public Slice<PageSummary> searchPages(String spaceKey, String cql, int start) {
        Slice<PageSummary> found = resource.searchPages(cql, start, PAGE_SIZE);
        return new Slice<>(found.items().stream().filter(hit -> spaceKey.equalsIgnoreCase(hit.spaceKey())).toList(),
                found.nextStart());
    }

    /**
     * The search endpoint does not say where a hit lives, so every hit is looked up by id and kept only when Confluence
     * puts it in the named space right now. A hit that cannot be looked up (not shown to the caller) is dropped.
     */
    @Override
    public Slice<SearchHit> searchContent(String spaceKey, String cql, int start) {
        Slice<ContentHit> found = resource.searchContent(cql, start, PAGE_SIZE);
        if (found.items().isEmpty()) {
            return new Slice<>(List.of(), found.nextStart());
        }
        Map<String, PageRecord> pages = resource.findPageRecords(found.items().stream().map(ContentHit::id).toList());
        List<SearchHit> hits = found.items().stream()
                .filter(hit -> pages.containsKey(hit.id()) && spaceKey.equalsIgnoreCase(pages.get(hit.id()).spaceKey()))
                .map(hit -> new SearchHit(pages.get(hit.id()).summary(), hit.excerpt())).toList();
        return new Slice<>(hits, found.nextStart());
    }

    @Override
    public Slice<PageSummary> listPages(String spaceKey, int start) {
        Slice<PageSummary> found = resource.listPages(spaceKey, start, PAGE_SIZE);
        return new Slice<>(found.items().stream().filter(hit -> spaceKey.equalsIgnoreCase(hit.spaceKey())).toList(),
                found.nextStart());
    }

    @Override
    public Slice<PageRecord> listSpacePages(String spaceKey, int start) {
        Slice<PageRecord> found = resource.listSpacePages(spaceKey, start, TREE_WINDOW);
        return new Slice<>(found.items().stream().filter(page -> spaceKey.equalsIgnoreCase(page.spaceKey())).toList(),
                found.nextStart());
    }

    @Override
    public Slice<PageSummary> listChildPages(PageRecord parent, int start) {
        return resource.listChildPages(parent.id(), start, PAGE_SIZE);
    }

    @Override
    public Slice<Space> listSpaces(int start) {
        return resource.listSpaces(start, PAGE_SIZE);
    }

    @Override
    public Optional<PageRecord> findPageByTitle(String spaceKey, String title) {
        return resource.findPageRecordByTitle(spaceKey, title).filter(page -> spaceKey.equalsIgnoreCase(page.spaceKey()));
    }

    /**
     * The policy was evaluated on {@code judged}; the body is read afterwards, so the page is compared with what
     * was judged. A page moved to another space or tree in between is not returned: the decision would not apply to it.
     */
    @Override
    public Page getPage(PageRecord judged) {
        return resource.findPage(judged.id()).filter(page -> samePlace(judged, page.spaceKey(), page.ancestorIds()))
                .orElseThrow(PageNotFoundException::new);
    }

    @Override
    public Optional<Page> getPageVersion(PageRecord judged, int version) {
        return resource.findPageVersion(judged.id(), version)
                .map(page -> {
                    // Confluence does not report the ancestors a stored version had, so only the space is compared.
                    if (!judged.spaceKey().equalsIgnoreCase(page.spaceKey())) {
                        throw new PageNotFoundException();
                    }
                    return page;
                });
    }

    /**
     * Confluence's public API has no version list, so the numbers below the current one are asked for one by one
     * (at most {@link #VERSION_WINDOW} per call). A number Confluence does not show (a pruned version) is skipped.
     */
    @Override
    public Slice<PageVersion> listPageVersions(PageRecord page, int start) {
        int top = start <= 0 ? page.version() : Math.min(start, page.version());
        int bottom = Math.max(1, top - VERSION_WINDOW + 1);
        List<PageVersion> versions = new java.util.ArrayList<>();
        for (int number = top; number >= bottom; number--) {
            resource.findPageVersionInfo(page.id(), number, number == page.version()).ifPresent(versions::add);
        }
        return new Slice<>(versions, bottom > 1 ? bottom - 1 : null);
    }

    @Override
    public Optional<VersionDiff> diffPageVersions(PageRecord judged, int fromVersion, int toVersion) {
        Optional<Page> from = pageAtVersion(judged, fromVersion);
        if (from.isEmpty()) {
            return Optional.empty();
        }
        Optional<Page> to = pageAtVersion(judged, toVersion);
        if (to.isEmpty()) {
            return Optional.empty();
        }
        List<String> omitted = new java.util.ArrayList<>();
        if (from.get().body().truncated() || to.get().body().truncated()) {
            omitted.add("body_truncated");
        }
        List<String> before = TextDiff.lines(from.get().body().text());
        List<String> after = TextDiff.lines(to.get().body().text());
        if (before.size() > DIFF_MAX_LINES || after.size() > DIFF_MAX_LINES) {
            omitted.add("diff_too_large");
            return Optional.of(new VersionDiff(fromVersion, toVersion, "", false, false, omitted));
        }
        String diff;
        try {
            diff = TextDiff.unified(before, after, "version " + fromVersion, "version " + toVersion);
        } catch (TextDiff.TooLargeException tooLarge) {
            omitted.add("diff_too_large");
            return Optional.of(new VersionDiff(fromVersion, toVersion, "", false, false, omitted));
        }
        boolean cut = diff.length() > DIFF_TEXT_MAX;
        return Optional.of(new VersionDiff(fromVersion, toVersion, cut ? diff.substring(0, DIFF_TEXT_MAX) : diff,
                diff.isEmpty(), cut, omitted));
    }

    /**
     * The version judged as current is read as the page itself, because Confluence serves only earlier ones as
     * historical; if the page was edited since, that number is historical by now and is read as such.
     */
    private Optional<Page> pageAtVersion(PageRecord judged, int version) {
        if (version > judged.version()) {
            return Optional.empty();
        }
        if (version == judged.version()) {
            Optional<Page> current = resource.findPage(judged.id())
                    .filter(page -> samePlace(judged, page.spaceKey(), page.ancestorIds()));
            if (current.isPresent() && current.get().version() == version) {
                return current;
            }
        }
        return getPageVersion(judged, version);
    }

    @Override
    public Slice<Comment> listComments(PageRecord page, int start) {
        Slice<Comment> found = resource.listComments(page.id(), start, COMMENT_WINDOW);
        return new Slice<>(found.items().stream().filter(comment -> page.id().equals(comment.containerId())).toList(),
                found.nextStart());
    }

    @Override
    public Slice<String> listLabels(PageRecord page) {
        return resource.listLabels(page.id());
    }

    @Override
    public Slice<Attachment> listAttachments(PageRecord page, int start) {
        return resource.listAttachments(page.id(), start, PAGE_SIZE);
    }

    @Override
    public Optional<AttachmentText> readAttachment(PageRecord page, String attachmentId) {
        return resource.readAttachment(page.id(), attachmentId, ATTACHMENT_TEXT_MAX);
    }

    /**
     * Confluence would place the page wherever the parent lives, so a parent from another space would turn a write the
     * policy allowed for one space into a write in another. The parent is the record the policy judged, not a second
     * lookup, and the space sent is the canonical key Confluence reported for it (the argument may differ in case).
     * Space keys are compared ignoring case because the decisions are taken on the key Confluence reports.
     */
    @Override
    public PageSummary createPage(String spaceKey, PageRecord parent, String title, String body) {
        requireWrites();
        if (!spaceKey.equalsIgnoreCase(parent.spaceKey())) {
            throw new PageNotFoundException();
        }
        return resource.createPage(new NewPage(parent.spaceKey(), parent.id(), parent.ancestorIds(), title, body));
    }

    @Override
    public PageSummary updatePage(PageEdit edit) {
        requireWrites();
        return resource.updatePage(edit);
    }

    /** A reply goes under a comment that belongs to this very page; a comment id of another page is "not found". */
    @Override
    public Comment addComment(PageRecord page, String parentCommentId, String body) {
        requireWrites();
        if (parentCommentId != null) {
            resource.findComment(parentCommentId).filter(parent -> page.id().equals(parent.containerId()))
                    .orElseThrow(PageNotFoundException::new);
        }
        return resource.addComment(new NewComment(page, parentCommentId, body));
    }

    @Override
    public void addLabel(PageRecord page, String label) {
        requireWrites();
        resource.addLabel(page.id(), label);
    }

    @Override
    public void trashPage(PageRecord judged) {
        requireWrites();
        resource.findPageRecord(judged.id()).filter(page -> samePlace(judged, page.spaceKey(), page.ancestorIds()))
                .orElseThrow(PageNotFoundException::new);
        resource.trashPage(judged.id());
    }

    private void requireWrites() {
        if (!features.writesAllowed()) {
            throw new WritesNotVerifiedException();
        }
    }

    private static boolean samePlace(PageRecord judged, String spaceKey, List<String> ancestorIds) {
        return judged.spaceKey().equalsIgnoreCase(spaceKey) && judged.ancestorIds().equals(ancestorIds);
    }
}
