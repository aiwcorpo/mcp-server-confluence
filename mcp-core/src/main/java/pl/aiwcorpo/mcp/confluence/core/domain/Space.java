package pl.aiwcorpo.mcp.confluence.core.domain;

import java.util.List;

/**
 * A Confluence space as the policy sees it.
 *
 * @param labels labels of the space (its classification), or null when Confluence did not report them
 */
public record Space(String key, String name, List<String> labels) {

    public Space {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        labels = labels == null ? null : List.copyOf(labels);
    }
}
