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
     * Search is scoped twice: the adapter builds the CQL for the named space only, and every hit must belong to
     * that space and pass {@link #spaceInScope}. The starter beta's QueryScope (narrowing by the virtual
     * server's spaces) plugs in before the port call, its ResultFilter into {@link #spaceInScope}.
     */
    @Override
    public List<PageSummary> searchPages(String spaceKey, String text) {
        return resource.searchPages(spaceKey, text, SEARCH_LIMIT).stream()
                .filter(hit -> spaceKey.equals(hit.spaceKey()) && spaceInScope(hit.spaceKey()))
                .limit(SEARCH_LIMIT)
                .toList();
    }

    /**
     * {@code get_page} names only the page, so the space is known after the fetch; the page passes the same
     * {@link #spaceInScope} check as search hits. Out of scope, missing and forbidden are one
     * {@link PageNotFoundException}, so the client cannot tell them apart.
     */
    @Override
    public Page getPage(String pageId) {
        return resource.findPage(pageId)
                .filter(page -> spaceInScope(page.spaceKey()))
                .orElseThrow(PageNotFoundException::new);
    }

    /**
     * The one place where a resolved page's space is checked against the caller's scope. In M1 Confluence
     * enforces the user's own permissions (the call runs with the user's PAT) and this check only refuses
     * personal spaces, which the policy cannot grant or narrow (ConfluenceSpaceKey of the contracts).
     * With the starter beta, the policy decision on {@code Confluence::Page} / {@code Confluence::Space}
     * (@ResourceRef, vsScopes of the virtual server, Cedar on the server) replaces this method.
     */
    private static boolean spaceInScope(String spaceKey) {
        return ConfluenceIds.isSpaceKey(spaceKey);
    }

    /**
     * The parent must be a visible page of the named space. Confluence would place the page wherever the
     * parent lives, so a parent from another space would turn a write the gateway allowed for one space into
     * a write in another.
     */
    @Override
    public PageSummary createPage(String spaceKey, String parentId, String title, String body) {
        resource.findPageSummary(parentId)
                .filter(parent -> spaceKey.equals(parent.spaceKey()) && spaceInScope(parent.spaceKey()))
                .orElseThrow(PageNotFoundException::new);
        return resource.createPage(new NewPage(spaceKey, parentId, title, body));
    }
}
