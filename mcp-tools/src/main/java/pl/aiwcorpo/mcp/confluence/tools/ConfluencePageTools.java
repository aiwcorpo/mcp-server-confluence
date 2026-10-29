package pl.aiwcorpo.mcp.confluence.tools;

import java.util.Set;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import pl.aiwcorpo.mcp.confluence.core.domain.ConfluenceIds;
import pl.aiwcorpo.mcp.confluence.core.domain.ConfluenceResourceModel;
import pl.aiwcorpo.mcp.confluence.core.domain.PageRecord;
import pl.aiwcorpo.mcp.confluence.core.domain.Space;
import pl.aiwcorpo.mcp.confluence.core.port.in.ConfluenceUseCase;
import pl.aiwcorpo.mcp.confluence.tools.ToolResults.CreatedPage;
import pl.aiwcorpo.mcp.confluence.tools.ToolResults.PageHit;
import pl.aiwcorpo.mcp.confluence.tools.ToolResults.PageResult;
import pl.aiwcorpo.mcp.confluence.tools.ToolResults.SearchResult;
import pl.aiwcorpo.mcp.confluence.tools.ToolResults.SpaceResult;
import pl.aiwcorpo.mcp.platform.error.InvalidToolArgumentException;
import pl.aiwcorpo.mcp.platform.guard.RiskLevel;
import pl.aiwcorpo.mcp.platform.guard.ToolRisk;
import pl.aiwcorpo.mcp.platform.guard.ToolTitle;
import pl.aiwcorpo.mcp.platform.input.ArgumentLimits;
import pl.aiwcorpo.mcp.platform.resource.QueryScope;
import pl.aiwcorpo.mcp.platform.resource.ResourceContext;
import pl.aiwcorpo.mcp.platform.resource.ResourcePolicy;
import pl.aiwcorpo.mcp.platform.resource.ResourceRef;
import pl.aiwcorpo.mcp.platform.resource.StructuredQuery.Operator;

/**
 * The MCP tools of this server. Each public {@code @Tool} method is a capability an AI client can call
 * through the gateway, so every argument is untrusted input from a model.
 *
 * <p>The gateway decides who may call a tool and asks for confirmations; the starter verifies that the call came from
 * the gateway for exactly this tool and evaluates the policy (C7) on the pages and spaces the tool names. What stays
 * here: a risk flag and a title on every tool, strict argument limits, every page or space named by an explicit
 * argument marked {@link ResourceRef}, and a search whose CQL the server builds from structured filters.
 */
@Service
public class ConfluencePageTools {

    static final int SPACE_KEY_MAX = 255;
    static final int PAGE_ID_MAX = 19;
    static final int SEARCH_TEXT_MAX = 200;
    static final int TITLE_MAX = 255;
    static final int BODY_MAX = 20_000;

    /** Single line with at least one non-space character. */
    static final String NON_BLANK_LINE = "^(?=.*\\S)[^\\u0000-\\u001f\\u007f]+$";

    private final ConfluenceUseCase useCase;
    private final ResourcePolicy policy;

    public ConfluencePageTools(ConfluenceUseCase useCase, ResourcePolicy policy) {
        this.useCase = useCase;
        this.policy = policy;
    }

