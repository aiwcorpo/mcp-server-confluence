package pl.aiwcorpo.mcp.confluence.application.service;

import java.util.List;
import pl.aiwcorpo.mcp.confluence.core.domain.ConfluenceIds;
import pl.aiwcorpo.mcp.confluence.core.domain.NewPage;
import pl.aiwcorpo.mcp.confluence.core.domain.Page;
import pl.aiwcorpo.mcp.confluence.core.domain.PageNotFoundException;
import pl.aiwcorpo.mcp.confluence.core.domain.PageSummary;
import pl.aiwcorpo.mcp.confluence.core.port.in.ConfluenceUseCase;
import pl.aiwcorpo.mcp.confluence.core.port.out.ConfluenceResourcePort;

/** Use cases of the server. A plain class: mcp-app makes it a bean, so this module stays free of Spring. */
public class ConfluenceService implements ConfluenceUseCase {

    /** Upper bound of search hits handed to the model; also what Confluence is asked for. */
    static final int SEARCH_LIMIT = 25;

    private final ConfluenceResourcePort resource;

    public ConfluenceService(ConfluenceResourcePort resource) {
        this.resource = resource;
    }

    /**
     * The single place where search is scoped. In M1 the scope is the space argument: the adapter builds the
     * CQL for that space only, and hits from any other space are dropped here as well. The starter beta's
     * QueryScope (narrowing by the virtual server's spaces) and ResultFilter (per-hit policy check) plug in
     * at this method.
     */
    @Override
    public List<PageSummary> searchPages(String spaceKey, String text) {
        return resource.searchPages(spaceKey, text, SEARCH_LIMIT).stream()
                .filter(hit -> spaceKey.equals(hit.spaceKey()))
                .limit(SEARCH_LIMIT)
                .toList();
    }

    /**
     * Pages of personal spaces are reported as not found: such a space cannot be granted or narrowed in the
     * policy (ConfluenceSpaceKey of the contracts), so the server never hands one out.
     */
    @Override
    public Page getPage(String pageId) {
        return resource.findPage(pageId)
                .filter(page -> ConfluenceIds.isSpaceKey(page.spaceKey()))
                .orElseThrow(PageNotFoundException::new);
    }

    /**
     * The parent must be a visible page of the named space. Confluence would place the page wherever the
     * parent lives, so a parent from another space would turn a write the gateway allowed for one space into
     * a write in another.
     */
    @Override
    public PageSummary createPage(String spaceKey, String parentId, String title, String body) {
        resource.findPageSummary(parentId)
                .filter(parent -> spaceKey.equals(parent.spaceKey()))
                .orElseThrow(PageNotFoundException::new);
        return resource.createPage(new NewPage(spaceKey, parentId, title, body));
    }
}
