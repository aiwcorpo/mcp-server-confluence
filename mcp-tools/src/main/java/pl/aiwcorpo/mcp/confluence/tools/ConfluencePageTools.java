package pl.aiwcorpo.mcp.confluence.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import pl.aiwcorpo.mcp.confluence.core.domain.Comment;
import pl.aiwcorpo.mcp.confluence.core.domain.ConfluenceIds;
import pl.aiwcorpo.mcp.confluence.core.domain.ConfluenceResourceModel;
import pl.aiwcorpo.mcp.confluence.core.domain.PageEdit;
import pl.aiwcorpo.mcp.confluence.core.domain.PageNotFoundException;
import pl.aiwcorpo.mcp.confluence.core.domain.PageRecord;
import pl.aiwcorpo.mcp.confluence.core.domain.PageSummary;
import pl.aiwcorpo.mcp.confluence.core.domain.PageVersion;
import pl.aiwcorpo.mcp.confluence.core.domain.SearchHit;
import pl.aiwcorpo.mcp.confluence.core.domain.Slice;
import pl.aiwcorpo.mcp.confluence.core.domain.Space;
import pl.aiwcorpo.mcp.confluence.core.port.in.ConfluenceUseCase;
import pl.aiwcorpo.mcp.confluence.tools.ToolResults.AddedComment;
import pl.aiwcorpo.mcp.confluence.tools.ToolResults.AddedLabel;
import pl.aiwcorpo.mcp.confluence.tools.ToolResults.AttachmentContent;
import pl.aiwcorpo.mcp.confluence.tools.ToolResults.AttachmentItem;
import pl.aiwcorpo.mcp.confluence.tools.ToolResults.CommentItem;
import pl.aiwcorpo.mcp.confluence.tools.ToolResults.ContentSearchHit;
import pl.aiwcorpo.mcp.confluence.tools.ToolResults.DiffResult;
import pl.aiwcorpo.mcp.confluence.tools.ToolResults.LabelsResult;
import pl.aiwcorpo.mcp.confluence.tools.ToolResults.MovedPage;
import pl.aiwcorpo.mcp.confluence.tools.ToolResults.PageHit;
import pl.aiwcorpo.mcp.confluence.tools.ToolResults.PageResult;
import pl.aiwcorpo.mcp.confluence.tools.ToolResults.RestrictionsResult;
import pl.aiwcorpo.mcp.confluence.tools.ToolResults.SpaceHit;
import pl.aiwcorpo.mcp.confluence.tools.ToolResults.SpaceResult;
import pl.aiwcorpo.mcp.confluence.tools.ToolResults.TrashedPage;
import pl.aiwcorpo.mcp.confluence.tools.ToolResults.TreeItem;
import pl.aiwcorpo.mcp.confluence.tools.ToolResults.VersionItem;
import pl.aiwcorpo.mcp.confluence.tools.ToolResults.WrittenPage;
import pl.aiwcorpo.mcp.platform.error.InvalidToolArgumentException;
import pl.aiwcorpo.mcp.platform.guard.RiskLevel;
import pl.aiwcorpo.mcp.platform.guard.ToolRisk;
import pl.aiwcorpo.mcp.platform.guard.ToolTitle;
import pl.aiwcorpo.mcp.platform.input.ArgumentLimits;
import pl.aiwcorpo.mcp.platform.paging.CursorCodec;
import pl.aiwcorpo.mcp.platform.paging.PageFiller;
import pl.aiwcorpo.mcp.platform.paging.PagedResult;
import pl.aiwcorpo.mcp.platform.resource.QueryScope;
import pl.aiwcorpo.mcp.platform.resource.ResourceContext;
import pl.aiwcorpo.mcp.platform.resource.ResourcePolicy;
import pl.aiwcorpo.mcp.platform.resource.ResourceRef;
import pl.aiwcorpo.mcp.platform.resource.StructuredQuery.Direction;
import pl.aiwcorpo.mcp.platform.resource.StructuredQuery.Operator;

/**
 * The MCP tools of this server. Each public {@code @Tool} method is a capability an AI client can call
 * through the gateway, so every argument is untrusted input from a model.
 *
 * <p>The gateway decides who may call a tool and asks for confirmations; the starter verifies that the call came from
 * the gateway for exactly this tool and evaluates the policy (C7) on the pages and spaces the tool names. What stays
 * here: a risk flag and a title on every tool, strict argument limits, every page or space named by an explicit
 * argument marked {@link ResourceRef}, a search whose CQL the server builds from structured filters, lists that
 * return a signed cursor instead of a total, and writes that are safe to repeat where Confluence allows it.
 */
@Service
public class ConfluencePageTools {

    static final int SPACE_KEY_MAX = 255;
    static final int PAGE_ID_MAX = 19;
    static final int SEARCH_TEXT_MAX = 200;
    static final int TITLE_MAX = 255;
    static final int BODY_MAX = 20_000;
    static final int COMMENT_MAX = 10_000;
    static final int PAGE_SIZE = 25;
    static final int TREE_MAX_PAGES = 200;
    static final int TREE_MAX_READS = 10;
    static final int TREE_DEFAULT_DEPTH = 5;
    static final int TREE_MAX_DEPTH = 10;

    /** Single line with at least one non-space character. */
    static final String NON_BLANK_LINE = "^(?=.*\\S)[^\\u0000-\\u001f\\u007f]+$";

    /** Labels as Confluence stores them: lower case, no spaces; the server accepts the ASCII subset. */
    static final String LABEL = "^[a-z0-9][a-z0-9_.-]{0,99}$";
    static final String ATTACHMENT_ID = "^att[0-9]{1,18}$";
    static final String SEARCH_ORDER = "^(?:lastmodified|created|title)$";
    static final String EDIT_MODE = "^(?:append|replace)$";

    private static final String SEARCH_TOOL = "search_pages";
    private static final String CONTENT_SEARCH_TOOL = "search_content";

    private final ConfluenceUseCase useCase;
    private final ResourcePolicy policy;
    private final CursorCodec cursors;

    public ConfluencePageTools(ConfluenceUseCase useCase, ResourcePolicy policy, CursorCodec cursors) {
        this.useCase = useCase;
        this.policy = policy;
        this.cursors = cursors;
    }

