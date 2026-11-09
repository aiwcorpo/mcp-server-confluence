package pl.aiwcorpo.mcp.confluence.core.domain;

import java.util.List;

/**
 * Line diff between the plain-text bodies of two versions of a page.
 *
 * @param diff      unified diff, empty when the texts are equal
 * @param truncated the diff was cut at the server's size limit
 * @param omitted   fixed codes: {@code body_truncated} (a body was already cut when it was read) and
 *                  {@code diff_too_large} (the texts are too long to compare; {@code diff} is empty then)
 */
public record VersionDiff(int fromVersion, int toVersion, String diff, boolean identical, boolean truncated,
                          List<String> omitted) {

    public VersionDiff {
        omitted = List.copyOf(omitted);
    }
}
