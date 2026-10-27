package pl.aiwcorpo.mcp.confluence.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import pl.aiwcorpo.mcp.confluence.core.domain.Page;
import pl.aiwcorpo.mcp.confluence.core.domain.PageSummary;

/** What the tools return, field by field. Nothing from Confluence reaches the model outside these records. */
public final class ToolResults {

    private ToolResults() {
    }

    public record PageHit(String id, UntrustedText title, int version, String lastUpdated) {

        static PageHit of(PageSummary page) {
            return new PageHit(page.id(), UntrustedText.plain(page.title()), page.version(), page.lastUpdated());
        }
    }

    public record SearchResult(String spaceKey, List<PageHit> pages) {
    }

    /**
     * Text written by Confluence users, handed out as data (UntrustedText of the contracts, CC-072): page titles
     * as much as page bodies. {@code kind} is constant so a client or a reviewer can tell this field apart from
     * the server's own fields; {@code omittedMacros} is this server's addition and only present for bodies.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UntrustedText(String kind, String format, String text, boolean truncated,
                                List<String> omittedMacros) {

        static final String KIND = "untrusted-data";
        static final String PLAIN = "text/plain";

        static UntrustedText plain(String text) {
            return new UntrustedText(KIND, PLAIN, text == null ? "" : text, false, null);
        }
    }

    public record PageResult(String id, String spaceKey, UntrustedText title, int version, String lastUpdated,
                             List<String> ancestorIds, UntrustedText body) {

        static PageResult of(Page page) {
            return new PageResult(page.id(), page.spaceKey(), UntrustedText.plain(page.title()), page.version(),
                    page.lastUpdated(), page.ancestorIds(), new UntrustedText(UntrustedText.KIND,
                            UntrustedText.PLAIN, page.body().text(), page.body().truncated(),
                            page.body().omittedMacros()));
        }
    }

    public record CreatedPage(String id, String spaceKey, UntrustedText title, int version) {

        static CreatedPage of(PageSummary page) {
            return new CreatedPage(page.id(), page.spaceKey(), UntrustedText.plain(page.title()), page.version());
        }
    }
}
