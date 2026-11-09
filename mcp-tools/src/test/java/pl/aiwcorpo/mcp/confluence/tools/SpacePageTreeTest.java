package pl.aiwcorpo.mcp.confluence.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.confluence.core.domain.PageRecord;

class SpacePageTreeTest {

    private static PageRecord page(String id, String... ancestors) {
        return new PageRecord(id, "ENG", "Page " + id, 1, null, List.of(ancestors), null);
    }

    @Test
    void childrenFollowTheirParentDepthFirstInTheGivenOrder() {
        var tree = SpacePageTree.build(List.of(page("1"), page("2", "1"), page("3", "1", "2"), page("4", "1"), page("5")), 5, 100);

        assertThat(tree.nodes()).extracting(node -> node.page().id()).containsExactly("1", "2", "3", "4", "5");
        assertThat(tree.nodes()).extracting(SpacePageTree.Node::depth).containsExactly(0, 1, 2, 1, 0);
        assertThat(tree.nodes()).extracting(SpacePageTree.Node::parentId).containsExactly(null, "1", "2", "1", null);
        assertThat(tree.depthCut()).isFalse();
        assertThat(tree.countCut()).isFalse();
    }

    @Test
    void aParentThatIsNotInTheListIsNeverNamed() {
        var tree = SpacePageTree.build(List.of(page("3", "1", "2"), page("4", "1", "2", "3")), 5, 100);

        assertThat(tree.nodes()).extracting(SpacePageTree.Node::parentId).containsExactly(null, "3");
        assertThat(tree.nodes()).extracting(SpacePageTree.Node::depth).containsExactly(0, 1);
    }

    @Test
    void depthAndCountCutsAreReportedSeparately() {
        List<PageRecord> pages = List.of(page("1"), page("2", "1"), page("3", "1", "2"));

        var shallow = SpacePageTree.build(pages, 2, 100);
        assertThat(shallow.nodes()).extracting(node -> node.page().id()).containsExactly("1", "2");
        assertThat(shallow.depthCut()).isTrue();
        assertThat(shallow.countCut()).isFalse();

        var small = SpacePageTree.build(pages, 5, 2);
        assertThat(small.nodes()).hasSize(2);
        assertThat(small.countCut()).isTrue();
        assertThat(small.depthCut()).isFalse();
    }
}
