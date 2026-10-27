package pl.aiwcorpo.mcp.confluence.core.domain;

import java.util.regex.Pattern;

/**
 * Identifier grammars of mcp-contracts ({@code ConfluenceSpaceKey}, {@code ConfluencePageId} in
 * common.schema.json). The same strings go into the tools' input schemas, so they are ECMA-262 and Java
 * compatible.
 */
public final class ConfluenceIds {

    /** Key of a global space. Personal spaces ({@code ~user}) cannot be granted, so they never match. */
    public static final String SPACE_KEY = "^[A-Za-z0-9]{1,255}$";

    /** Content id: positive 64-bit integer as a decimal string. */
    public static final String PAGE_ID = "^[1-9][0-9]{0,18}$";

    private static final Pattern SPACE_KEY_PATTERN = Pattern.compile(SPACE_KEY);
    private static final Pattern PAGE_ID_PATTERN = Pattern.compile(PAGE_ID);

    private ConfluenceIds() {
    }

    public static boolean isSpaceKey(String value) {
        return value != null && SPACE_KEY_PATTERN.matcher(value).matches();
    }

    public static boolean isPageId(String value) {
        return value != null && PAGE_ID_PATTERN.matcher(value).matches();
    }
}
