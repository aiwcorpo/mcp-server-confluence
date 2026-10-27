package pl.aiwcorpo.mcp.confluence.core.domain;

import java.util.List;

/**
 * Body of a page as plain text, taken from the page's own storage format. Macros that pull content from
 * elsewhere (include, excerpt-include, ...) are never expanded; their names are listed instead.
 *
 * @param omittedMacros names of the macros left out, in order of appearance, without duplicates
 * @param truncated     the text was cut at the server's size limit
 */
public record PageText(String text, List<String> omittedMacros, boolean truncated) {

    public PageText {
        text = text == null ? "" : text;
        omittedMacros = List.copyOf(omittedMacros);
    }
}