    /**
     * The model names the space and the words, never a query: the server builds the CQL. The space is a
     * {@code @ResourceRef}, so a missing, hidden, restricted or out-of-link space is {@code not_found_or_denied} before
     * anything is searched. The narrowing of the link still goes into the query ({@link QueryScope}); the hits are
     * filtered afterwards by their {@code @ResourceRef} component, which removes what Confluence returns beyond the space
     * and pages that are now somewhere the policy refuses.
     */
    @Tool(name = "search_pages", description = "Searches the current pages of one Confluence space for the given "
            + "words. Returns the space key and up to 25 pages, each with id, title, version and last update time. "
            + "Characters other than letters, digits, spaces, '.', '_' and apostrophes are treated as spaces.")
    @ToolRisk(RiskLevel.READ_ONLY)
    @ToolTitle("Szukaj stron")
    public SearchResult searchPages(
            @ToolParam(description = "Key of the space to search, letters and digits only")
            @ArgumentLimits(minLength = 1, maxLength = SPACE_KEY_MAX, contractPattern = "ConfluenceSpaceKey")
            @ResourceRef(type = ConfluenceResourceModel.SPACE_TYPE) String space,
            @ToolParam(description = "Words to look for in page titles and content, at most 200 characters")
            @ArgumentLimits(minLength = 1, maxLength = SEARCH_TEXT_MAX, pattern = NON_BLANK_LINE) String text) {
        String spaceKey = judgedSpace(validSpace(space)).key();
        String words = Arguments.searchWords(Arguments.require("text", text, 1, SEARCH_TEXT_MAX, NON_BLANK_LINE,
                "1-200 characters on one line"));
        if (words.codePoints().noneMatch(Character::isLetterOrDigit)) {
            throw new InvalidToolArgumentException("text must contain at least one letter or digit");
        }
        QueryScope scope = policy.queryScope(ConfluenceResourceModel.SPACE_TYPE);
        String cql = scope.cql(Set.of("text"))
                .where("text", Operator.CONTAINS, words)
                .build();
        return new SearchResult(spaceKey, useCase.searchPages(spaceKey, cql).stream().map(PageHit::of).toList());
    }

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
    @ToolTitle("Pobierz stronę")
    public PageResult getPage(
            @ToolParam(description = "Page id, a positive decimal number")
            @ArgumentLimits(minLength = 1, maxLength = PAGE_ID_MAX, contractPattern = "ConfluencePageId")
            @ResourceRef(type = ConfluenceResourceModel.PAGE_TYPE) String pageId) {
        return PageResult.of(useCase.getPage(judgedPage(pageId)));
    }

    @Tool(name = "get_space", description = "Returns one Confluence space: its key and name.")
    @ToolRisk(RiskLevel.READ_ONLY)
    @ToolTitle("Pobierz przestrzeń")
    public SpaceResult getSpace(
            @ToolParam(description = "Key of the space, letters and digits only")
            @ArgumentLimits(minLength = 1, maxLength = SPACE_KEY_MAX, contractPattern = "ConfluenceSpaceKey")
            @ResourceRef(type = ConfluenceResourceModel.SPACE_TYPE) String space) {
        return SpaceResult.of(judgedSpace(space));
    }

    /**
     * The policy judges the space and the parent page with the action {@code update}, so the user needs a write grant
     * on the space. The {@code space} argument is also compared with the space of the parent the policy evaluated, so the
     * page cannot land in another space than the one granted.
     */
    @Tool(name = "create_page", description = "Creates a Confluence page in a space, under an existing parent page "
            + "of the same space. The body is plain text: blank lines separate paragraphs, line breaks are kept, "
            + "and markup characters are escaped, so macros and HTML are not supported. Returns id, space key, "
            + "title and version of the new page.")
    @ToolRisk(RiskLevel.UPDATE)
    @ToolTitle("Utwórz stronę")
    public CreatedPage createPage(
            @ToolParam(description = "Key of the space, letters and digits only")
            @ArgumentLimits(minLength = 1, maxLength = SPACE_KEY_MAX, contractPattern = "ConfluenceSpaceKey")
            @ResourceRef(type = ConfluenceResourceModel.SPACE_TYPE) String space,
            @ToolParam(description = "Id of the parent page in that space, a positive decimal number")
            @ArgumentLimits(minLength = 1, maxLength = PAGE_ID_MAX, contractPattern = "ConfluencePageId")
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
        return CreatedPage.of(useCase.createPage(spaceKey, judgedPage(parentId), pageTitle.strip(), pageBody), spaceKey);
    }

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

    private static String validSpace(String space) {
        return Arguments.require("space", space, 1, SPACE_KEY_MAX, ConfluenceIds.SPACE_KEY,
                "a space key: 1-255 letters or digits");
    }
}