    // ---- search and lists ---------------------------------------------------------------------------------------

    /**
     * The model names the space and the words, never a query: the server builds the CQL. The space is a
     * {@code @ResourceRef}, so a missing, hidden or restricted space is {@code not_found_or_denied} before
     * anything is searched. The requested space goes into the query as the narrowing of the scope ({@link QueryScope}); the hits are
     * filtered afterwards by their {@code @ResourceRef} component, which removes what Confluence returns beyond the space
     * and pages that are now somewhere the policy refuses.
     */
    @Tool(name = SEARCH_TOOL, description = "Searches the current pages of one Confluence space. Filters: words in "
            + "title and content (text), words in the title (title), an exact label (label); at least one is "
            + "required. Returns about 25 pages per call, each with id, title, version and last update time, and "
            + "nextCursor when more follow. Characters other than letters, digits, spaces, '.', '_' and apostrophes "
            + "in text and title are treated as spaces.")
    @ToolRisk(RiskLevel.READ_ONLY)
    @ToolTitle("Search pages")
    public PagedResult<PageHit> searchPages(
            @ToolParam(description = "Key of the space to search, letters and digits only")
            @ArgumentLimits(minLength = 1, maxLength = SPACE_KEY_MAX, resourceType = ConfluenceResourceModel.SPACE_TYPE)
            @ResourceRef(type = ConfluenceResourceModel.SPACE_TYPE) String space,
            @ToolParam(required = false, description = "Words to look for in page titles and content, at most 200 characters")
            @ArgumentLimits(minLength = 1, maxLength = SEARCH_TEXT_MAX, pattern = NON_BLANK_LINE) @Nullable String text,
            @ToolParam(required = false, description = "Words to look for in page titles only, at most 200 characters")
            @ArgumentLimits(minLength = 1, maxLength = SEARCH_TEXT_MAX, pattern = NON_BLANK_LINE) @Nullable String title,
            @ToolParam(required = false, description = "Exact label of the page: lower case letters, digits, '_', '.', '-'")
            @ArgumentLimits(minLength = 1, maxLength = 100, pattern = LABEL) @Nullable String label,
            @ToolParam(required = false, description = "Order of the hits: lastmodified, created or title; "
                    + "without it, by relevance")
            @ArgumentLimits(minLength = 1, maxLength = 12, pattern = SEARCH_ORDER) @Nullable String orderBy,
            @ToolParam(required = false, description = "nextCursor of the previous call with the same arguments")
            @ArgumentLimits(maxLength = CursorCodec.MAX_CURSOR_LENGTH, pattern = CursorCodec.ARGUMENT_PATTERN) @Nullable String cursor) {
        String spaceKey = judgedSpace(validSpace(space)).key();
        String words = searchWords("text", text);
        String titleWords = searchWords("title", title);
        if (words == null && titleWords == null && label == null) {
            throw new InvalidToolArgumentException("at least one of text, title and label is required");
        }
        String fingerprint = CursorCodec.fingerprint(spaceKey, words, titleWords, label, orderBy);
        String position = positionOf(cursor, SEARCH_TOOL, fingerprint);
        QueryScope scope = policy.queryScope(ConfluenceResourceModel.SPACE_TYPE).narrowedTo(List.of(spaceKey));
        var query = scope.cql(Set.of("text", "title", "label"), Set.of("created", "lastmodified", "title"))
                .where("text", Operator.CONTAINS, words)
                .where("title", Operator.CONTAINS, titleWords)
                .where("label", Operator.EQUALS, label);
        if (orderBy != null) {
            query.orderBy(orderBy, "title".equals(orderBy) ? Direction.ASC : Direction.DESC);
        }
        String cql = query.build();
        return filledPage(position, SEARCH_TOOL, fingerprint, ConfluenceResourceModel.PAGE_TYPE, PageSummary::id,
                start -> useCase.searchPages(spaceKey, cql, start), PageHit::of);
    }

    /**
     * The one tool that takes query text from the model. The caller's CQL is confined three times: the space is a
     * {@code @ResourceRef} the policy judged; the text is scanned and wrapped by {@link CqlConfinement}, so the server's
     * space clause and {@code type = page} are ANDed outside it; and every hit is looked up by id and kept only if
     * Confluence puts it in that space now and the policy allows the page ({@code filledPage}).
     */
    @Tool(name = CONTENT_SEARCH_TOOL, description = "Searches the current pages of one Confluence space with a CQL "
            + "condition written by the caller, for example: text ~ \"release\" AND label = \"handbook\". The space "
            + "and type = page are added by the server, so the query cannot reach other spaces or other content; "
            + "fields such as space and type in the condition only narrow it further. The condition is at most 500 "
            + "characters; outside quoted strings only letters, digits, spaces and _ . , = ! ~ < > ( ) are accepted, "
            + "and the only ORDER BY allowed is a trailing one on created, lastmodified or title. Returns about 25 "
            + "pages per call, each with id, title, version, last update time and an excerpt of the text around the "
            + "match, and nextCursor when more follow.")
    @ToolRisk(RiskLevel.READ_ONLY)
    @ToolTitle("Search content with CQL")
    public PagedResult<ContentSearchHit> searchContent(
            @ToolParam(description = "Key of the space to search, letters and digits only")
            @ArgumentLimits(minLength = 1, maxLength = SPACE_KEY_MAX, resourceType = ConfluenceResourceModel.SPACE_TYPE)
            @ResourceRef(type = ConfluenceResourceModel.SPACE_TYPE) String space,
            @ToolParam(description = "CQL condition, at most 500 characters, one line")
            @ArgumentLimits(minLength = 1, maxLength = CqlConfinement.MAX_LENGTH, pattern = NON_BLANK_LINE) String cql,
            @ToolParam(required = false, description = "nextCursor of the previous call with the same arguments")
            @ArgumentLimits(maxLength = CursorCodec.MAX_CURSOR_LENGTH, pattern = CursorCodec.ARGUMENT_PATTERN) @Nullable String cursor) {
        String spaceKey = judgedSpace(validSpace(space)).key();
        QueryScope scope = policy.queryScope(ConfluenceResourceModel.SPACE_TYPE).narrowedTo(List.of(spaceKey));
        String confined = CqlConfinement.confine(cql, scope.cqlSpaceClause().orElseThrow());
        String fingerprint = CursorCodec.fingerprint(spaceKey, confined);
        String position = positionOf(cursor, CONTENT_SEARCH_TOOL, fingerprint);
        return filledPage(position, CONTENT_SEARCH_TOOL, fingerprint, ConfluenceResourceModel.PAGE_TYPE,
                (SearchHit hit) -> hit.page().id(), start -> useCase.searchContent(spaceKey, confined, start), ContentSearchHit::of);
    }

