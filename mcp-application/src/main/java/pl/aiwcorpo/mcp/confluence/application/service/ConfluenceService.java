package pl.aiwcorpo.mcp.confluence.application.service;

import java.util.List;
import pl.aiwcorpo.mcp.confluence.core.domain.NewPage;
import pl.aiwcorpo.mcp.confluence.core.domain.Page;
import pl.aiwcorpo.mcp.confluence.core.domain.PageNotFoundException;
import pl.aiwcorpo.mcp.confluence.core.domain.PageRecord;
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
     * The query is the tool's (built from the scope of the call and structured filters), this method only drops what
     * Confluence returns beyond the named space. Which of the remaining hits the caller may see is the starter's
     * result filter, which judges every hit by its page id.
     */
    @Override
    public List<PageSummary> searchPages(String spaceKey, String cql) {
        return resource.searchPages(cql, SEARCH_LIMIT).stream()
                .filter(hit -> spaceKey.equals(hit.spaceKey()))
                .limit(SEARCH_LIMIT)
                .toList();
    }

    /**
     * The policy was evaluated on {@code judged}; the body is read afterwards, so the page is compared with what
     * was judged. A page moved to another space or tree in between is not returned: the decision would not apply to it.
     */
    @Override
    public Page getPage(PageRecord judged) {
        return resource.findPage(judged.id())
                .filter(page -> judged.spaceKey().equals(page.spaceKey()) && judged.ancestorIds().equals(page.ancestorIds()))
                .orElseThrow(PageNotFoundException::new);
    }

    /**
     * Confluence would place the page wherever the parent lives, so a parent from another space would turn a write the
     * policy allowed for one space into a write in another. The parent is the record the policy judged, not a second
     * lookup.
     */
    @Override
    public PageSummary createPage(String spaceKey, PageRecord parent, String title, String body) {
        if (!spaceKey.equals(parent.spaceKey())) {
            throw new PageNotFoundException();
        }
        return resource.createPage(new NewPage(spaceKey, parent.id(), title, body));
    }
}
