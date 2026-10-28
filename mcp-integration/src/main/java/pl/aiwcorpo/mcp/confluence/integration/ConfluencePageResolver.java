package pl.aiwcorpo.mcp.confluence.integration;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import pl.aiwcorpo.mcp.cedar.entities.ResolvedResource;
import pl.aiwcorpo.mcp.confluence.core.domain.ConfluenceResourceModel;
import pl.aiwcorpo.mcp.confluence.core.domain.PageRecord;
import pl.aiwcorpo.mcp.confluence.core.port.out.ConfluenceResourcePort;
import pl.aiwcorpo.mcp.platform.resource.ResourceResolver;

/**
 * Tells the starter what a page id is before the tool runs: it asks Confluence with the caller's own credential and
 * describes the answer for the policy (space, labels of the space, ancestor pages). The same resolver judges the hits of
 * a search, in one query for the whole result.
 *
 * <p>{@link #fetch} is empty for "not there" and "not allowed for this user" alike (the adapter maps 404 and 403 to it)
 * and throws for a backend that fails, which the client sees as an upstream failure, not as a denial.
 */
@Component
public class ConfluencePageResolver implements ResourceResolver<PageRecord> {

    private final ConfluenceResourcePort resource;

    public ConfluencePageResolver(ConfluenceResourcePort resource) {
        this.resource = resource;
    }

    @Override
    public String resourceType() {
        return ConfluenceResourceModel.PAGE_TYPE;
    }

    @Override
    public Optional<PageRecord> fetch(String pageId) {
        return resource.findPageRecord(pageId);
    }

    @Override
    public Map<String, PageRecord> fetchAll(Collection<String> pageIds) {
        return resource.findPageRecords(pageIds);
    }

    @Override
    public ResolvedResource describe(PageRecord page) {
        return ConfluenceResourceDescription.of(page);
    }
}
