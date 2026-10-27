package pl.aiwcorpo.mcp.confluence.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.confluence.core.domain.NewPage;
import pl.aiwcorpo.mcp.confluence.core.domain.Page;
import pl.aiwcorpo.mcp.confluence.core.domain.PageNotFoundException;
import pl.aiwcorpo.mcp.confluence.core.domain.PageSummary;
import pl.aiwcorpo.mcp.confluence.core.domain.PageText;
import pl.aiwcorpo.mcp.confluence.core.port.out.ConfluenceResourcePort;

class ConfluenceServiceTest {

    private final InMemoryConfluence confluence = new InMemoryConfluence();
    private final ConfluenceService service = new ConfluenceService(confluence);

    @Test
    void searchKeepsOnlyHitsOfTheNamedSpace() {
        confluence.hits = List.of(summary("1", "ENG"), summary("2", "HR"), summary("3", "ENG"));

        assertThat(service.searchPages("ENG", "release")).extracting(PageSummary::id).containsExactly("1", "3");
        assertThat(confluence.searchedSpace).isEqualTo("ENG");
    }

    @Test
    void aPageOfAPersonalSpaceIsNotHandedOut() {
        confluence.pages = Map.of("10", page("10", "ENG"), "11", page("11", "~jkowalski"));

        assertThat(service.getPage("10").title()).isEqualTo("Page 10");
        assertThatThrownBy(() -> service.getPage("11")).isInstanceOf(PageNotFoundException.class);
        assertThatThrownBy(() -> service.getPage("12")).isInstanceOf(PageNotFoundException.class);
    }

    @Test
    void aPageIsCreatedOnlyUnderAParentOfTheSameSpace() {
        confluence.pages = Map.of("10", page("10", "ENG"), "20", page("20", "HR"));

        assertThat(service.createPage("ENG", "10", "New", "text").spaceKey()).isEqualTo("ENG");
        assertThatThrownBy(() -> service.createPage("ENG", "20", "New", "text"))
                .isInstanceOf(PageNotFoundException.class);
        assertThatThrownBy(() -> service.createPage("ENG", "30", "New", "text"))
                .isInstanceOf(PageNotFoundException.class);
        assertThat(confluence.created).extracting(NewPage::parentId).containsExactly("10");
    }

    private static PageSummary summary(String id, String space) {
        return new PageSummary(id, space, "Page " + id, 1, "2026-10-20T10:00:00.000+02:00");
    }

    private static Page page(String id, String space) {
        return new Page(id, space, "Page " + id, 1, "2026-10-20T10:00:00.000+02:00", List.of(),
                new PageText("body", List.of(), false));
    }

    private static final class InMemoryConfluence implements ConfluenceResourcePort {

        List<PageSummary> hits = List.of();
        Map<String, Page> pages = Map.of();
        String searchedSpace;
        final List<NewPage> created = new ArrayList<>();

        @Override
        public List<PageSummary> searchPages(String spaceKey, String text, int limit) {
            searchedSpace = spaceKey;
            return hits;
        }

        @Override
        public Optional<Page> findPage(String pageId) {
            return Optional.ofNullable(pages.get(pageId));
        }

        @Override
        public Optional<PageSummary> findPageSummary(String pageId) {
            return findPage(pageId).map(p -> new PageSummary(p.id(), p.spaceKey(), p.title(), p.version(),
                    p.lastUpdated()));
        }

        @Override
        public PageSummary createPage(NewPage page) {
            created.add(page);
            return new PageSummary("99", page.spaceKey(), page.title(), 1, "2026-10-27T12:00:00.000+01:00");
        }
    }
}
