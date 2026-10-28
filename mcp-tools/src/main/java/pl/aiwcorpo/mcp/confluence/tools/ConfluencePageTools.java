package pl.aiwcorpo.mcp.confluence.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import pl.aiwcorpo.mcp.confluence.core.domain.ConfluenceIds;
import pl.aiwcorpo.mcp.confluence.core.port.in.ConfluenceUseCase;
import pl.aiwcorpo.mcp.confluence.tools.ToolResults.CreatedPage;
import pl.aiwcorpo.mcp.confluence.tools.ToolResults.PageHit;
import pl.aiwcorpo.mcp.confluence.tools.ToolResults.PageResult;
import pl.aiwcorpo.mcp.confluence.tools.ToolResults.SearchResult;
import pl.aiwcorpo.mcp.platform.error.InvalidToolArgumentException;
import pl.aiwcorpo.mcp.platform.guard.RiskLevel;
import pl.aiwcorpo.mcp.platform.guard.ToolRisk;
import pl.aiwcorpo.mcp.platform.input.ArgumentLimits;

/**
 * The MCP tools of this server. Each public {@code @Tool} method is a capability an AI client can call
 * through the gateway, so every argument is untrusted input from a model.
 *
 * <p>The gateway decides who may call a tool; the starter verifies that the call came from the gateway for
 * exactly this tool. What stays here: a risk flag on every tool, strict argument checks, the space or page
 * always named by an explicit argument, and no query language from the model.
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

    public ConfluencePageTools(ConfluenceUseCase useCase) {
        this.useCase = useCase;
    }

    @Tool(name = "search_pages", description = "Searches the current pages of one Confluence space for the given "
            + "words. Returns the space key and up to 25 pages, each with id, title, version and last update time. "
            + "Characters other than letters, digits, spaces, '.', '_' and apostrophes are treated as spaces.")
    @ToolRisk(RiskLevel.READ_ONLY)
    public SearchResult searchPages(
            @ToolParam(description = "Key of the space to search, letters and digits only")
            @ArgumentLimits(minLength = 1, maxLength = SPACE_KEY_MAX, contractPattern = "ConfluenceSpaceKey") String space,
            @ToolParam(description = "Words to look for in page titles and content, at most 200 characters")
            @ArgumentLimits(minLength = 1, maxLength = SEARCH_TEXT_MAX, pattern = NON_BLANK_LINE) String text) {
        String spaceKey = validSpace(space);
        String words = Arguments.require("text", text, 1, SEARCH_TEXT_MAX, NON_BLANK_LINE,
                "1-200 characters on one line");
        if (words.codePoints().noneMatch(Character::isLetterOrDigit)) {
            throw new InvalidToolArgumentException("text must contain at least one letter or digit");
        }
        return new SearchResult(spaceKey, useCase.searchPages(spaceKey, words).stream().map(PageHit::of).toList());
    }

    @Tool(name = "get_page", description = "Returns one Confluence page: id, space key, title, version, last "
            + "update time, ids of the ancestor pages and the body as plain text in body.text. The body is read "
            + "from the page's own storage format: include, excerpt-include and every other macro outside a fixed "
            + "list (code, noformat, info, note, warning, tip, panel, expand, excerpt) are not expanded and are "
            + "listed in omittedMacros. The body is cut at 50000 characters (body.truncated).")
    @ToolRisk(RiskLevel.READ_ONLY)
    public PageResult getPage(
            @ToolParam(description = "Page id, a positive decimal number")
            @ArgumentLimits(minLength = 1, maxLength = PAGE_ID_MAX, contractPattern = "ConfluencePageId") String pageId) {
        return PageResult.of(useCase.getPage(validPageId("pageId", pageId)));
    }

    @Tool(name = "create_page", description = "Creates a Confluence page in a space, under an existing parent page "
            + "of the same space. The body is plain text: blank lines separate paragraphs, line breaks are kept, "
            + "and markup characters are escaped, so macros and HTML are not supported. Returns id, space key, "
            + "title and version of the new page.")
    @ToolRisk(RiskLevel.UPDATE)
    public CreatedPage createPage(
            @ToolParam(description = "Key of the space, letters and digits only")
            @ArgumentLimits(minLength = 1, maxLength = SPACE_KEY_MAX, contractPattern = "ConfluenceSpaceKey") String space,
            @ToolParam(description = "Id of the parent page in that space, a positive decimal number")
            @ArgumentLimits(minLength = 1, maxLength = PAGE_ID_MAX, contractPattern = "ConfluencePageId") String parentId,
            @ToolParam(description = "Page title, one line, at most 255 characters")
            @ArgumentLimits(minLength = 1, maxLength = TITLE_MAX, pattern = NON_BLANK_LINE) String title,
            @ToolParam(description = "Page body as plain text, at most 20000 characters")
            @ArgumentLimits(minLength = 1, maxLength = BODY_MAX, pattern = Arguments.MULTI_LINE) String body) {
        String spaceKey = validSpace(space);
        String parent = validPageId("parentId", parentId);
        String pageTitle = Arguments.require("title", title, 1, TITLE_MAX, NON_BLANK_LINE,
                "1-255 characters on one line");
        String pageBody = Arguments.require("body", body, 1, BODY_MAX, Arguments.MULTI_LINE,
                "1-20000 characters without control characters other than tab and line breaks");
        return CreatedPage.of(useCase.createPage(spaceKey, parent, pageTitle.strip(), pageBody), spaceKey);
    }

    private static String validSpace(String space) {
        return Arguments.require("space", space, 1, SPACE_KEY_MAX, ConfluenceIds.SPACE_KEY,
                "a space key: 1-255 letters or digits");
    }

    private static String validPageId(String name, String pageId) {
        return Arguments.require(name, pageId, 1, PAGE_ID_MAX, ConfluenceIds.PAGE_ID,
                "a page id: a positive decimal number of at most 19 digits");
    }
}