    @Tool(name = "list_pages", description = "Lists the current pages of one Confluence space in Confluence's own "
            + "order, about 25 per call, each with id, title, version and last update time, and nextCursor when more follow.")
    @ToolRisk(RiskLevel.READ_ONLY)
    @ToolTitle("List pages")
    public PagedResult<PageHit> listPages(
            @ToolParam(description = "Key of the space, letters and digits only")
            @ArgumentLimits(minLength = 1, maxLength = SPACE_KEY_MAX, resourceType = ConfluenceResourceModel.SPACE_TYPE)
            @ResourceRef(type = ConfluenceResourceModel.SPACE_TYPE) String space,
            @ToolParam(required = false, description = "nextCursor of the previous call with the same arguments")
            @ArgumentLimits(maxLength = CursorCodec.MAX_CURSOR_LENGTH, pattern = CursorCodec.ARGUMENT_PATTERN) @Nullable String cursor) {
        String spaceKey = judgedSpace(validSpace(space)).key();
        String fingerprint = CursorCodec.fingerprint(spaceKey);
        String position = positionOf(cursor, "list_pages", fingerprint);
        return filledPage(position, "list_pages", fingerprint, ConfluenceResourceModel.PAGE_TYPE, PageSummary::id,
                start -> useCase.listPages(spaceKey, start), PageHit::of);
    }

    @Tool(name = "list_child_pages", description = "Lists the direct child pages of a Confluence page, about 25 per "
            + "call, each with id, title, version and last update time, and nextCursor when more follow.")
    @ToolRisk(RiskLevel.READ_ONLY)
    @ToolTitle("List child pages")
    public PagedResult<PageHit> listChildPages(
            @ToolParam(description = "Id of the parent page, a positive decimal number")
            @ArgumentLimits(minLength = 1, maxLength = PAGE_ID_MAX, resourceType = ConfluenceResourceModel.PAGE_TYPE)
            @ResourceRef(type = ConfluenceResourceModel.PAGE_TYPE) String pageId,
            @ToolParam(required = false, description = "nextCursor of the previous call with the same arguments")
            @ArgumentLimits(maxLength = CursorCodec.MAX_CURSOR_LENGTH, pattern = CursorCodec.ARGUMENT_PATTERN) @Nullable String cursor) {
        PageRecord parent = judgedPage(pageId);
        String fingerprint = CursorCodec.fingerprint(parent.id());
        String position = positionOf(cursor, "list_child_pages", fingerprint);
        return filledPage(position, "list_child_pages", fingerprint, ConfluenceResourceModel.PAGE_TYPE, PageSummary::id,
                start -> useCase.listChildPages(parent, start), PageHit::of);
    }

    /**
     * Not paged: the tree is built from a flat list, so a cursor would split it in the middle of a subtree. The read is
     * bounded instead (at most 10 windows of 50 pages, 200 pages returned) and says when it stopped short.
     */
    @Tool(name = "get_space_page_tree", description = "Returns the page tree of one Confluence space as a flat list in "
            + "tree order (a parent, then its children): id, title, parent id (null for a top-level page) and depth "
            + "(0 for top-level pages). At most 200 pages and maxDepth levels (default 5, at most 10) are returned; "
            + "truncated is true and omitted holds page_limit or depth_limit when pages were left out. A page that is "
            + "not shown to the caller is not listed and a page below it is then shown as a top-level page.")
    @ToolRisk(RiskLevel.READ_ONLY)
    @ToolTitle("Get space page tree")
    public PagedResult<TreeItem> getSpacePageTree(
            @ToolParam(description = "Key of the space, letters and digits only")
            @ArgumentLimits(minLength = 1, maxLength = SPACE_KEY_MAX, resourceType = ConfluenceResourceModel.SPACE_TYPE)
            @ResourceRef(type = ConfluenceResourceModel.SPACE_TYPE) String space,
            @ToolParam(required = false, description = "Number of levels to return, 1 to 10; 1 lists only top-level pages")
            @ArgumentLimits(minimum = 1, maximum = TREE_MAX_DEPTH) @Nullable Integer maxDepth) {
        String spaceKey = judgedSpace(validSpace(space)).key();
        int depth = maxDepth == null ? TREE_DEFAULT_DEPTH : maxDepth;
        if (depth < 1 || depth > TREE_MAX_DEPTH) {
            throw new InvalidToolArgumentException("maxDepth must be 1-" + TREE_MAX_DEPTH);
        }
        List<PageRecord> allowed = new ArrayList<>();
        Integer next = 0;
        int reads = 0;
        while (next != null && reads < TREE_MAX_READS && allowed.size() <= TREE_MAX_PAGES) {
            Slice<PageRecord> window = useCase.listSpacePages(spaceKey, next);
            reads++;
            allowed.addAll(policy.retainAllowedById(ConfluenceResourceModel.PAGE_TYPE, window.items(), PageRecord::id));
            next = window.nextStart();
        }
        SpacePageTree.Result tree = SpacePageTree.build(allowed, depth, TREE_MAX_PAGES);
        boolean pageLimit = next != null || tree.countCut();
        List<String> omitted = new ArrayList<>();
        if (pageLimit) {
            omitted.add("page_limit");
        }
        if (tree.depthCut()) {
            omitted.add("depth_limit");
        }
        return new PagedResult<>(tree.nodes().stream().map(TreeItem::of).toList(), null, pageLimit || tree.depthCut(),
                omitted);
    }

