package pl.aiwcorpo.mcp.confluence.tools;

import java.util.regex.Pattern;
import pl.aiwcorpo.mcp.platform.error.InvalidToolArgumentException;

/** Checks of untrusted string arguments; a failure is {@code invalid_argument} with the server's own text. */
final class Arguments {

    /** Multi-line text: tab, line feed and carriage return are the only control characters allowed. */
    static final String MULTI_LINE = "^[^\\u0000-\\u0008\\u000b\\u000c\\u000e-\\u001f\\u007f]*$";

    private Arguments() {
    }

    static String require(String name, String value, int minLength, int maxLength, String pattern, String rule) {
        if (value == null || value.length() < minLength || value.length() > maxLength
                || !Pattern.compile(pattern).matcher(value).matches()) {
            throw new InvalidToolArgumentException(name + " must be " + rule);
        }
        return value;
    }
}
