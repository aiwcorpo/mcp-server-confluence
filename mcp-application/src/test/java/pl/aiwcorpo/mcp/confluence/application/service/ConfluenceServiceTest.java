package pl.aiwcorpo.mcp.confluence.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.confluence.core.domain.Comment;
import pl.aiwcorpo.mcp.confluence.core.domain.ContentHit;
import pl.aiwcorpo.mcp.confluence.core.domain.NewComment;
import pl.aiwcorpo.mcp.confluence.core.domain.NewPage;
import pl.aiwcorpo.mcp.confluence.core.domain.Page;
import pl.aiwcorpo.mcp.confluence.core.domain.PageEdit;
import pl.aiwcorpo.mcp.confluence.core.domain.PageNotFoundException;
import pl.aiwcorpo.mcp.confluence.core.domain.PageRecord;
import pl.aiwcorpo.mcp.confluence.core.domain.PageSummary;
import pl.aiwcorpo.mcp.confluence.core.domain.PageText;
import pl.aiwcorpo.mcp.confluence.core.domain.PageVersion;
import pl.aiwcorpo.mcp.confluence.core.domain.SearchHit;
import pl.aiwcorpo.mcp.confluence.core.domain.Slice;
import pl.aiwcorpo.mcp.confluence.core.domain.WritesNotVerifiedException;
import pl.aiwcorpo.mcp.confluence.core.port.out.ConfluenceResourcePort;

class ConfluenceServiceTest {

    /** The port as a proxy: each test installs only the calls it expects, any other call fails the test. */
    private final Map<String, Function<Object[], Object>> calls = new HashMap<>();
    private final List<String> made = new ArrayList<>();
    private boolean writes = true;
    private final ConfluenceService service = new ConfluenceService(port(), () -> writes);

