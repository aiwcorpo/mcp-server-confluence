package pl.aiwcorpo.mcp.confluence.core.port.in;

import java.util.List;
import pl.aiwcorpo.mcp.confluence.core.domain.Page;
import pl.aiwcorpo.mcp.confluence.core.domain.PageRecord;
import pl.aiwcorpo.mcp.confluence.core.domain.PageSummary;

/** Inbound port: what the server can do, in business terms. Implemented in mcp-application, called by mcp-tools. */
public interface ConfluenceUseCase {

    /**
     * Pages of {@code spaceKey} that the server-built {@code cql} finds; the space is mandatory, there is no search
     * across spaces.
     */
    List<PageSummary> searchPages(String spaceKey, String cql);

    /**
     * The page with its body. {@code judged} is the record the policy was evaluated on: a page that moved to another
     * space or tree since then is not returned.
     *
     * @throws pl.aiwcorpo.mcp.confluence.core.domain.PageNotFoundException when the page is not visible any more or
     *         differs from {@code judged}
     */
    Page getPage(PageRecord judged);

    /**
     * @param parent the parent page as the policy judged it
     * @throws pl.aiwcorpo.mcp.confluence.core.domain.PageNotFoundException when {@code parent} is not a page of
     *         {@code spaceKey}, or Confluence refuses to show the space
     * @throws pl.aiwcorpo.mcp.confluence.core.domain.PageRejectedException when Confluence refuses the page
     */
    PageSummary createPage(String spaceKey, PageRecord parent, String title, String body);
}
