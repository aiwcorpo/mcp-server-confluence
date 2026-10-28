package pl.aiwcorpo.mcp.confluence.integration;

import java.util.Collection;
import java.util.stream.Collectors;
import pl.aiwcorpo.mcp.platform.resource.QueryScope;

/**
 * The one CQL this adapter writes itself: a lookup of pages by id for the resolver. The ids come from the starter
 * after it checked them against the page id pattern, and are quoted anyway. Search queries are built by the tools from
 * structured filters ({@code StructuredQuery}); the model never writes CQL.
 */
final class Cql {

    private Cql() {
    }

    static String contentIn(Collection<String> pageIds) {
        return "id in (" + pageIds.stream().map(QueryScope::quote).collect(Collectors.joining(", ")) + ") AND type = page";
    }
}
