package pl.aiwcorpo.mcp.confluence.tools;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import pl.aiwcorpo.mcp.platform.error.InvalidToolArgumentException;

/**
 * Makes a CQL query written by the model safe to send: the query becomes one parenthesised condition that the server's
 * own scope clause is ANDed to, so nothing the model wrote can widen the scope.
 *
 * <p>A condition in parentheses is only a barrier when the text cannot close the parenthesis itself, so the text is
 * scanned before it is used: quotes must be closed (backslash escapes the next character), parentheses must balance and
 * never close more than they opened, and outside quotes only the characters a condition needs are allowed (letters,
 * digits, space, {@code _ . , = ! ~ < > ( )}). One trailing {@code ORDER BY} on created, lastmodified or title is
 * taken out and put after the scope; an {@code ORDER} anywhere else is refused. The scan is a second line of defence:
 * the hits are in any case judged by the space they are in and by the policy.
 */
final class CqlConfinement {

    static final int MAX_LENGTH = 500;

    private static final Pattern OUTSIDE_QUOTES = Pattern.compile("[\\p{L}\\p{N}_.,=!~<>() \\t]");
    private static final Pattern ORDER_CLAUSE = Pattern.compile("(?i)^by +(created|lastmodified|title)(?: +(asc|desc))?$");
    private static final Pattern ORDER_WORD = Pattern.compile("(?i)(?<![\\p{L}\\p{N}_.])order(?![\\p{L}\\p{N}_.])");

    private CqlConfinement() {
    }

    /**
     * @param spaceClause the scope clause of the server, for example {@code space in ("ENG")}
     * @return the query to send: {@code (<condition>) AND <spaceClause> AND type = page [ORDER BY ...]}
     */
    static String confine(String cql, String spaceClause) {
        if (cql == null || cql.isBlank() || cql.length() > MAX_LENGTH) {
            throw refused("cql must be 1-" + MAX_LENGTH + " characters");
        }
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < cql.length(); i++) {
            char c = cql.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                    if (i >= cql.length() || cql.charAt(i) < 0x20) {
                        throw refused("cql has an unfinished escape");
                    }
                } else if (c == quote) {
                    quote = 0;
                } else if (c < 0x20 && c != '\t' || c == 0x7f) {
                    throw refused("cql must not contain control characters");
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (!OUTSIDE_QUOTES.matcher(String.valueOf(c)).matches()) {
                throw refused("cql has a character that is allowed only inside quotes");
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth < 0) {
                throw refused("cql has a closing parenthesis without an opening one");
            }
        }
        if (quote != 0) {
            throw refused("cql has a quote that is not closed");
        }
        if (depth != 0) {
            throw refused("cql has a parenthesis that is not closed");
        }
        String condition = cql.strip();
        String order = "";
        String plain = withoutQuoted(condition);
        Matcher word = ORDER_WORD.matcher(plain);
        if (word.find()) {
            int at = word.start();
            Matcher clause = ORDER_CLAUSE.matcher(plain.substring(word.end()).strip());
            if (word.find() || depthAt(plain, at) != 0 || !clause.matches()) {
                throw refused("cql may end with one ORDER BY created, lastmodified or title, and has it nowhere else");
            }
            order = " ORDER BY " + clause.group(1).toLowerCase(Locale.ROOT)
                    + (clause.group(2) == null ? "" : " " + clause.group(2).toUpperCase(Locale.ROOT));
            condition = condition.substring(0, at).strip();
        }
        if (condition.isEmpty()) {
            throw refused("cql has no condition before ORDER BY");
        }
        return "(" + condition + ") AND " + spaceClause + " AND type = page" + order;
    }

    /** The text with every quoted string replaced by blanks of the same length, so positions stay valid. */
    private static String withoutQuoted(String text) {
        StringBuilder out = new StringBuilder(text.length());
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                out.append(' ');
                if (c == '\\') {
                    out.append(' ');
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                out.append(' ');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static int depthAt(String text, int end) {
        int depth = 0;
        for (int i = 0; i < end; i++) {
            if (text.charAt(i) == '(') {
                depth++;
            } else if (text.charAt(i) == ')') {
                depth--;
            }
        }
        return depth;
    }

    private static InvalidToolArgumentException refused(String message) {
        return new InvalidToolArgumentException(message);
    }
}
