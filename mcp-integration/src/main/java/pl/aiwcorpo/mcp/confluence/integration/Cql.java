package pl.aiwcorpo.mcp.confluence.integration;

import java.util.regex.Pattern;

/**
 * Builds the only CQL query this server sends. The model never writes CQL: it names a space and gives words,
 * and the query is assembled here from fixed clauses.
 */
final class Cql {

    /**
     * Anything but letters, digits, whitespace and {@code . _ '} is replaced by a space. That removes CQL string
     * delimiters and Lucene operators ({@code * ? ~ : ( ) [ ] { } + - ! & | ^ " \ /}) from the text, so the
     * words are matched as words and cannot change the query, and Confluence does not answer 400 for a stray
     * wildcard.
     */
    private static final Pattern NOT_A_WORD_CHARACTER = Pattern.compile("[^\\p{L}\\p{N}\\s._']+");

    private Cql() {
    }

    static String pagesInSpaceMatching(String spaceKey, String text) {
        return "space = " + quoted(spaceKey) + " AND type = page AND text ~ " + quoted(words(text));
    }

    static String words(String text) {
        return NOT_A_WORD_CHARACTER.matcher(text).replaceAll(" ").replaceAll("\\s+", " ").strip();
    }

    /** CQL string literal; backslash and double quote escaped even where the input grammar excludes them. */
    static String quoted(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
