package pl.aiwcorpo.mcp.confluence.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.cedar.entities.ResolvedResource;
import pl.aiwcorpo.mcp.confluence.core.domain.PageRecord;
import pl.aiwcorpo.mcp.confluence.core.domain.Space;

/** The scopes the policy decides with: the space, every ancestor page of the tree and the page itself. */
class ConfluenceResourceDescriptionTest {

    private static PageRecord page(String id, String space, List<String> ancestors) {
        return new PageRecord(id, space, "Title", 1, "2026-11-01T10:00:00.000Z", ancestors, List.of());
    }

    @Test
    void aPageCarriesTheScopeOfItsSpaceOfEveryAncestorAndOfItself() {
        ResolvedResource resource = ConfluenceResourceDescription.of(page("1002", "ENG", List.of("1000", "1001")));

        assertThat(resource.scopes()).containsExactly(
                "Confluence::Space/ENG", "Confluence::Page/1000", "Confluence::Page/1001", "Confluence::Page/1002");
        assertThat(resource.parent().orElseThrow().scopes()).containsExactly("Confluence::Space/ENG");
    }

    @Test
    void aRootPageHasNoAncestorScopes() {
        assertThat(ConfluenceResourceDescription.of(page("1000", "ENG", List.of())).scopes())
                .containsExactly("Confluence::Space/ENG", "Confluence::Page/1000");
    }

    @Test
    void anAncestorIdThatCannotBeGrantedIsLeftOutOfTheScopes() {
        ResolvedResource resource = ConfluenceResourceDescription.of(page("1002", "ENG", List.of("abc", "1000")));

        assertThat(resource.scopes()).containsExactly("Confluence::Space/ENG", "Confluence::Page/1000", "Confluence::Page/1002");
    }

    @Test
    void aSpaceCarriesItsOwnScopeAndTheProbeNamesTheKeyWithNoLabels() {
        assertThat(ConfluenceResourceDescription.of(new Space("OPS", "Operations", List.of("restricted"))).scopes())
                .containsExactly("Confluence::Space/OPS");

        ResolvedResource probe = ConfluenceResourceDescription.probeOfSpace("OPS").orElseThrow();

        assertThat(probe.scopes()).containsExactly("Confluence::Space/OPS");
        assertThat(probe.attributes()).containsEntry("labels", List.of());
    }

    @Test
    void aPageJudgedBeforeItIsFetchedLiesInASpaceThatCannotBeGranted() {
        ResolvedResource probe = ConfluenceResourceDescription.pageOutsideScopes("1000").orElseThrow();

        assertThat(probe.id()).isEqualTo("1000");
        assertThat(probe.parent().orElseThrow().id()).isEqualTo(ConfluenceResourceDescription.UNGRANTABLE_SPACE);
        assertThat(probe.scopes()).containsExactly(
                "Confluence::Space/" + ConfluenceResourceDescription.UNGRANTABLE_SPACE, "Confluence::Page/1000");
    }
}