    @Tool(name = "list_spaces", description = "Lists the global Confluence spaces the user can see, about 25 per call, "
            + "each with key and name, and nextCursor when more follow. Personal spaces are not listed.")
    @ToolRisk(RiskLevel.READ_ONLY)
    @ToolTitle("List spaces")
    public PagedResult<SpaceHit> listSpaces(
            @ToolParam(required = false, description = "nextCursor of the previous call")
            @ArgumentLimits(maxLength = CursorCodec.MAX_CURSOR_LENGTH, pattern = CursorCodec.ARGUMENT_PATTERN) @Nullable String cursor) {
        String fingerprint = CursorCodec.fingerprint("spaces");
        String position = positionOf(cursor, "list_spaces", fingerprint);
        return filledPage(position, "list_spaces", fingerprint, ConfluenceResourceModel.SPACE_TYPE, Space::key,
                useCase::listSpaces, SpaceHit::of);
    }

    // ---- reads --------------------------------------------------------------------------------------------------

    /**
     * {@code @ResourceRef} makes the starter resolve the page (with the caller's credential) and evaluate the policy
     * before this method runs: a page that is missing, hidden, in a personal space or in a space labelled restricted
     * never gets here, and the client sees the same {@code not_found_or_denied} for all of them.
     */
    @Tool(name = "get_page", description = "Returns one Confluence page: id, space key, title, version, last "
            + "update time, ids of the ancestor pages and the body as plain text in body.text. The body is read "
            + "from the page's own storage format: include, excerpt-include and every other macro outside a fixed "
            + "list (code, noformat, info, note, warning, tip, panel, expand, excerpt) are not expanded and are "
            + "listed in omittedMacros. The body is cut at 50000 characters (body.truncated).")
    @ToolRisk(RiskLevel.READ_ONLY)
    @ToolTitle("Get page")
    public PageResult getPage(
            @ToolParam(description = "Page id, a positive decimal number")
            @ArgumentLimits(minLength = 1, maxLength = PAGE_ID_MAX, resourceType = ConfluenceResourceModel.PAGE_TYPE)
            @ResourceRef(type = ConfluenceResourceModel.PAGE_TYPE) String pageId) {
        return PageResult.of(useCase.getPage(judgedPage(pageId)));
    }

    /**
     * The space is judged by the starter; the page found by the title is judged here, by the same resolver and the same
     * policy as a page named by id, so a page in a restricted tree is {@code not_found_or_denied} like a missing one.
     */
    @Tool(name = "get_page_by_title", description = "Returns the current page with exactly this title in one "
            + "Confluence space, in the same form as get_page.")
    @ToolRisk(RiskLevel.READ_ONLY)
    @ToolTitle("Get page by title")
    public PageResult getPageByTitle(
            @ToolParam(description = "Key of the space, letters and digits only")
            @ArgumentLimits(minLength = 1, maxLength = SPACE_KEY_MAX, resourceType = ConfluenceResourceModel.SPACE_TYPE)
            @ResourceRef(type = ConfluenceResourceModel.SPACE_TYPE) String space,
            @ToolParam(description = "Exact page title, one line, at most 255 characters")
            @ArgumentLimits(minLength = 1, maxLength = TITLE_MAX, pattern = NON_BLANK_LINE) String title) {
        String spaceKey = judgedSpace(validSpace(space)).key();
        String pageTitle = Arguments.require("title", title, 1, TITLE_MAX, NON_BLANK_LINE, "1-255 characters on one line");
        PageRecord found = useCase.findPageByTitle(spaceKey, pageTitle).orElseThrow(PageNotFoundException::new);
        if (policy.retainAllowedById(ConfluenceResourceModel.PAGE_TYPE, List.of(found), PageRecord::id).isEmpty()) {
            throw new PageNotFoundException();
        }
        return PageResult.of(useCase.getPage(found));
    }

    @Tool(name = "get_space", description = "Returns one Confluence space: its key and name.")
    @ToolRisk(RiskLevel.READ_ONLY)
    @ToolTitle("Get space")
    public SpaceResult getSpace(
            @ToolParam(description = "Key of the space, letters and digits only")
            @ArgumentLimits(minLength = 1, maxLength = SPACE_KEY_MAX, resourceType = ConfluenceResourceModel.SPACE_TYPE)
            @ResourceRef(type = ConfluenceResourceModel.SPACE_TYPE) String space) {
        return SpaceResult.of(judgedSpace(space));
    }

    @Tool(name = "get_page_version", description = "Returns an earlier stored version of a Confluence page in the "
            + "form of get_page (the version field is the requested one). A version that does not exist is "
            + "not_found_or_denied.")
    @ToolRisk(RiskLevel.READ_ONLY)
    @ToolTitle("Get page version")
    public PageResult getPageVersion(
            @ToolParam(description = "Page id, a positive decimal number")
            @ArgumentLimits(minLength = 1, maxLength = PAGE_ID_MAX, resourceType = ConfluenceResourceModel.PAGE_TYPE)
            @ResourceRef(type = ConfluenceResourceModel.PAGE_TYPE) String pageId,
            @ToolParam(description = "Version number, 1 or more")
            @ArgumentLimits(minimum = 1, maximum = 1_000_000) int version) {
        return useCase.getPageVersion(judgedPage(pageId), version).map(PageResult::of)
                .orElseThrow(PageNotFoundException::new);
    }

    @Tool(name = "list_page_versions", description = "Lists the stored versions of a Confluence page, newest first, "
            + "up to 20 per call: version number, author display name, time and the version comment, and nextCursor "
            + "when older versions follow. A version the instance has pruned is left out.")
    @ToolRisk(RiskLevel.READ_ONLY)
    @ToolTitle("List page versions")
    public PagedResult<VersionItem> listPageVersions(
            @ToolParam(description = "Page id, a positive decimal number")
            @ArgumentLimits(minLength = 1, maxLength = PAGE_ID_MAX, resourceType = ConfluenceResourceModel.PAGE_TYPE)
            @ResourceRef(type = ConfluenceResourceModel.PAGE_TYPE) String pageId,
            @ToolParam(required = false, description = "nextCursor of the previous call with the same arguments")
            @ArgumentLimits(maxLength = CursorCodec.MAX_CURSOR_LENGTH, pattern = CursorCodec.ARGUMENT_PATTERN) @Nullable String cursor) {
        PageRecord page = judgedPage(pageId);
        String fingerprint = CursorCodec.fingerprint(page.id());
        int start = startOf(cursor, "list_page_versions", fingerprint);
        Slice<PageVersion> versions = useCase.listPageVersions(page, start);
        return PagedResult.of(versions.items().stream().map(VersionItem::of).toList(),
                nextCursor(versions.nextStart(), "list_page_versions", fingerprint));
    }

