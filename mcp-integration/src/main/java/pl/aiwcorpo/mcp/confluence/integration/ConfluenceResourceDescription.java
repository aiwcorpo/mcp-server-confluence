package pl.aiwcorpo.mcp.confluence.integration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import pl.aiwcorpo.mcp.cedar.entities.ResolvedResource;
import pl.aiwcorpo.mcp.confluence.core.domain.ConfluenceResourceModel;
import pl.aiwcorpo.mcp.confluence.core.domain.PageRecord;
import pl.aiwcorpo.mcp.confluence.core.domain.Space;

/**
 * The page and the space as the policy sees them (C7, entity-builder.md). Ids, the space of a page and its ancestor
 * ids are what Confluence reports, never the argument of the call. {@code Confluence::Space.labels} is required: a
 * space whose labels Confluence did not report gets no attribute and the builder refuses it (fail closed).
 */
final class ConfluenceResourceDescription {

    private ConfluenceResourceDescription() {
    }

    static ResolvedResource of(PageRecord page) {
        return ResolvedResource.of(ConfluenceResourceModel.PAGE_TYPE, page.id(),
                        Map.of("id", page.id(), "ancestorIds", page.ancestorIds()))
                .withParent(space(page.spaceKey(), page.spaceLabels()));
    }

    static ResolvedResource of(Space space) {
        return space(space.key(), space.labels());
    }

    private static ResolvedResource space(String key, List<String> labels) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("key", key);
        if (labels != null) {
            attributes.put("labels", labels);
        }
        return ResolvedResource.of(ConfluenceResourceModel.SPACE_TYPE, key, attributes);
    }
}
