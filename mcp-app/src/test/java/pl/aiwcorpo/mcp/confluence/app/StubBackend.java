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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 *   <li>{@code 2002}: page in HR; {@code 4000}: page in UNCL, whose labels Confluence does not report;
 *       {@code 4001}: page in OPS; {@code 4002}: page in OPS that the search index still lists under ENG; {@code 4003}:
 *       page in MANY, whose label list is longer than one answer;</li>
 *   <li>{@code 1403}: 403; {@code 1500}: 500 echoing the caller's Authorization header; anything else: 404.</li>
 * </ul>
 * Spaces ({@code /rest/api/space/{key}?expand=metadata.labels}): ENG without labels, HR labelled restricted, OPS with two
 * ordinary labels, UNCL without a label list, MANY with a label list that continues on a next page.
 * Search in ENG also returns the blog post, a page of HR, and pages the index lists under ENG although they moved to
 * HR (2002) or OPS (4002), which real Confluence would not: the server must drop them. A lookup of pages by id
 * ({@code id in (...)}) answers with where the pages are now. Creating a page titled "Existing" is 400 (duplicate title), in space LOCKED 403, titled "No space in reply" is answered without the space object.
 * Any request with {@link #EXPIRED_CREDENTIAL} is answered 401, like a revoked token.
 */
final class StubBackend {

    static final String EXPIRED_CREDENTIAL = "Bearer expired-pat-of-jkowalski";
    static final String HR_SECRET = "Salary of jkowalski: 12345 PLN";
    /** Text that must never leave the adapter: it sits in a body the adapter cannot use. */
    static final String BODY_MARKER = "backend-body-marker-5c1d";

    record Request(String method, String path, Map<String, String> query, String authorization, String body) {
    }

    /** A page the stub knows; {@code storage} and {@code view} are what the expands return. */
    private record Fixture(String id, String type, String space, String title, int version, List<String> ancestors,
                           String storage, String view) {
    }

    private static final Map<String, Fixture> FIXTURES = fixtures();

    /** Label names by space key; UNCL has no label list at all (null), the case of a classification Confluence did not report. */
    private static final Map<String, List<String>> SPACE_LABELS = spaceLabels();
    private static final Pattern QUOTED_ID = Pattern.compile("\"(\\d+)\"");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final List<String> elsewhereHits = new CopyOnWriteArrayList<>();
    private HttpServer elsewhere;

    private StubBackend(HttpServer server) {
        this.server = server;
    }

    static StubBackend start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            StubBackend backend = new StubBackend(server);
            server.createContext("/rest/api/content", backend::handle);
            server.createContext("/rest/api/space", backend::handle);
            server.start();
            // Target of the redirects: a client that followed them would show up here with its credential.
            backend.elsewhere = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            backend.elsewhere.createContext("/", exchange -> {
                backend.elsewhereHits.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            backend.elsewhere.start();
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

    List<String> elsewhereHits() {
        return List.copyOf(elsewhereHits);
    }

    void reset() {
        requests.clear();
        elsewhereHits.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String authorization = String.valueOf(exchange.getRequestHeaders().getFirst("Authorization"));
        String path = exchange.getRequestURI().getRawPath();
        Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new Request(exchange.getRequestMethod(), path, query, authorization, body));

        if (EXPIRED_CREDENTIAL.equals(authorization)) {
            respond(exchange, 401, error(401, "Unauthorized"));
        } else if (path.startsWith("/rest/api/space/")) {
            space(exchange, path.substring("/rest/api/space/".length()));
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
        Fixture fixture = FIXTURES.get(id);
        if (fixture != null) {
            respond(exchange, 200, content(fixture, expand));
            return;
        }
        switch (id) {
            case "1600" -> redirect(exchange);
            case "1601" -> respond(exchange, 200, "{\"id\":\"1601\",\"type\":\"page\",\"title\":\"" + BODY_MARKER);
            case "1602" -> respond(exchange, 200, "{\"type\":\"page\",\"status\":\"current\",\"title\":\""
                    + BODY_MARKER + "\"}");
            case "1603" -> respond(exchange, 200, content(new Fixture("1603", "page", "ENG", "Huge", 1, List.of(),
                    "<p>" + "x".repeat(200_000) + "</p>", null), expand));
            case "1604" -> {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            }
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
        if (cql.contains("\"REDIR\"")) {
            redirect(exchange);
            return;
        }
        if (cql.contains("\"BAD\"")) {
            respond(exchange, 400, error(400, "Could not parse cql: " + BODY_MARKER));
            return;
        }
        if (cql.startsWith("id in (")) {
            Matcher ids = QUOTED_ID.matcher(cql);
            while (ids.find()) {
                if ("1500".equals(ids.group(1))) {
                    respond(exchange, 500, "{\"statusCode\":500,\"message\":\"boom\"}");
                    return;
                }
                Fixture fixture = FIXTURES.get(ids.group(1));
                if (fixture != null && "page".equals(fixture.type())) {
                    results.add(contentNode(fixture, expand));
                }
            }
        } else if (cql.contains("space = \"ENG\"")) {
            results.add(listed("1000", "ENG", expand));
            results.add(listed("1001", "ENG", expand));
            results.add(listed("1002", "ENG", expand));
            results.add(listed("2000", "ENG", expand));
            results.add(listed("2002", "ENG", expand));
            results.add(listed("4002", "ENG", expand));
        } else if (cql.contains("space = \"HR\"")) {
            results.add(listed("2000", "HR", expand));
        } else if (cql.contains("space = \"FAIL\"")) {
            results.add(contentNode(new Fixture("1500", "page", "FAIL", "Failing", 1, List.of(), null, null), expand));
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
            List<String> expand = "No space in reply".equals(title)
                    ? List.of("version", "ancestors", "body.storage")
                    : List.of("space", "version", "ancestors", "body.storage");
            respond(exchange, 200, content(new Fixture("5001", "page", space, title, 1, List.of(parent),
                    request.path("body").path("storage").path("value").asString(), null), expand));
        }
    }

    /** A search hit as the index reports it: fixture {@code id}, but in {@code indexedSpace}, whatever its real space. */
    private ObjectNode listed(String id, String indexedSpace, List<String> expand) {
        Fixture real = FIXTURES.get(id);
        return contentNode(new Fixture(id, real.type(), indexedSpace, real.title(), real.version(), List.of(), null, null),
                expand);
    }

    /** The space endpoint: labels live under {@code metadata.labels} when asked for. */
    private void space(HttpExchange exchange, String key) throws IOException {
        List<String> expand = Arrays.asList(query(exchange.getRequestURI().getRawQuery()).getOrDefault("expand", "").split(","));
        if (!SPACE_LABELS.containsKey(key)) {
            respond(exchange, 404, error(404, "No space with key : " + key));
            return;
        }
        List<String> labels = SPACE_LABELS.get(key);
        ObjectNode space = JSON.createObjectNode().put("id", 98306).put("key", key).put("name", key + " space")
                .put("type", "global").put("status", "current");
        if (expand.contains("metadata.labels") && labels != null) {
            ObjectNode listing = space.putObject("metadata").putObject("labels");
            ArrayNode results = listing.putArray("results");
            labels.forEach(label -> results.addObject().put("prefix", "global").put("name", label).put("id", "1")
                    .put("label", label));
            listing.put("start", 0).put("limit", 200).put("size", results.size());
            if ("MANY".equals(key)) {
                listing.putObject("_links").put("next", "/rest/api/space/MANY?start=200");
            }
        } else {
            space.putObject("_expandable").put("metadata", "");
        }
        space.putObject("_links").put("webui", "/display/" + key);
        respond(exchange, 200, space.toString());
    }

    private static Map<String, List<String>> spaceLabels() {
        Map<String, List<String>> labels = new LinkedHashMap<>();
        labels.put("ENG", List.of());
        labels.put("HR", List.of("restricted"));
        labels.put("OPS", List.of("public", "team-x"));
        labels.put("MANY", List.of("a"));
        labels.put("UNCL", null);
        return labels;
    }

    private static Map<String, Fixture> fixtures() {
        Map<String, Fixture> fixtures = new LinkedHashMap<>();
        for (Fixture fixture : List.of(
                new Fixture("1000", "page", "ENG", "Engineering home", 7, List.of(), "<p>Welcome to engineering.</p>", null),
                new Fixture("1001", "page", "ENG", "Release notes", 3, List.of("1000"),
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
                        "<p>Release 2.1 ships on Friday.</p><div class=\"conf-macro\"><p>" + HR_SECRET + "</p></div>"),
                new Fixture("1002", "blogpost", "ENG", "Team news", 1, List.of(), "<p>News</p>", null),
                new Fixture("2000", "page", "HR", "Salaries", 2, List.of(), "<p>" + HR_SECRET + "</p>", null),
                new Fixture("2002", "page", "HR", "Bonus rules", 1, List.of(), "<p>" + HR_SECRET + "</p>", null),
                new Fixture("3000", "page", "~jkowalski", "My notes", 1, List.of(), "<p>private</p>", null),
                new Fixture("4000", "page", "UNCL", "Unlabelled", 1, List.of(), "<p>no labels known</p>", null),
                new Fixture("4001", "page", "OPS", "Runbook", 4, List.of("4002"), "<p>Restart the job.</p>", null),
                new Fixture("4002", "page", "OPS", "Operations home", 2, List.of(), "<p>Operations.</p>", null),
                new Fixture("4003", "page", "MANY", "Many labels", 1, List.of(), "<p>unclear</p>", null))) {
            fixtures.put(fixture.id(), fixture);
        }
        return fixtures;
    }

    /** Content JSON as Confluence DC returns it: only the expanded parts in full, the rest in _expandable. */
    private String content(Fixture fixture, List<String> expand) {
        return contentNode(fixture, expand).toString();
    }

    private ObjectNode contentNode(Fixture fixture, List<String> expand) {
        String id = fixture.id();
        String type = fixture.type();
        String space = fixture.space();
        String title = fixture.title();
        int version = fixture.version();
        List<String> ancestors = fixture.ancestors();
        String storage = fixture.storage();
        String view = fixture.view();
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

    private void redirect(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + elsewhere.getAddress().getPort()
                + "/login?note=" + BODY_MARKER);
        respond(exchange, 302, "<html>" + BODY_MARKER + "</html>");
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
