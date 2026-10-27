package pl.aiwcorpo.mcp.confluence.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Confluence Data Center in tests: a real HTTP server on loopback answering {@code /rest/api/content} the way
 * Confluence 8/9 does (content JSON with {@code expand}, {@code _links}, {@code _expandable}; error bodies with
 * {@code statusCode}, {@code message}), so the adapter runs with its real client, timeouts and error mapping.
 *
 * <p>Content it knows:
 * <ul>
 *   <li>{@code 1000}: page "Engineering home" in ENG;</li>
 *   <li>{@code 1001}: page "Release notes" in ENG under 1000; its storage has an include of HR:Salaries and an
 *       excerpt-include, and its {@code body.view} (sent only when asked for) has that HR content expanded;</li>
 *   <li>{@code 1002}: a blog post in ENG;</li>
 *   <li>{@code 2000}: page in HR; {@code 3000}: page in the personal space {@code ~jkowalski};</li>
 *   <li>{@code 1403}: 403; {@code 1500}: 500 echoing the caller's Authorization header; anything else: 404.</li>
 * </ul>
 * Search in ENG also returns the blog post and a page of HR, which real Confluence would not: the server must
 * drop them. Creating a page titled "Existing" is 400 (duplicate title), in space LOCKED 403.
 * Any request with {@link #EXPIRED_CREDENTIAL} is answered 401, like a revoked token.
 */
final class StubBackend {

    static final String EXPIRED_CREDENTIAL = "Bearer expired-pat-of-jkowalski";
    static final String HR_SECRET = "Salary of jkowalski: 12345 PLN";

    record Request(String method, String path, Map<String, String> query, String authorization, String body) {
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    private StubBackend(HttpServer server) {
        this.server = server;
    }

    static StubBackend start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            StubBackend backend = new StubBackend(server);
            server.createContext("/rest/api/content", backend::handle);
            server.start();
            return backend;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<Request> requests() {
        return List.copyOf(requests);
    }

    /** Authorization headers received so far, in order. */
    List<String> authorizations() {
        return requests.stream().map(Request::authorization).toList();
    }

    void reset() {
        requests.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String authorization = String.valueOf(exchange.getRequestHeaders().getFirst("Authorization"));
        String path = exchange.getRequestURI().getRawPath();
        Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new Request(exchange.getRequestMethod(), path, query, authorization, body));

        if (EXPIRED_CREDENTIAL.equals(authorization)) {
            respond(exchange, 401, error(401, "Unauthorized"));
        } else if ("POST".equals(exchange.getRequestMethod()) && "/rest/api/content".equals(path)) {
            create(exchange, JSON.readTree(body));
        } else if ("/rest/api/content/search".equals(path)) {
            search(exchange, query);
        } else {
            get(exchange, path.substring("/rest/api/content/".length()), query, authorization);
        }
    }

    private void get(HttpExchange exchange, String id, Map<String, String> query, String authorization)
            throws IOException {
        List<String> expand = Arrays.asList(query.getOrDefault("expand", "").split(","));
        switch (id) {
            case "1000" -> respond(exchange, 200, content("1000", "page", "ENG", "Engineering home", 7, List.of(),
                    "<p>Welcome to engineering.</p>", null, expand));
            case "1001" -> respond(exchange, 200, content("1001", "page", "ENG", "Release notes", 3, List.of("1000"),
                    """
                    <p>Release 2.1 ships on Friday.</p>\
                    <ac:structured-macro ac:name="include" ac:schema-version="1" ac:macro-id="a1">\
                    <ac:parameter ac:name=""><ac:link><ri:page ri:space-key="HR" ri:content-title="Salaries" />\
                    </ac:link></ac:parameter></ac:structured-macro>\
                    <ac:structured-macro ac:name="excerpt-include" ac:schema-version="1">\
                    <ac:parameter ac:name="nopanel">true</ac:parameter>\
                    <ac:parameter ac:name=""><ac:link><ri:page ri:space-key="HR" ri:content-title="Bonuses" />\
                    </ac:link></ac:parameter></ac:structured-macro>\
                    <ac:structured-macro ac:name="info"><ac:rich-text-body><p>Freeze starts Thursday.</p>\
                    </ac:rich-text-body></ac:structured-macro>""",
                    "<p>Release 2.1 ships on Friday.</p><div class=\"conf-macro\"><p>" + HR_SECRET + "</p></div>",
                    expand));
            case "1002" -> respond(exchange, 200, content("1002", "blogpost", "ENG", "Team news", 1, List.of(),
                    "<p>News</p>", null, expand));
            case "2000" -> respond(exchange, 200, content("2000", "page", "HR", "Salaries", 2, List.of(),
                    "<p>" + HR_SECRET + "</p>", null, expand));
            case "3000" -> respond(exchange, 200, content("3000", "page", "~jkowalski", "My notes", 1, List.of(),
                    "<p>private</p>", null, expand));
            case "1403" -> respond(exchange, 403, error(403, "Forbidden"));
            case "1500" -> respond(exchange, 500, "{\"statusCode\":500,\"message\":\"boom\",\"seen\":\""
                    + authorization + "\"}");
            default -> respond(exchange, 404, error(404, "No content found with id: ContentId{id=" + id + "}"));
        }
    }

    private void search(HttpExchange exchange, Map<String, String> query) throws IOException {
        String cql = query.getOrDefault("cql", "");
        List<String> expand = Arrays.asList(query.getOrDefault("expand", "").split(","));
        ObjectNode response = JSON.createObjectNode();
        ArrayNode results = response.putArray("results");
        if (cql.startsWith("space = \"ENG\"")) {
            results.add(contentNode("1000", "page", "ENG", "Engineering home", 7, List.of(), null, null, expand));
            results.add(contentNode("1001", "page", "ENG", "Release notes", 3, List.of(), null, null, expand));
            results.add(contentNode("1002", "blogpost", "ENG", "Team news", 1, List.of(), null, null, expand));
            results.add(contentNode("2000", "page", "HR", "Salaries", 2, List.of(), null, null, expand));
        }
        response.put("start", 0).put("limit", Integer.parseInt(query.getOrDefault("limit", "25")))
                .put("size", results.size());
        response.putObject("_links").put("base", baseUrl()).put("context", "")
                .put("self", baseUrl() + "/rest/api/content/search");
        respond(exchange, 200, response.toString());
    }

    private void create(HttpExchange exchange, JsonNode request) throws IOException {
        String space = request.path("space").path("key").asString();
        String title = request.path("title").asString();
        if ("LOCKED".equals(space)) {
            respond(exchange, 403, error(403, "Not permitted to create page in space LOCKED"));
        } else if ("Existing".equals(title)) {
            respond(exchange, 400, error(400, "A page with this title already exists: A page already exists "
                    + "with the title Existing in the space with key " + space));
        } else {
            String parent = request.path("ancestors").path(0).path("id").asString();
            respond(exchange, 200, content("5001", "page", space, title, 1, List.of(parent),
                    request.path("body").path("storage").path("value").asString(), null,
                    List.of("space", "version", "ancestors", "body.storage")));
        }
    }

    /** Content JSON as Confluence DC returns it: only the expanded parts in full, the rest in _expandable. */
    private String content(String id, String type, String space, String title, int version, List<String> ancestors,
            String storage, String view, List<String> expand) {
        return contentNode(id, type, space, title, version, ancestors, storage, view, expand).toString();
    }

    private ObjectNode contentNode(String id, String type, String space, String title, int version,
            List<String> ancestors, String storage, String view, List<String> expand) {
        ObjectNode content = JSON.createObjectNode()
                .put("id", id).put("type", type).put("status", "current").put("title", title);
        ObjectNode expandable = JSON.createObjectNode();
        if (expand.contains("space")) {
            ObjectNode spaceNode = content.putObject("space").put("id", 98306).put("key", space)
                    .put("name", space + " space").put("type", space.startsWith("~") ? "personal" : "global")
                    .put("status", "current");
            spaceNode.putObject("_links").put("webui", "/display/" + space);
        } else {
            expandable.put("space", "/rest/api/space/" + space);
        }
        if (expand.contains("version")) {
            ObjectNode versionNode = content.putObject("version");
            versionNode.putObject("by").put("type", "known").put("username", "admin").put("displayName", "Admin");
            versionNode.put("when", "2026-10-20T10:15:30.000+02:00").put("message", "").put("number", version)
                    .put("minorEdit", false).put("hidden", false);
        } else {
            expandable.put("version", "");
        }
        if (expand.contains("ancestors")) {
            ArrayNode ancestorNodes = content.putArray("ancestors");
            ancestors.forEach(ancestor -> ancestorNodes.addObject().put("id", ancestor).put("type", "page")
                    .put("status", "current").put("title", "Ancestor " + ancestor));
        } else {
            expandable.put("ancestors", "");
        }
        ObjectNode bodyNode = JSON.createObjectNode();
        ObjectNode bodyExpandable = bodyNode.putObject("_expandable");
        if (expand.contains("body.storage") && storage != null) {
            bodyNode.putObject("storage").put("value", storage).put("representation", "storage");
        } else {
            bodyExpandable.put("storage", "");
        }
        if (expand.contains("body.view") && view != null) {
            bodyNode.putObject("view").put("value", view).put("representation", "view");
        } else {
            bodyExpandable.put("view", "");
        }
        bodyExpandable.put("export_view", "").put("styled_view", "").put("editor", "");
        if (expand.stream().anyMatch(e -> e.startsWith("body."))) {
            content.set("body", bodyNode);
        } else {
            expandable.put("body", "");
        }
        content.putObject("_links").put("webui", "/pages/viewpage.action?pageId=" + id)
                .put("tinyui", "/x/" + id).put("self", baseUrl() + "/rest/api/content/" + id);
        expandable.put("children", "/rest/api/content/" + id + "/child")
                .put("restrictions", "/rest/api/content/" + id + "/restriction/byOperation")
                .put("history", "/rest/api/content/" + id + "/history");
        content.set("_expandable", expandable);
        return content;
    }

    private static String error(int status, String message) {
        ObjectNode error = JSON.createObjectNode().put("statusCode", status);
        error.putObject("data").put("authorized", false).put("valid", true).put("successful", false)
                .putArray("errors");
        return error.put("message", message).toString();
    }

    private static Map<String, String> query(String rawQuery) {
        Map<String, String> query = new LinkedHashMap<>();
        if (rawQuery == null) {
            return query;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            query.put(name, value);
        }
        return query;
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
