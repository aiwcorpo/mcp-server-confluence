package pl.aiwcorpo.mcp.confluence.core.domain;

import java.util.List;

/**
 * One window of a list read from Confluence.
 *
 * @param items     the elements Confluence returned that this server can use
 * @param nextStart offset to ask for the next window, or null when Confluence reports nothing more; the offset counts
 *                  what Confluence returned, not what was dropped afterwards
 */
public record Slice<T>(List<T> items, Integer nextStart) {

    public Slice {
        items = List.copyOf(items);
    }

    public static <T> Slice<T> empty() {
        return new Slice<>(List.of(), null);
    }
}