    @Tool(name = "diff_page_versions", description = "Returns a unified line diff between the plain-text bodies of "
            + "two versions of a Confluence page, read the way get_page reads a body (macros are not expanded), with "
            + "3 lines of context. The diff is cut at 50000 characters (diff.truncated). Bodies of more than 2000 "
            + "lines are not compared: omitted then holds diff_too_large. A version that does not exist is "
            + "not_found_or_denied.")
    @ToolRisk(RiskLevel.READ_ONLY)
    @ToolTitle("Diff page versions")
    public DiffResult diffPageVersions(
            @ToolParam(description = "Page id, a positive decimal number")
            @ArgumentLimits(minLength = 1, maxLength = PAGE_ID_MAX, resourceType = ConfluenceResourceModel.PAGE_TYPE)
            @ResourceRef(type = ConfluenceResourceModel.PAGE_TYPE) String pageId,
            @ToolParam(description = "Older version number, 1 or more")
            @ArgumentLimits(minimum = 1, maximum = 1_000_000) int fromVersion,
            @ToolParam(description = "Newer version number, 1 or more")
            @ArgumentLimits(minimum = 1, maximum = 1_000_000) int toVersion) {
        return useCase.diffPageVersions(judgedPage(pageId), fromVersion, toVersion).map(DiffResult::of)
                .orElseThrow(PageNotFoundException::new);
    }

    @Tool(name = "list_comments", description = "Lists the comments of a Confluence page, replies included, up to 10 "
            + "per call: id, id of the parent comment, author, creation time, location (footer or inline) and the "
            + "text of each, and nextCursor when more follow. Macros in comments are not expanded.")
    @ToolRisk(RiskLevel.READ_ONLY)
    @ToolTitle("List comments")
    public PagedResult<CommentItem> listComments(
            @ToolParam(description = "Page id, a positive decimal number")
            @ArgumentLimits(minLength = 1, maxLength = PAGE_ID_MAX, resourceType = ConfluenceResourceModel.PAGE_TYPE)
            @ResourceRef(type = ConfluenceResourceModel.PAGE_TYPE) String pageId,
            @ToolParam(required = false, description = "nextCursor of the previous call with the same arguments")
            @ArgumentLimits(maxLength = CursorCodec.MAX_CURSOR_LENGTH, pattern = CursorCodec.ARGUMENT_PATTERN) @Nullable String cursor) {
        PageRecord page = judgedPage(pageId);
        String fingerprint = CursorCodec.fingerprint(page.id());
        int start = startOf(cursor, "list_comments", fingerprint);
        Slice<Comment> comments = useCase.listComments(page, start);
        List<CommentItem> items = comments.items().stream().map(CommentItem::of).toList();
        boolean macros = items.stream().anyMatch(item -> !item.omittedMacros().isEmpty());
        return new PagedResult<>(items, nextCursor(comments.nextStart(), "list_comments", fingerprint), false,
                macros ? List.of("unsupported_macro") : List.of());
    }

    @Tool(name = "get_page_restrictions", description = "Returns who the view (read) and the edit (update) of a "
            + "Confluence page are restricted to on that page: user display names and group names, at most 100 of each "
            + "per operation (truncated is true when there are more). Empty lists mean no restriction on this page, unless "
            + "omitted holds restrictions_not_reported, which means Confluence did not say; restrictions of its ancestor pages also apply and are not included. E-mail addresses are not returned.")
    @ToolRisk(RiskLevel.READ_ONLY)
    @ToolTitle("Get page restrictions")
    public RestrictionsResult getPageRestrictions(
            @ToolParam(description = "Page id, a positive decimal number")
            @ArgumentLimits(minLength = 1, maxLength = PAGE_ID_MAX, resourceType = ConfluenceResourceModel.PAGE_TYPE)
            @ResourceRef(type = ConfluenceResourceModel.PAGE_TYPE) String pageId) {
        PageRecord page = judgedPage(pageId);
        return RestrictionsResult.of(page.id(), useCase.getPageRestrictions(page));
    }

    @Tool(name = "list_labels", description = "Lists the labels of a Confluence page (global labels only), at most 200; "
            + "truncated is true when the page has more.")
    @ToolRisk(RiskLevel.READ_ONLY)
    @ToolTitle("List labels")
    public LabelsResult listLabels(
            @ToolParam(description = "Page id, a positive decimal number")
            @ArgumentLimits(minLength = 1, maxLength = PAGE_ID_MAX, resourceType = ConfluenceResourceModel.PAGE_TYPE)
            @ResourceRef(type = ConfluenceResourceModel.PAGE_TYPE) String pageId) {
        Slice<String> labels = useCase.listLabels(judgedPage(pageId));
        return LabelsResult.of(labels.items(), labels.nextStart() != null);
    }

    @Tool(name = "list_attachments", description = "Lists the attachments of a Confluence page, about 25 per call: "
            + "id, file name, media type, size in bytes and version, and nextCursor when more follow. File contents "
            + "are not part of the list.")
    @ToolRisk(RiskLevel.READ_ONLY)
    @ToolTitle("List attachments")
    public PagedResult<AttachmentItem> listAttachments(
            @ToolParam(description = "Page id, a positive decimal number")
            @ArgumentLimits(minLength = 1, maxLength = PAGE_ID_MAX, resourceType = ConfluenceResourceModel.PAGE_TYPE)
            @ResourceRef(type = ConfluenceResourceModel.PAGE_TYPE) String pageId,
            @ToolParam(required = false, description = "nextCursor of the previous call with the same arguments")
            @ArgumentLimits(maxLength = CursorCodec.MAX_CURSOR_LENGTH, pattern = CursorCodec.ARGUMENT_PATTERN) @Nullable String cursor) {
        PageRecord page = judgedPage(pageId);
        String fingerprint = CursorCodec.fingerprint(page.id());
        int start = startOf(cursor, "list_attachments", fingerprint);
        var attachments = useCase.listAttachments(page, start);
        return PagedResult.of(attachments.items().stream().map(AttachmentItem::of).toList(),
                nextCursor(attachments.nextStart(), "list_attachments", fingerprint));
    }

