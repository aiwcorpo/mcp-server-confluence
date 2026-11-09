package pl.aiwcorpo.mcp.confluence.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import pl.aiwcorpo.mcp.confluence.core.domain.PageRecord;

/**
 * Arranges the pages of one space, as a flat list from Confluence, into tree order (a parent, then its children in
 * Confluence's order, depth first). A page whose parent is not among {@code pages} (it was hidden by the policy or
 * lies beyond what was read) is a root of its own fragment: its real parent id never reaches the result.
 */
final class SpacePageTree {

    record Node(PageRecord page, String parentId, int depth) {
    }

    /**
     * @param depthCut pages below {@code maxDepth} were left out
     * @param countCut pages beyond {@code maxPages} were left out
     */
    record Result(List<Node> nodes, boolean depthCut, boolean countCut) {
    }

    private SpacePageTree() {
    }

    /** @param maxDepth number of levels kept: 1 keeps only the roots */
    static Result build(List<PageRecord> pages, int maxDepth, int maxPages) {
        Map<String, PageRecord> byId = new LinkedHashMap<>();
        pages.forEach(page -> byId.putIfAbsent(page.id(), page));
        Map<String, List<PageRecord>> children = new HashMap<>();
        List<PageRecord> roots = new ArrayList<>();
        for (PageRecord page : byId.values()) {
            String parentId = parentOf(page, byId);
            if (parentId == null) {
                roots.add(page);
            } else {
                children.computeIfAbsent(parentId, id -> new ArrayList<>()).add(page);
            }
        }
        List<Node> nodes = new ArrayList<>();
        boolean[] cuts = new boolean[2];
        for (PageRecord root : roots) {
            visit(root, null, 0, children, maxDepth, maxPages, nodes, cuts);
        }
        return new Result(nodes, cuts[0], cuts[1]);
    }

    private static void visit(PageRecord page, String parentId, int depth, Map<String, List<PageRecord>> children,
            int maxDepth, int maxPages, List<Node> nodes, boolean[] cuts) {
        if (depth >= maxDepth) {
            cuts[0] = true;
            return;
        }
        if (nodes.size() >= maxPages) {
            cuts[1] = true;
            return;
        }
        nodes.add(new Node(page, parentId, depth));
        for (PageRecord child : children.getOrDefault(page.id(), List.of())) {
            visit(child, page.id(), depth + 1, children, maxDepth, maxPages, nodes, cuts);
        }
    }

    private static String parentOf(PageRecord page, Map<String, PageRecord> byId) {
        if (page.ancestorIds().isEmpty()) {
            return null;
        }
        String parent = page.ancestorIds().getLast();
        return byId.containsKey(parent) && !parent.equals(page.id()) ? parent : null;
    }
}
