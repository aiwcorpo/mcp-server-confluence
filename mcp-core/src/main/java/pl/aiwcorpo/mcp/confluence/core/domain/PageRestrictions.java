package pl.aiwcorpo.mcp.confluence.core.domain;

import java.util.List;

/**
 * Who may view or edit a page, as set on that page itself. Restrictions of ancestor pages also apply in Confluence
 * but are not part of this page's answer. People are named by display name only; no e-mail address or user key is
 * kept.
 */
public record PageRestrictions(Subjects read, Subjects update) {

    /**
     * @param users     display names of the users the operation is restricted to
     * @param groups    names of the groups the operation is restricted to
     * @param truncated Confluence listed fewer names than there are, or the list was cut at the server's limit
     */
    public record Subjects(List<String> users, List<String> groups, boolean truncated) {

        public Subjects {
            users = List.copyOf(users);
            groups = List.copyOf(groups);
        }

        public static Subjects none() {
            return new Subjects(List.of(), List.of(), false);
        }
    }
}