    @Tool(name = "read_attachment", description = "Returns the text of a text attachment (text/*, JSON or XML, at most "
            + "200000 bytes) of a Confluence page, cut at 50000 characters (content.truncated). Other files are not "
            + "returned: omitted holds binary_attachment or attachment_too_large and content is empty.")
    @ToolRisk(RiskLevel.READ_ONLY)
    @ToolTitle("Read attachment")
    public AttachmentContent readAttachment(
            @ToolParam(description = "Page id, a positive decimal number")
            @ArgumentLimits(minLength = 1, maxLength = PAGE_ID_MAX, resourceType = ConfluenceResourceModel.PAGE_TYPE)
            @ResourceRef(type = ConfluenceResourceModel.PAGE_TYPE) String pageId,
            @ToolParam(description = "Attachment id from list_attachments, att followed by digits")
            @ArgumentLimits(minLength = 4, maxLength = 21, pattern = ATTACHMENT_ID) String attachmentId) {
        String id = Arguments.require("attachmentId", attachmentId, 4, 21, ATTACHMENT_ID, "att followed by digits");
        return useCase.readAttachment(judgedPage(pageId), id).map(AttachmentContent::of)
                .orElseThrow(PageNotFoundException::new);
    }

    // ---- writes -------------------------------------------------------------------------------------------------

    /**
     * The policy judges the space and the parent page with the action {@code update}, so the user needs a write grant
     * on the space. The {@code space} argument is also compared with the space of the parent the policy evaluated, so the
     * page cannot land in another space than the one granted. A repeated call is refused by Confluence (the title is
     * taken), so it does not create a duplicate.
     */
    @Tool(name = "create_page", description = "Creates a Confluence page in a space, under an existing parent page "
            + "of the same space. The body is plain text: blank lines separate paragraphs, line breaks are kept, "
            + "and markup characters are escaped, so macros and HTML are not supported. Returns id, space key, "
            + "title and version of the new page. A repeated call fails because the title already exists.")
    @ToolRisk(RiskLevel.UPDATE)
    @ToolTitle("Create page")
    public WrittenPage createPage(
            @ToolParam(description = "Key of the space, letters and digits only")
            @ArgumentLimits(minLength = 1, maxLength = SPACE_KEY_MAX, resourceType = ConfluenceResourceModel.SPACE_TYPE)
            @ResourceRef(type = ConfluenceResourceModel.SPACE_TYPE) String space,
            @ToolParam(description = "Id of the parent page in that space, a positive decimal number")
            @ArgumentLimits(minLength = 1, maxLength = PAGE_ID_MAX, resourceType = ConfluenceResourceModel.PAGE_TYPE)
            @ResourceRef(type = ConfluenceResourceModel.PAGE_TYPE) String parentId,
            @ToolParam(description = "Page title, one line, at most 255 characters")
            @ArgumentLimits(minLength = 1, maxLength = TITLE_MAX, pattern = NON_BLANK_LINE) String title,
            @ToolParam(description = "Page body as plain text, at most 20000 characters")
            @ArgumentLimits(minLength = 1, maxLength = BODY_MAX, pattern = Arguments.MULTI_LINE) String body) {
        String spaceKey = judgedSpace(validSpace(space)).key();
        String pageTitle = Arguments.require("title", title, 1, TITLE_MAX, NON_BLANK_LINE,
                "1-255 characters on one line");
        String pageBody = Arguments.require("body", body, 1, BODY_MAX, Arguments.MULTI_LINE,
                "1-20000 characters without control characters other than tab and line breaks");
        return WrittenPage.of(useCase.createPage(spaceKey, judgedPage(parentId), pageTitle.strip(), pageBody), spaceKey);
    }

    /**
     * Optimistic lock: the edit applies only on top of the version the caller read, so a repeat of the same call after
     * a lost answer fails with a conflict instead of writing twice. {@code append} keeps everything on the page; {@code replace}
     * is refused for a page with rich content, which plain text cannot reproduce.
     */
    @Tool(name = "update_page", description = "Changes a Confluence page that the caller read at expectedVersion. "
            + "Mode append adds the body as new paragraphs after the existing content and keeps everything else; "
            + "mode replace replaces the content and works only for a page made of plain paragraphs (otherwise it "
            + "fails and append remains). The body is plain text, escaped, without macros or HTML. If the page has "
            + "another version by now the call fails and changes nothing. Returns id, space key, title and the new "
            + "version.")
    @ToolRisk(RiskLevel.UPDATE)
    @ToolTitle("Update page")
    public WrittenPage updatePage(
            @ToolParam(description = "Page id, a positive decimal number")
            @ArgumentLimits(minLength = 1, maxLength = PAGE_ID_MAX, resourceType = ConfluenceResourceModel.PAGE_TYPE)
            @ResourceRef(type = ConfluenceResourceModel.PAGE_TYPE) String pageId,
            @ToolParam(description = "Version number of the page as last read (version field of get_page)")
            @ArgumentLimits(minimum = 1, maximum = 1_000_000) int expectedVersion,
            @ToolParam(description = "append or replace")
            @ArgumentLimits(minLength = 6, maxLength = 7, pattern = EDIT_MODE) String mode,
            @ToolParam(description = "Text to write, at most 20000 characters")
            @ArgumentLimits(minLength = 1, maxLength = BODY_MAX, pattern = Arguments.MULTI_LINE) String body,
            @ToolParam(required = false, description = "New title, one line, at most 255 characters; omit to keep it")
            @ArgumentLimits(minLength = 1, maxLength = TITLE_MAX, pattern = NON_BLANK_LINE) @Nullable String title) {
        PageEdit.Mode editMode = "replace".equals(Arguments.require("mode", mode, 6, 7, EDIT_MODE, "append or replace"))
                ? PageEdit.Mode.REPLACE : PageEdit.Mode.APPEND;
        String text = Arguments.require("body", body, 1, BODY_MAX, Arguments.MULTI_LINE,
                "1-20000 characters without control characters other than tab and line breaks");
        String newTitle = title == null ? null : Arguments.require("title", title, 1, TITLE_MAX, NON_BLANK_LINE,
                "1-255 characters on one line").strip();
        PageRecord judged = judgedPage(pageId);
        return WrittenPage.of(useCase.updatePage(new PageEdit(judged, expectedVersion, newTitle, editMode, text)),
                judged.spaceKey());
    }