    private ConfluenceResourcePort port() {
        return (ConfluenceResourcePort) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {ConfluenceResourcePort.class}, (proxy, method, args) -> {
                    Function<Object[], Object> call = calls.get(method.getName());
                    if (call == null) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    made.add(method.getName());
                    return call.apply(args);
                });
    }

    @Test
    void searchKeepsOnlyHitsOfTheNamedSpaceAndPassesTheQueryOn() {
        List<Object> seen = new ArrayList<>();
        calls.put("searchPages", args -> {
            seen.add(args[0]);
            seen.add(args[1]);
            return new Slice<>(List.of(summary("1", "ENG"), summary("2", "HR"), summary("3", "ENG")), 25);
        });

        Slice<PageSummary> found = service.searchPages("ENG", "space = \"ENG\"", 50);

        assertThat(found.items()).extracting(PageSummary::id).containsExactly("1", "3");
        assertThat(found.nextStart()).isEqualTo(25);
        assertThat(seen).containsExactly("space = \"ENG\"", 50);
    }

    @Test
    void aPageThatMovedSinceItWasJudgedIsNotHandedOut() {
        Map<String, Page> pages = Map.of("10", page("10", "ENG", List.of("1")), "11", page("11", "HR", List.of("1")),
                "12", page("12", "ENG", List.of("2")));
        calls.put("findPage", args -> Optional.ofNullable(pages.get(args[0])));

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
        List<NewPage> created = new ArrayList<>();
        calls.put("createPage", args -> {
            created.add((NewPage) args[0]);
            return summary("99", "ENG");
        });

        assertThat(service.createPage("ENG", record("10", "ENG", List.of()), "New", "text").id()).isEqualTo("99");
        assertThatThrownBy(() -> service.createPage("ENG", record("20", "HR", List.of()), "New", "text"))
                .isInstanceOf(PageNotFoundException.class);
        assertThat(created).extracting(NewPage::parentId).containsExactly("10");
    }

    @Test
    void everyWriteIsRefusedWithoutTouchingConfluenceWhenTheVersionIsNotVerified() {
        writes = false;
        PageRecord page = record("10", "ENG", List.of());

        assertThatThrownBy(() -> service.createPage("ENG", page, "T", "b")).isInstanceOf(WritesNotVerifiedException.class);
        assertThatThrownBy(() -> service.updatePage(new PageEdit(page, 1, null, PageEdit.Mode.APPEND, "b")))
                .isInstanceOf(WritesNotVerifiedException.class);
        assertThatThrownBy(() -> service.addComment(page, null, "b")).isInstanceOf(WritesNotVerifiedException.class);
        assertThatThrownBy(() -> service.addLabel(page, "x")).isInstanceOf(WritesNotVerifiedException.class);
        assertThatThrownBy(() -> service.trashPage(page)).isInstanceOf(WritesNotVerifiedException.class);
        assertThat(made).isEmpty();
    }

    @Test
    void aReplyGoesOnlyUnderACommentOfTheSamePage() {
        Map<String, Comment> comments = Map.of("7001", comment("7001", "10"), "7100", comment("7100", "20"));
        calls.put("findComment", args -> Optional.ofNullable(comments.get(args[0])));
        List<NewComment> added = new ArrayList<>();
        calls.put("addComment", args -> {
            added.add((NewComment) args[0]);
            return comment("7999", "10");
        });
        PageRecord page = record("10", "ENG", List.of());

        service.addComment(page, "7001", "ok");
        assertThatThrownBy(() -> service.addComment(page, "7100", "leak")).as("comment of another page")
                .isInstanceOf(PageNotFoundException.class);
        assertThatThrownBy(() -> service.addComment(page, "7555", "leak")).as("no such comment")
                .isInstanceOf(PageNotFoundException.class);
        assertThat(added).extracting(NewComment::parentId).containsExactly("7001");
    }

    @Test
    void commentsOfAnotherPageAreDroppedFromTheWindow() {
        calls.put("listComments", args -> new Slice<>(List.of(comment("1", "10"), comment("2", "20")), null));

        assertThat(service.listComments(record("10", "ENG", List.of()), 0).items()).extracting(Comment::id)
                .containsExactly("1");
    }

    @Test
    void aPageIsTrashedOnlyWhereItWasJudged() {
        calls.put("findPageRecord", args -> Optional.of(record("10", "ENG", List.of("1"))));
        List<Object> trashed = new ArrayList<>();
        calls.put("trashPage", args -> {
            trashed.add(args[0]);
            return null;
        });

        service.trashPage(record("10", "ENG", List.of("1")));
        assertThatThrownBy(() -> service.trashPage(record("10", "ENG", List.of("2")))).as("moved")
                .isInstanceOf(PageNotFoundException.class);
        assertThat(trashed).containsExactly("10");
    }

    @Test
    void anEarlierVersionMustBeInTheJudgedSpace() {
        calls.put("findPageVersion", args -> Optional.of(page("10", "HR", List.of())));

        assertThatThrownBy(() -> service.getPageVersion(record("10", "ENG", List.of()), 1))
                .isInstanceOf(PageNotFoundException.class);
    }

    @Test
    void aContentHitIsKeptOnlyWhereALookupByIdPutsThePageInTheSpaceNow() {
        calls.put("searchContent", args -> new Slice<>(List.of(new ContentHit("1", "one"), new ContentHit("2", "two"),
                new ContentHit("3", "three"), new ContentHit("4", "four")), 25));
        calls.put("findPageRecords", args -> Map.of("1", record("1", "ENG", List.of()), "2", record("2", "HR", List.of()),
                "4", record("4", "eng", List.of())));

        Slice<SearchHit> found = service.searchContent("ENG", "(x) AND space in (\"ENG\") AND type = page", 0);

        assertThat(found.items()).extracting(hit -> hit.page().id()).as("2 is in HR now, 3 is not shown").containsExactly("1", "4");
        assertThat(found.items()).extracting(SearchHit::excerpt).containsExactly("one", "four");
        assertThat(found.nextStart()).isEqualTo(25);
    }

    @Test
    void spacePagesOfAnotherSpaceAreDroppedFromTheTreeWindow() {
        calls.put("listSpacePages", args -> new Slice<>(List.of(record("1", "ENG", List.of()), record("2", "HR", List.of())), 50));

        Slice<PageRecord> window = service.listSpacePages("ENG", 0);

        assertThat(window.items()).extracting(PageRecord::id).containsExactly("1");
        assertThat(window.nextStart()).isEqualTo(50);
    }

    @Test
    void theHistoryIsReadInWindowsOfTwentyNumbersFromTheNewestAndSkipsWhatIsNotShown() {
        List<String> asked = new ArrayList<>();
        calls.put("findPageVersionInfo", args -> {
            asked.add(args[1] + ":" + args[2]);
            return (int) args[1] == 29 ? Optional.<PageVersion>empty()
                    : Optional.of(new PageVersion((int) args[1], "Author", "2026-10-20T10:00:00.000+02:00", ""));
        });
        PageRecord current = new PageRecord("10", "ENG", "Page 10", 30, null, List.of(), List.of());

        Slice<PageVersion> first = service.listPageVersions(current, 0);

        assertThat(first.items()).hasSize(19).extracting(PageVersion::number).first().isEqualTo(30);
        assertThat(first.nextStart()).isEqualTo(10);
        assertThat(asked).first().isEqualTo("30:true");
        assertThat(asked).filteredOn(call -> call.endsWith(":true")).hasSize(1);
        assertThat(service.listPageVersions(current, 10).nextStart()).isNull();
    }

    @Test
    void aDiffOfBodiesLongerThanTheLimitIsReportedNotComputed() {
        String longText = String.join("\n", java.util.Collections.nCopies(ConfluenceService.DIFF_MAX_LINES + 1, "x"));
        calls.put("findPageVersion", args -> Optional.of(new Page("10", "ENG", "Page 10", (int) args[1], null, List.of(),
                new PageText((int) args[1] == 1 ? longText : "short", List.of(), true))));
        PageRecord current = new PageRecord("10", "ENG", "Page 10", 5, null, List.of(), List.of());

        var diff = service.diffPageVersions(current, 1, 2).orElseThrow();

        assertThat(diff.diff()).isEmpty();
        assertThat(diff.omitted()).containsExactly("body_truncated", "diff_too_large");
    }

    @Test
    void aVersionAfterTheJudgedOneDoesNotExist() {
        PageRecord current = new PageRecord("10", "ENG", "Page 10", 5, null, List.of(), List.of());

        assertThat(service.diffPageVersions(current, 6, 1)).isEmpty();
    }

    private static Comment comment(String id, String page) {
        return new Comment(id, page, null, "Author", "2026-10-20T10:00:00.000+02:00", "footer",
                new PageText("text", List.of(), false));
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
}
