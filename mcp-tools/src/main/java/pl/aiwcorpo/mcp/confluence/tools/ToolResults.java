package pl.aiwcorpo.mcp.confluence.tools;

import java.util.List;
import pl.aiwcorpo.mcp.confluence.core.domain.Page;
import pl.aiwcorpo.mcp.confluence.core.domain.PageSummary;

/** What the tools return, field by field. Nothing from Confluence reaches the model outside these records. */
public final class ToolResults {

    private ToolResults() {
    }

    public record PageHit(String id, String title, int version, String lastUpdated) {

        static PageHit of(PageSummary page) {
            return new PageHit(page.id(), page.title(), page.version(), page.lastUpdated());
        }
    }

    public record SearchResult(String spaceKey, List<PageHit> pages) {
    }

    /**
     * Text written by Confluence users, handed out as data. {@code kind} is constant so a client or a reviewer
     * can tell this field apart from the server's own fields.
     */
    public record UntrustedText(String kind, String format, String text, List<String> omittedMacros,
                                boolean truncated) {
    }

    public record PageResult(String id, String spaceKey, String title, int version, String lastUpdated,
                             List<String> ancestorIds, UntrustedText body) {

        static PageResult of(Page page) {
            return new PageResult(page.id(), page.spaceKey(), page.title(), page.version(), page.lastUpdated(),
                    page.ancestorIds(), new UntrustedText("untrusted-data", "text/plain", page.body().text(),
                            page.body().omittedMacros(), page.body().truncated()));
        }
    }

    public record CreatedPage(String id, String spaceKey, String title, int version) {

        static CreatedPage of(PageSummary page) {
            return new CreatedPage(page.id(), page.spaceKey(), page.title(), page.version());
        }
    }
}