    @Tool(name = "add_comment", description = "Adds a footer comment to a Confluence page, or a reply to one of its "
            + "comments. The text is plain text, escaped. A repeated call adds the comment again.")
    @ToolRisk(RiskLevel.UPDATE)
    @ToolTitle("Add comment")
    public AddedComment addComment(
            @ToolParam(description = "Page id, a positive decimal number")
            @ArgumentLimits(minLength = 1, maxLength = PAGE_ID_MAX, resourceType = ConfluenceResourceModel.PAGE_TYPE)
            @ResourceRef(type = ConfluenceResourceModel.PAGE_TYPE) String pageId,
            @ToolParam(description = "Comment text, at most 10000 characters")
            @ArgumentLimits(minLength = 1, maxLength = COMMENT_MAX, pattern = Arguments.MULTI_LINE) String body,
            @ToolParam(required = false, description = "Id of a comment of the same page to reply to")
            @ArgumentLimits(minLength = 1, maxLength = PAGE_ID_MAX, pattern = ConfluenceIds.PAGE_ID) @Nullable String parentCommentId) {
        String text = Arguments.require("body", body, 1, COMMENT_MAX, Arguments.MULTI_LINE,
                "1-10000 characters without control characters other than tab and line breaks");
        if (parentCommentId != null && !ConfluenceIds.isPageId(parentCommentId)) {
            throw new InvalidToolArgumentException("parentCommentId must be a positive decimal number");
        }
        PageRecord page = judgedPage(pageId);
        return new AddedComment(useCase.addComment(page, parentCommentId, text).id(), page.id());
    }

    @Tool(name = "reply_to_comment", description = "Adds a reply to an existing comment of a Confluence page. The "
            + "comment has to belong to that page. The text is plain text, escaped. A repeated call adds the reply again.")
    @ToolRisk(RiskLevel.UPDATE)
    @ToolTitle("Reply to comment")
    public AddedComment replyToComment(
            @ToolParam(description = "Page id, a positive decimal number")
            @ArgumentLimits(minLength = 1, maxLength = PAGE_ID_MAX, resourceType = ConfluenceResourceModel.PAGE_TYPE)
            @ResourceRef(type = ConfluenceResourceModel.PAGE_TYPE) String pageId,
            @ToolParam(description = "Id of the comment to reply to, from list_comments")
            @ArgumentLimits(minLength = 1, maxLength = PAGE_ID_MAX, pattern = ConfluenceIds.PAGE_ID) String commentId,
            @ToolParam(description = "Reply text, at most 10000 characters")
            @ArgumentLimits(minLength = 1, maxLength = COMMENT_MAX, pattern = Arguments.MULTI_LINE) String body) {
        String text = Arguments.require("body", body, 1, COMMENT_MAX, Arguments.MULTI_LINE,
                "1-10000 characters without control characters other than tab and line breaks");
        if (!ConfluenceIds.isPageId(commentId)) {
            throw new InvalidToolArgumentException("commentId must be a positive decimal number");
        }
        PageRecord page = judgedPage(pageId);
        return new AddedComment(useCase.addComment(page, commentId, text).id(), page.id());
    }

    /**
     * The policy judges the page and the new parent with the action {@code update} (both are {@code @ResourceRef}).
     * What the policy cannot see is the place the page leaves: that is the old parent page, or the space for a top-level
     * page, and the caller needs the update right there too, so a grant on a subtree does not let a page out of it.
     */
    @Tool(name = "move_page", description = "Moves a Confluence page, with its child pages, under another page of the "
            + "same space. Moving to another space is not supported. A page cannot be moved under itself or one of its "
            + "own descendants. A page that is already under that parent is left as it is. The caller needs the "
            + "write grant on the page, on its current parent (on the space for a top-level page) and on the new "
            + "parent. Returns id, space key, title, new version and the id of the new parent.")
    @ToolRisk(RiskLevel.UPDATE)
    @ToolTitle("Move page")
    public MovedPage movePage(
            @ToolParam(description = "Id of the page to move, a positive decimal number")
            @ArgumentLimits(minLength = 1, maxLength = PAGE_ID_MAX, resourceType = ConfluenceResourceModel.PAGE_TYPE)
            @ResourceRef(type = ConfluenceResourceModel.PAGE_TYPE) String pageId,
            @ToolParam(description = "Id of the new parent page in the same space, a positive decimal number")
            @ArgumentLimits(minLength = 1, maxLength = PAGE_ID_MAX, resourceType = ConfluenceResourceModel.PAGE_TYPE)
            @ResourceRef(type = ConfluenceResourceModel.PAGE_TYPE) String targetParentId) {
        PageRecord page = judgedPage(pageId);
        PageRecord target = judgedPage(targetParentId);
        if (!page.spaceKey().equalsIgnoreCase(target.spaceKey())) {
            throw new InvalidToolArgumentException("targetParentId must be a page of the same space as pageId");
        }
        if (page.id().equals(target.id()) || target.ancestorIds().contains(page.id())) {
            throw new InvalidToolArgumentException("a page cannot be moved under itself or one of its descendants");
        }
        requireUpdateOnCurrentParent(page);
        return MovedPage.of(useCase.movePage(page, target), target);
    }

