package pl.aiwcorpo.mcp.confluence.core.port.in;

import java.util.List;
import pl.aiwcorpo.mcp.confluence.core.domain.Page;
import pl.aiwcorpo.mcp.confluence.core.domain.PageSummary;

/** Inbound port: what the server can do, in business terms. Implemented in mcp-application, called by mcp-tools. */
public interface ConfluenceUseCase {

    /** Pages of {@code spaceKey} only; the space is mandatory, there is no search across spaces. */
    List<PageSummary> searchPages(String spaceKey, String text);

    /**
     * @throws pl.aiwcorpo.mcp.confluence.core.domain.PageNotFoundException when the page does not exist, is not
     *         visible to the caller or lies in a personal space
     */
    Page getPage(String pageId);

    /**
     * @throws pl.aiwcorpo.mcp.confluence.core.domain.PageNotFoundException when the parent is not a visible page
     *         of {@code spaceKey}
     * @throws pl.aiwcorpo.mcp.confluence.core.domain.PageRejectedException when Confluence refuses the page
     */
    PageSummary createPage(String spaceKey, String parentId, String title, String body);
}
