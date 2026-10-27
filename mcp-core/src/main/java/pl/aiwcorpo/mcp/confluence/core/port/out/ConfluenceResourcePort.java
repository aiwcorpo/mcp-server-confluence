package pl.aiwcorpo.mcp.confluence.core.port.out;

import java.util.List;
import java.util.Optional;
import pl.aiwcorpo.mcp.confluence.core.domain.NewPage;
import pl.aiwcorpo.mcp.confluence.core.domain.Page;
import pl.aiwcorpo.mcp.confluence.core.domain.PageSummary;

/** Outbound port to Confluence, implemented in mcp-integration. Every call runs as the calling user. */
public interface ConfluenceResourcePort {

    /** Current pages of one space matching the words of {@code text}; empty when the space is not visible. */
    List<PageSummary> searchPages(String spaceKey, String text, int limit);

    /** Empty when Confluence does not show the page to the caller, whether it exists or not. */
    Optional<Page> findPage(String pageId);

    /** Like {@link #findPage} without the body, for checks before a write. */
    Optional<PageSummary> findPageSummary(String pageId);

    /**
     * @throws pl.aiwcorpo.mcp.confluence.core.domain.PageNotFoundException  space or parent not visible, or no
     *         permission to add pages
     * @throws pl.aiwcorpo.mcp.confluence.core.domain.PageRejectedException Confluence refused the page (400)
     */
    PageSummary createPage(NewPage page);
}
