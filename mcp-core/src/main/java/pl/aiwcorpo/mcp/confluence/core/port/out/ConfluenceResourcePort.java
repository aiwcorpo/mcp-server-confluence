package pl.aiwcorpo.mcp.confluence.core.port.out;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import pl.aiwcorpo.mcp.confluence.core.domain.NewPage;
import pl.aiwcorpo.mcp.confluence.core.domain.Page;
import pl.aiwcorpo.mcp.confluence.core.domain.PageRecord;
import pl.aiwcorpo.mcp.confluence.core.domain.PageSummary;
import pl.aiwcorpo.mcp.confluence.core.domain.Space;

/** Outbound port to Confluence, implemented in mcp-integration. Every call runs as the calling user. */
public interface ConfluenceResourcePort {

    /**
     * Current pages matching {@code cql}, a query the server built (never text from the model); empty when Confluence
     * shows the caller nothing.
     */
    List<PageSummary> searchPages(String cql, int limit);

    /** The page without its body, with the labels of its space; empty when Confluence does not show it to the caller. */
    Optional<PageRecord> findPageRecord(String pageId);

    /** Like {@link #findPageRecord} for many pages with one query; pages that are not shown are left out of the map. */
    Map<String, PageRecord> findPageRecords(Collection<String> pageIds);

    /** The space with its labels; empty when Confluence does not show it to the caller. */
    Optional<Space> findSpace(String spaceKey);

    /** Empty when Confluence does not show the page to the caller, whether it exists or not. */
    Optional<Page> findPage(String pageId);

    /**
     * @throws pl.aiwcorpo.mcp.confluence.core.domain.PageNotFoundException  space or parent not visible, or no
     *         permission to add pages
     * @throws pl.aiwcorpo.mcp.confluence.core.domain.PageRejectedException Confluence refused the page (400)
     */
    PageSummary createPage(NewPage page);
}