    @Tool(name = "add_label", description = "Adds a global label to a Confluence page. A label the page already has "
            + "is left as it is, so a repeated call changes nothing.")
    @ToolRisk(RiskLevel.UPDATE)
    @ToolTitle("Add label")
    public AddedLabel addLabel(
            @ToolParam(description = "Page id, a positive decimal number")
            @ArgumentLimits(minLength = 1, maxLength = PAGE_ID_MAX, resourceType = ConfluenceResourceModel.PAGE_TYPE)
            @ResourceRef(type = ConfluenceResourceModel.PAGE_TYPE) String pageId,
            @ToolParam(description = "Label: lower case letters, digits, '_', '.', '-', at most 100 characters")
            @ArgumentLimits(minLength = 1, maxLength = 100, pattern = LABEL) String label) {
        String name = Arguments.require("label", label, 1, 100, LABEL, "lower case letters, digits, '_', '.' or '-'");
        PageRecord page = judgedPage(pageId);
        useCase.addLabel(page, name);
        return new AddedLabel(page.id(), name);
    }

    /** DESTRUCTIVE: the gateway asks the user for a TOTP code before the call reaches this server. */
    @Tool(name = "delete_page", description = "Moves a Confluence page to the trash of its space. Child pages are "
            + "not deleted. A space administrator can restore the page from the trash.")
    @ToolRisk(RiskLevel.DESTRUCTIVE)
    @ToolTitle("Delete page")
    public TrashedPage deletePage(
            @ToolParam(description = "Page id, a positive decimal number")
            @ArgumentLimits(minLength = 1, maxLength = PAGE_ID_MAX, resourceType = ConfluenceResourceModel.PAGE_TYPE)
            @ResourceRef(type = ConfluenceResourceModel.PAGE_TYPE) String pageId) {
        PageRecord page = judgedPage(pageId);
        useCase.trashPage(page);
        return new TrashedPage(page.id());
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** The page the resolver fetched for the policy: no second lookup, and the answer judged is the answer used. */
    private static PageRecord judgedPage(String pageId) {
        return ResourceContext.current().orElseThrow()
                .fetched(ConfluenceResourceModel.PAGE_TYPE, pageId, PageRecord.class).orElseThrow();
    }

    /** The space as Confluence reports it: its key is the canonical one even when the argument differs in case. */
    private static Space judgedSpace(String space) {
        return ResourceContext.current().orElseThrow()
                .fetched(ConfluenceResourceModel.SPACE_TYPE, space, Space.class).orElseThrow();
    }

    /** The place a page leaves is judged like the place it goes to; a refusal looks like a missing page. */
    private void requireUpdateOnCurrentParent(PageRecord page) {
        boolean allowed = page.ancestorIds().isEmpty()
                ? !policy.retainAllowedById(ConfluenceResourceModel.SPACE_TYPE, List.of(page.spaceKey()), key -> key).isEmpty()
                : !policy.retainAllowedById(ConfluenceResourceModel.PAGE_TYPE, List.of(page.ancestorIds().getLast()), id -> id)
                        .isEmpty();
        if (!allowed) {
            throw new PageNotFoundException();
        }
    }

    private static String validSpace(String space) {
        return Arguments.require("space", space, 1, SPACE_KEY_MAX, ConfluenceIds.SPACE_KEY,
                "a space key: 1-255 letters or digits");
    }

    /** Cleaned search words, or null when the argument is absent; text without a letter or digit is refused. */
    private static String searchWords(String name, @Nullable String value) {
        if (value == null) {
            return null;
        }
        String words = Arguments.searchWords(Arguments.require(name, value, 1, SEARCH_TEXT_MAX, NON_BLANK_LINE,
                "1-200 characters on one line"));
        if (words.codePoints().noneMatch(Character::isLetterOrDigit)) {
            throw new InvalidToolArgumentException(name + " must contain at least one letter or digit");
        }
        return words;
    }

    /** Most reads of Confluence to fill one page after the policy thinned it out. */
    private static final int MAX_FILL_READS = 5;

    /**
     * One page of a list whose elements the policy may hide. A short page with a cursor, or a run of empty ones, would tell
     * the caller that something in the window was hidden, so further windows are read until the page is full or the list
     * ends (README of the starter, "Large results: pages, cursor, `truncated` and `omitted`"). The elements are judged here with the resolver of their type; the
     * starter's result filter then finds the same answers cached.
     */
    private <S, R> PagedResult<R> filledPage(@Nullable String position, String tool, String fingerprint,
            String resourceType, java.util.function.Function<S, String> idOf,
            java.util.function.IntFunction<Slice<S>> read, java.util.function.Function<S, R> shape) {
        PageFiller.Filled<S> filled = PageFiller.fill(position, PAGE_SIZE, MAX_FILL_READS,
                at -> {
                    Slice<S> window = read.apply(at == null ? 0 : Integer.parseInt(at));
                    return new PageFiller.BackendPage<>(window.items(),
                            window.nextStart() == null ? null : Integer.toString(window.nextStart()));
                },
                items -> policy.retainAllowedById(resourceType, items, idOf));
        String next = filled.next() == null ? null : cursors.issue(tool, fingerprint, filled.next());
        return PagedResult.of(filled.items().stream().map(shape).toList(), next);
    }

    /** The position inside a cursor of this call as text, or null for the first page. */
    private @Nullable String positionOf(@Nullable String cursor, String tool, String fingerprint) {
        if (cursor == null) {
            return null;
        }
        String position = cursors.open(cursor, tool, fingerprint);
        try {
            Integer.parseInt(position);
        } catch (NumberFormatException forged) {
            throw new InvalidToolArgumentException("cursor is not valid for this call");
        }
        return position;
    }

    private int startOf(@Nullable String cursor, String tool, String fingerprint) {
        if (cursor == null) {
            return 0;
        }
        String position = cursors.open(cursor, tool, fingerprint);
        try {
            return Integer.parseInt(position);
        } catch (NumberFormatException forged) {
            throw new InvalidToolArgumentException("cursor is not valid for this call");
        }
    }

    private String nextCursor(Integer nextStart, String tool, String fingerprint) {
        return nextStart == null ? null : cursors.issue(tool, fingerprint, Integer.toString(nextStart));
    }
}
