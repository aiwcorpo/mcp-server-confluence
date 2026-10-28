package pl.aiwcorpo.mcp.confluence.core.domain;

import java.util.List;

/**
 * A page to create. {@code body} is plain text; the adapter turns it into storage format with every markup
 * character escaped, so no macro or HTML can be smuggled in. {@code parentAncestorIds} are the ancestors of the parent
 * as the policy judged them: the adapter compares them with where Confluence put the page.
 */
public record NewPage(String spaceKey, String parentId, List<String> parentAncestorIds, String title, String body) {

    public NewPage {
        parentAncestorIds = List.copyOf(parentAncestorIds);
    }
}
