package pl.aiwcorpo.mcp.confluence.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.confluence.core.domain.NewPage;
import pl.aiwcorpo.mcp.confluence.core.domain.Page;
import pl.aiwcorpo.mcp.confluence.core.domain.PageNotFoundException;
import pl.aiwcorpo.mcp.confluence.core.domain.PageRecord;
import pl.aiwcorpo.mcp.confluence.core.domain.PageSummary;
import pl.aiwcorpo.mcp.confluence.core.domain.PageText;
import pl.aiwcorpo.mcp.confluence.core.domain.Space;
import pl.aiwcorpo.mcp.confluence.core.port.out.ConfluenceResourcePort;

class ConfluenceServiceTest {

    private final InMemoryConfluence confluence = new InMemoryConfluence();
    private final ConfluenceService service = new ConfluenceService(confluence);

    @Test
    void searchKeepsOnlyHitsOfTheNamedSpaceAndPassesTheQueryOn() {
        confluence.hits = List.of(summary("1", "ENG"), summary("2", "HR"), summary("3", "ENG"));

        assertThat(service.searchPages("ENG", "space = \"ENG\"")).extracting(PageSummary::id).containsExactly("1", "3");
        assertThat(confluence.searchedQuery).isEqualTo("space = \"ENG\"");
    }

    @Test
    void aPageThatMovedSinceItWasJudgedIsNotHandedOut() {
        confluence.pages = Map.of("10", page("10", "ENG", List.of("1")), "11", page("11", "HR", List.of("1")),
                "12", page("12", "ENG", List.of("2")));

        assertThat(service.getPage(record("10", "ENG", List.of("1"))).title()).isEqualTo("Page 10");
        assertThatThrownBy(() -> service.getPage(record("11", "ENG", List.of("1")))).as("other space")
                .isInstanceOf(PageNotFoundException.class);
        assertThatThrownBy(() -> service.getPage(record("12", "ENG", List.of("1")))).as("other tree")
                .isInstanceOf(PageNotFoundException.class);
        assertThatThrownBy(() -> service.getPage(record("13", "ENG", List.of()))).as("gone")
                .isInstanceOf(PageNotFoundException.class);
    }

    @Test
    void aPageIsCreatedOnlyUnderAParentOfTheSameSpace() {
        assertThat(service.createPage("ENG", record("10", "ENG", List.of()), "New", "text").spaceKey()).isEqualTo("ENG");
        assertThatThrownBy(() -> service.createPage("ENG", record("20", "HR", List.of()), "New", "text"))
                .isInstanceOf(PageNotFoundException.class);
        assertThat(confluence.created).extracting(NewPage::parentId).containsExactly("10");
    }

    private static PageSummary summary(String id, String space) {
        return new PageSummary(id, space, "Page " + id, 1, "2026-10-20T10:00:00.000+02:00");
    }

    private static PageRecord record(String id, String space, List<String> ancestors) {
        return new PageRecord(id, space, "Page " + id, 1, "2026-10-20T10:00:00.000+02:00", ancestors, List.of());
    }

    private static Page page(String id, String space, List<String> ancestors) {
        return new Page(id, space, "Page " + id, 1, "2026-10-20T10:00:00.000+02:00", ancestors,
                new PageText("body", List.of(), false));
    }

    private static final class InMemoryConfluence implements ConfluenceResourcePort {

        List<PageSummary> hits = List.of();
        Map<String, Page> pages = Map.of();
        String searchedQuery;
        final List<NewPage> created = new ArrayList<>();

        @Override
        public List<PageSummary> searchPages(String cql, int limit) {
            searchedQuery = cql;
            return hits;
        }

        @Override
        public Optional<PageRecord> findPageRecord(String pageId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, PageRecord> findPageRecords(Collection<String> pageIds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Space> findSpace(String spaceKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Page> findPage(String pageId) {
            return Optional.ofNullable(pages.get(pageId));
        }

        @Override
        public PageSummary createPage(NewPage page) {
            created.add(page);
            return new PageSummary("99", page.spaceKey(), page.title(), 1, "2026-10-27T12:00:00.000+01:00");
        }
    }
}
