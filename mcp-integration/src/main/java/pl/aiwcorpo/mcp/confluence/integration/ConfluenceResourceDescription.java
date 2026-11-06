package pl.aiwcorpo.mcp.confluence.integration;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import pl.aiwcorpo.mcp.cedar.entities.ResolvedResource;
import pl.aiwcorpo.mcp.cedar.entities.Scopes;
import pl.aiwcorpo.mcp.confluence.core.domain.ConfluenceIds;
import pl.aiwcorpo.mcp.confluence.core.domain.ConfluenceResourceModel;
import pl.aiwcorpo.mcp.confluence.core.domain.PageRecord;
import pl.aiwcorpo.mcp.confluence.core.domain.Space;

/**
 * The page and the space as the policy sees them (C7, entity-builder.md). Ids, the space of a page and its ancestor
 * ids are what Confluence reports, never the argument of the call. {@code Confluence::Space.labels} is required: a
 * space whose labels Confluence did not report gets no attribute and the builder refuses it (fail closed).
 *
 * <p>Scopes: a space carries its own ({@code Confluence::Space/<key>}); a page carries those of its space, of every
 * ancestor page and its own ({@code Confluence::Page/<id>}), so a grant on a page tree covers every page below the root
 * and a grant on the space covers all of its pages. The derivation is the server's, not the builder's.
 */
final class ConfluenceResourceDescription {

    private ConfluenceResourceDescription() {
    }

    /** Container that no scope can name, for the page the platform judges before it knows where the page is. */
    static final String UNGRANTABLE_SPACE = "NOSCOPE";

    static ResolvedResource of(PageRecord page) {
        List<String> scopes = new ArrayList<>();
        scopes.add(Scopes.id(ConfluenceResourceModel.SPACE_TYPE, page.spaceKey()));
        // An id that is not a page id cannot be granted (it would not pass scopePattern), so it is left out, not an error.
        page.ancestorIds().stream().filter(ConfluenceIds::isPageId)
                .forEach(ancestor -> scopes.add(Scopes.id(ConfluenceResourceModel.PAGE_TYPE, ancestor)));
        scopes.add(Scopes.id(ConfluenceResourceModel.PAGE_TYPE, page.id()));
        return ResolvedResource.of(ConfluenceResourceModel.PAGE_TYPE, page.id(),
                        Map.of("id", page.id(), "ancestorIds", page.ancestorIds()))
                .withParent(space(page.spaceKey(), page.spaceLabels()))
                .withScopes(scopes);
    }

    /**
     * A page the caller has no scope for, in a space nobody can be granted: the policy decides on it whether anything is
     * left to resolve. Labels are empty (least restrictive), so only the permits and the group decide.
     */
    static Optional<ResolvedResource> pageOutsideScopes(String pageId) {
        return Optional.of(ResolvedResource.of(ConfluenceResourceModel.PAGE_TYPE, pageId,
                        Map.of("id", pageId, "ancestorIds", List.of()))
                .withParent(space(UNGRANTABLE_SPACE, List.of()))
                .withScopes(List.of(Scopes.id(ConfluenceResourceModel.SPACE_TYPE, UNGRANTABLE_SPACE),
                        Scopes.id(ConfluenceResourceModel.PAGE_TYPE, pageId))));
    }

    /** The space as its key alone describes it, with the least restrictive labels, so the grant is judged before Confluence is asked (CC-146). */
    static Optional<ResolvedResource> probeOfSpace(String spaceKey) {
        return Optional.of(space(spaceKey, List.of()));
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
        return ResolvedResource.of(ConfluenceResourceModel.SPACE_TYPE, key, attributes)
                .withScopes(List.of(Scopes.id(ConfluenceResourceModel.SPACE_TYPE, key)));
    }
}
