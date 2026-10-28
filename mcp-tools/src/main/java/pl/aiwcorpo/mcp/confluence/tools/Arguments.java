package pl.aiwcorpo.mcp.confluence.tools;

import java.util.regex.Pattern;
import pl.aiwcorpo.mcp.platform.error.InvalidToolArgumentException;

/** Checks of untrusted string arguments; a failure is {@code invalid_argument} with the server's own text. */
final class Arguments {

    /** Multi-line text: tab, line feed and carriage return are the only control characters allowed. */
    static final String MULTI_LINE = "^[^\\u0000-\\u0008\\u000b\\u000c\\u000e-\\u001f\\u007f]*$";

    /**
     * Anything but letters, digits, whitespace and {@code . _ '} is replaced by a space. Lucene's operators
     * ({@code * ? ~ : ( ) [ ] { } + - ! & | ^ " \ /}) never reach the query, so the words are matched as words and
     * Confluence does not answer 400 for a stray wildcard. The query builder escapes what is left; this keeps the
     * text to what the tool description promises.
     */
    private static final Pattern NOT_A_WORD_CHARACTER = Pattern.compile("[^\\p{L}\\p{N}\\s._']+");

    private Arguments() {
    }

    static String searchWords(String text) {
        return NOT_A_WORD_CHARACTER.matcher(text).replaceAll(" ").replaceAll("\\s+", " ").strip();
    }

    static String require(String name, String value, int minLength, int maxLength, String pattern, String rule) {
        if (value == null || value.length() < minLength || value.length() > maxLength
                || !Pattern.compile(pattern).matcher(value).matches()) {
            throw new InvalidToolArgumentException(name + " must be " + rule);
        }
        return value;
    }
}
