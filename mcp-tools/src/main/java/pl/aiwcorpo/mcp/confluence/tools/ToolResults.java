package pl.aiwcorpo.mcp.confluence.tools;

import java.util.List;
import org.springframework.lang.Nullable;
import pl.aiwcorpo.mcp.confluence.core.domain.Page;
import pl.aiwcorpo.mcp.confluence.core.domain.PageSummary;
import pl.aiwcorpo.mcp.platform.text.UntrustedText;

/**
 * What the tools return, field by field. Nothing from Confluence reaches the model outside these records. Text
 * written by Confluence users is an {@link UntrustedText} (CC-072); a field Confluence may leave empty is
 * {@code @Nullable}, otherwise the result schema makes it required. No Jackson annotations on the components:
 * the result schema is built from their names.
 */
public final class ToolResults {

    static final int TITLE_MAX = 255;

    private ToolResults() {
    }

    public record PageHit(String id, UntrustedText title, int version, @Nullable String lastUpdated) {

        static PageHit of(PageSummary page) {
            return new PageHit(page.id(), titleText(page.title()), page.version(), page.lastUpdated());
        }
    }

    public record SearchResult(String spaceKey, List<PageHit> pages) {
    }

    /** {@code omittedMacros} sits next to {@code body}: the common {@code UntrustedText} has no such field. */
    public record PageResult(String id, String spaceKey, UntrustedText title, int version,
                             @Nullable String lastUpdated, List<String> ancestorIds, UntrustedText body,
                             List<String> omittedMacros) {

        static PageResult of(Page page) {
            return new PageResult(page.id(), page.spaceKey(), titleText(page.title()), page.version(),
                    page.lastUpdated(), page.ancestorIds(),
                    UntrustedText.plainCut(page.body().text(), page.body().truncated()),
                    page.body().omittedMacros());
        }
    }

    public record CreatedPage(String id, String spaceKey, UntrustedText title, int version) {

        /**
         * A reply without {@code space} must not turn a page that exists into an error: the space asked for is
         * the one the parent was checked against, so it stands in.
         */
        static CreatedPage of(PageSummary page, String requestedSpaceKey) {
            String spaceKey = page.spaceKey() == null ? requestedSpaceKey : page.spaceKey();
            return new CreatedPage(page.id(), spaceKey, titleText(page.title()), page.version());
        }
    }

    private static UntrustedText titleText(String title) {
        return UntrustedText.plain(title == null ? "" : title, TITLE_MAX);
    }
}
