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
import java.io.InputStream;
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

    /** Version profile answered by the probe endpoints; one directory under {@code fixtures/} per release. */
    private volatile String profile = "confluence-dc-10.2";

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final List<String> probeAuthorizations = new CopyOnWriteArrayList<>();
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
            server.createContext("/rest/api/search", backend::siteSearch);
            server.createContext("/rest/api/server-information", backend::probe);
            server.createContext("/rest/applinks/1.0/manifest", backend::probe);
            server.createContext("/download/attachments", backend::download);
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

    /** Switches the release the stub pretends to be (a directory name under {@code fixtures/}). */
    void profile(String name) {
        this.profile = name;
    }

    private String fixture(String file) {
        try (InputStream in = StubBackend.class.getResourceAsStream("/fixtures/" + profile + "/" + file)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    List<Request> requests() {
        return List.copyOf(requests);
    }

    /** Authorization headers received so far, in order. */
    List<String> authorizations() {
        return requests.stream().map(Request::authorization).toList();
    }

    /** Authorization headers of the version probe's requests: the probe has no user, so none is sent. */
    List<String> probeAuthorizations() {
        return List.copyOf(probeAuthorizations);
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
        } else if ("/rest/api/space".equals(path)) {
            spaces(exchange, exchange.getRequestURI().getRawQuery());
        } else if (path.startsWith("/rest/api/space/")) {
            space(exchange, path.substring("/rest/api/space/".length()));
        } else if ("POST".equals(exchange.getRequestMethod()) && "/rest/api/content".equals(path)) {
            create(exchange, JSON.readTree(body));
        } else if ("/rest/api/content".equals(path)) {
            listContent(exchange, query);
        } else if ("/rest/api/content/search".equals(path)) {
            search(exchange, query);
        } else {
            String rest = path.substring("/rest/api/content/".length());
            String[] parts = rest.split("/");
            String method = exchange.getRequestMethod();
            if (parts.length == 1 && "PUT".equals(method)) {
                update(exchange, parts[0], JSON.readTree(body));
            } else if (parts.length == 1 && "DELETE".equals(method)) {
                delete(exchange, parts[0]);
            } else if (parts.length == 3 && "child".equals(parts[1]) && "page".equals(parts[2])) {
                children(exchange, parts[0], query);
            } else if (parts.length == 3 && "child".equals(parts[1]) && "comment".equals(parts[2])) {
                comments(exchange, parts[0], query);
            } else if (parts.length == 3 && "child".equals(parts[1]) && "attachment".equals(parts[2])) {
                attachments(exchange, parts[0], query);
            } else if (parts.length == 2 && "label".equals(parts[1])) {
                labels(exchange, parts[0], method, body);
            } else {
                get(exchange, rest, query, authorization);
            }
        }
    }


    // ---- probe, lists, comments, labels, attachments, writes ---------------------------------------------------

    /** {@code server-information} and the applinks manifest of the current profile; a missing file is 403. */
    private void probe(HttpExchange exchange) throws IOException {
        probeAuthorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
        boolean manifest = exchange.getRequestURI().getRawPath().contains("applinks");
        if (!manifest && fixture("server-information.redirect") != null) {
            exchange.getResponseHeaders().set("Location", baseUrl() + "/login.action");
            respond(exchange, 302, "<html>login</html>");
            return;
        }
        String body = fixture(manifest ? "manifest.xml" : "server-information.json");
        if (body == null) {
            respond(exchange, 403, error(403, "Forbidden"));
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", manifest ? "application/xml" : "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void listContent(HttpExchange exchange, Map<String, String> query) throws IOException {
        List<String> expand = Arrays.asList(query.getOrDefault("expand", "").split(","));
        String spaceKey = query.get("spaceKey");
        ObjectNode response = JSON.createObjectNode();
        ArrayNode results = response.putArray("results");
        String title = query.get("title");
        for (Fixture fixture : FIXTURES.values()) {
            if (spaceKey != null && !spaceKey.equals(fixture.space())) {
                continue;
            }
            if (title != null && !title.equalsIgnoreCase(fixture.title())) {
                continue;
            }
            if ("page".equals(query.get("type")) && !"page".equals(fixture.type())) {
                continue;
            }
            results.add(contentNode(fixture, expand));
        }
        window(exchange, response, query, "/rest/api/content");
    }

    /** Applies {@code start} and {@code limit} to the results array and adds a next link when more remain. */
    private void window(HttpExchange exchange, ObjectNode response, Map<String, String> query, String self)
            throws IOException {
        int start = Integer.parseInt(query.getOrDefault("start", "0"));
        int limit = Integer.parseInt(query.getOrDefault("limit", "25"));
        ArrayNode all = (ArrayNode) response.get("results");
        ArrayNode cut = JSON.createArrayNode();
        for (int i = start; i < Math.min(all.size(), start + limit); i++) {
            cut.add(all.get(i));
        }
        response.set("results", cut);
        response.put("start", start).put("limit", limit).put("size", cut.size());
        ObjectNode links = response.putObject("_links");
        links.put("self", baseUrl() + self);
        if (start + limit < all.size()) {
            links.put("next", self + "?start=" + (start + limit) + "&limit=" + limit);
        }
        respond(exchange, 200, response.toString());
    }

    private void children(HttpExchange exchange, String id, Map<String, String> query) throws IOException {
        List<String> expand = Arrays.asList(query.getOrDefault("expand", "").split(","));
        if (!FIXTURES.containsKey(id)) {
            respond(exchange, 404, error(404, "No content found with id: ContentId{id=" + id + "}"));
            return;
        }
        ObjectNode response = JSON.createObjectNode();
        ArrayNode results = response.putArray("results");
        if ("1700".equals(id)) {
            // A confused backend: a page that is not below 1700 comes back among its children.
            results.add(contentNode(FIXTURES.get("1000"), expand));
        }
        for (Fixture fixture : FIXTURES.values()) {
            if ("page".equals(fixture.type()) && !fixture.ancestors().isEmpty()
                    && fixture.ancestors().getLast().equals(id)) {
                results.add(contentNode(fixture, expand));
            }
        }
        window(exchange, response, query, "/rest/api/content/" + id + "/child/page");
    }

    private record CommentFixture(String id, String page, String parent, String author, String location, String storage) {
    }

    private static final Map<String, CommentFixture> COMMENTS = comments();

    private static Map<String, CommentFixture> comments() {
        Map<String, CommentFixture> comments = new LinkedHashMap<>();
        for (CommentFixture comment : List.of(
                new CommentFixture("7001", "1000", null, "Anna Nowak", "footer", "<p>Looks good.</p>"),
                new CommentFixture("7002", "1000", "7001", "Jan Kowalski", "footer", "<p>Agreed.</p>"),
                new CommentFixture("7003", "1000", null, "Anna Nowak", "inline",
                        "<p>Typo here.</p><ac:structured-macro ac:name=\"include\"><ac:parameter ac:name=\"\">"
                                + "<ac:link><ri:page ri:space-key=\"HR\" ri:content-title=\"Salaries\" /></ac:link>"
                                + "</ac:parameter></ac:structured-macro>"),
                new CommentFixture("7004", "1000", null, "No Container", "footer", "<p>orphan</p>"),
                new CommentFixture("7100", "2000", null, "HR Admin", "footer", "<p>" + HR_SECRET + "</p>"))) {
            comments.put(comment.id(), comment);
        }
        return comments;
    }

    private ObjectNode comment(CommentFixture comment, List<String> expand) {
        ObjectNode node = JSON.createObjectNode().put("id", comment.id()).put("type", "comment")
                .put("status", "current").put("title", "Re: page " + comment.page());
        if (!"7004".equals(comment.id())) {
            node.putObject("container").put("id", comment.page()).put("type", "page");
        }
        ArrayNode ancestors = node.putArray("ancestors");
        if (comment.parent() != null) {
            ancestors.addObject().put("id", comment.parent()).put("type", "comment");
        }
        ObjectNode version = node.putObject("version");
        version.putObject("by").put("type", "known").put("displayName", comment.author());
        version.put("when", "2026-10-21T08:00:00.000+02:00").put("number", 1);
        node.putObject("body").putObject("storage").put("value", comment.storage()).put("representation", "storage");
        node.putObject("extensions").put("location", comment.location());
        return node;
    }

    private void comments(HttpExchange exchange, String pageId, Map<String, String> query) throws IOException {
        if (!FIXTURES.containsKey(pageId)) {
            respond(exchange, 404, error(404, "No content found with id: ContentId{id=" + pageId + "}"));
            return;
        }
        ObjectNode response = JSON.createObjectNode();
        ArrayNode results = response.putArray("results");
        COMMENTS.values().stream().filter(c -> c.page().equals(pageId))
                .forEach(c -> results.add(comment(c, List.of())));
        window(exchange, response, query, "/rest/api/content/" + pageId + "/child/comment");
    }

    private static final Map<String, List<String>> PAGE_LABELS = Map.of("1000", List.of("handbook", "release"),
            "1700", java.util.stream.IntStream.range(0, 200).mapToObj(i -> "label-" + i).toList());

    private void labels(HttpExchange exchange, String pageId, String method, String body) throws IOException {
        if (!FIXTURES.containsKey(pageId)) {
            respond(exchange, 404, error(404, "No content found with id: ContentId{id=" + pageId + "}"));
            return;
        }
        ObjectNode response = JSON.createObjectNode();
        ArrayNode results = response.putArray("results");
        if ("POST".equals(method)) {
            JSON.readTree(body).forEach(label -> results.addObject().put("prefix", label.path("prefix").asString())
                    .put("name", label.path("name").asString()));
        } else {
            PAGE_LABELS.getOrDefault(pageId, List.of()).forEach(name -> results.addObject().put("prefix", "global")
                    .put("name", name).put("id", "1").put("label", name));
            if ("1000".equals(pageId)) {
                results.addObject().put("prefix", "my").put("name", "private-note").put("id", "2").put("label", "my:private-note");
            }
        }
        response.put("start", 0).put("limit", 200).put("size", results.size());
        respond(exchange, 200, response.toString());
    }

    private record AttachmentFixture(String id, String page, String title, String mediaType, long size, String text) {
    }

    private static final Map<String, AttachmentFixture> ATTACHMENTS = attachments();

    private static Map<String, AttachmentFixture> attachments() {
        Map<String, AttachmentFixture> attachments = new LinkedHashMap<>();
        for (AttachmentFixture attachment : List.of(
                new AttachmentFixture("att5001", "1001", "notes.txt", "text/plain", 15, "Deploy at 10:00"),
                new AttachmentFixture("att5002", "1001", "diagram.png", "image/png", 3000, null),
                new AttachmentFixture("att5003", "1001", "dump.csv", "text/csv", 500_000, "a,b"),
                new AttachmentFixture("att5004", "1001", "..", "text/plain", 5, "dots"),
                new AttachmentFixture("att5100", "2000", "salaries.txt", "text/plain", 30, HR_SECRET))) {
            attachments.put(attachment.id(), attachment);
        }
        return attachments;
    }

    private ObjectNode attachment(AttachmentFixture attachment, List<String> expand) {
        ObjectNode node = JSON.createObjectNode().put("id", attachment.id()).put("type", "attachment")
                .put("status", "current").put("title", attachment.title());
        node.putObject("container").put("id", attachment.page()).put("type", "page");
        node.putObject("version").put("number", 1).put("when", "2026-10-22T09:00:00.000+02:00");
        node.putObject("extensions").put("mediaType", attachment.mediaType()).put("fileSize", attachment.size());
        node.putObject("_links").put("download", "/download/attachments/" + attachment.page() + "/" + attachment.title()
                + "?version=1&modificationDate=1760000000000&api=v2");
        return node;
    }

    private void attachments(HttpExchange exchange, String pageId, Map<String, String> query) throws IOException {
        if (!FIXTURES.containsKey(pageId)) {
            respond(exchange, 404, error(404, "No content found with id: ContentId{id=" + pageId + "}"));
            return;
        }
        ObjectNode response = JSON.createObjectNode();
        ArrayNode results = response.putArray("results");
        ATTACHMENTS.values().stream().filter(a -> a.page().equals(pageId))
                .forEach(a -> results.add(attachment(a, List.of())));
        window(exchange, response, query, "/rest/api/content/" + pageId + "/child/attachment");
    }

    private void download(HttpExchange exchange) throws IOException {
        String authorization = String.valueOf(exchange.getRequestHeaders().getFirst("Authorization"));
        String path = exchange.getRequestURI().getRawPath();
        requests.add(new Request("GET", path, Map.of(), authorization, ""));
        String[] parts = path.split("/");
        AttachmentFixture found = ATTACHMENTS.values().stream()
                .filter(a -> a.page().equals(parts[3]) && a.title().equals(parts[4])).findFirst().orElse(null);
        if (found == null || found.text() == null) {
            respond(exchange, 404, error(404, "no file"));
            return;
        }
        byte[] bytes = found.text().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", found.mediaType());
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** Stored versions of 1001: 2 exists, the rest do not. Of 1000: 1, 2 and 4 to 6 exist (3 was pruned), 7 is current. */
    private void historical(HttpExchange exchange, String id, String version, List<String> expand) throws IOException {
        Fixture current = FIXTURES.get(id);
        if ("1700".equals(id) && "1".equals(version)) {
            // A confused backend: the content behind the id is another page.
            respond(exchange, 200, contentNode(new Fixture("1000", "page", "ENG", "Engineering home", 1, List.of(),
                    "<p>other</p>", null), expand).put("status", "historical").toString());
            return;
        }
        if ("1000".equals(id) && version != null && version.matches("[1-6]") && !"3".equals(version)) {
            int number = Integer.parseInt(version);
            String text = number == 6 ? "<p>Welcome to engineering.</p><p>Newer line.</p>"
                    : "<p>Welcome to engineering.</p><p>Old line.</p>";
            ObjectNode content = contentNode(new Fixture(id, "page", "ENG", "Engineering home", number, List.of(), text, null),
                    expand);
            content.put("status", "historical");
            ((ObjectNode) content.get("version")).put("message", "Edit " + number);
            respond(exchange, 200, content.toString());
            return;
        }
        if (current == null || !"2".equals(version) || !"1001".equals(id)) {
            respond(exchange, 404, error(404, "No content found with id: ContentId{id=" + id + "}"));
            return;
        }
        ObjectNode content = contentNode(new Fixture(id, "page", current.space(), current.title(), 2, List.of(),
                "<p>Release 2.0 ships on Monday.</p>", null), expand);
        content.put("status", "historical");
        respond(exchange, 200, content.toString());
    }

    private void spaces(HttpExchange exchange, String rawQuery) throws IOException {
        Map<String, String> query = query(rawQuery);
        List<String> wanted = new java.util.ArrayList<>();
        if (rawQuery != null) {
            for (String pair : rawQuery.split("&")) {
                if (pair.startsWith("spaceKey=")) {
                    wanted.add(URLDecoder.decode(pair.substring("spaceKey=".length()), StandardCharsets.UTF_8));
                }
            }
        }
        ObjectNode response = JSON.createObjectNode();
        ArrayNode results = response.putArray("results");
        for (String key : SPACE_LABELS.keySet()) {
            if (!wanted.isEmpty() && !wanted.contains(key)) {
                continue;
            }
            List<String> labels = SPACE_LABELS.get(key);
            ObjectNode space = results.addObject().put("id", 98306).put("key", key).put("name", key + " space")
                    .put("type", "global").put("status", "current");
            if (labels != null) {
                ObjectNode listing = space.putObject("metadata").putObject("labels");
                ArrayNode labelResults = listing.putArray("results");
                labels.forEach(label -> labelResults.addObject().put("prefix", "global").put("name", label));
                listing.put("start", 0).put("limit", "FULL".equals(key) ? 2 : 200).put("size", labels.size());
                if ("MANY".equals(key)) {
                    listing.putObject("_links").put("next", "/rest/api/space/MANY?start=200");
                }
            }
        }
        window(exchange, response, query, "/rest/api/space");
    }

    /** PUT: the version number in the request must be the stored one plus one, else 409 as Confluence answers. */
    private void update(HttpExchange exchange, String id, JsonNode request) throws IOException {
        Fixture fixture = FIXTURES.get(id);
        if (fixture == null || !"page".equals(fixture.type())) {
            respond(exchange, 404, error(404, "No content found with id: ContentId{id=" + id + "}"));
            return;
        }
        if ("Existing".equals(request.path("title").asString())) {
            respond(exchange, 400, error(400, "A page already exists with the title Existing"));
            return;
        }
        if ("1700".equals(id)) {
            respond(exchange, 409, error(409, "Concurrent edit"));
            return;
        }
        if (request.path("version").path("number").asInt() != fixture.version() + 1) {
            respond(exchange, 409, error(409, "Version must be incremented on update. Current version is: "
                    + fixture.version()));
            return;
        }
        respond(exchange, 200, content(new Fixture(id, "page", fixture.space(), request.path("title").asString(),
                fixture.version() + 1, fixture.ancestors(),
                request.path("body").path("storage").path("value").asString(), null),
                List.of("space", "version", "ancestors")));
    }

    private void delete(HttpExchange exchange, String id) throws IOException {
        if ("1403".equals(id)) {
            respond(exchange, 403, error(403, "Forbidden"));
        } else if (!FIXTURES.containsKey(id)) {
            respond(exchange, 404, error(404, "No content found with id: ContentId{id=" + id + "}"));
        } else {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        }
    }

    private void get(HttpExchange exchange, String id, Map<String, String> query, String authorization)
            throws IOException {
        List<String> expand = Arrays.asList(query.getOrDefault("expand", "").split(","));
        Fixture fixture = FIXTURES.get(id);
        if ("historical".equals(query.get("status"))) {
            historical(exchange, id, query.get("version"), expand);
            return;
        }
        if (COMMENTS.containsKey(id)) {
            respond(exchange, 200, comment(COMMENTS.get(id), expand).toString());
            return;
        }
        if (ATTACHMENTS.containsKey(id)) {
            respond(exchange, 200, attachment(ATTACHMENTS.get(id), expand).toString());
            return;
        }
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
        } else if (cql.contains("space in (\"IDX\")")) {
            for (int i = 0; i < 40; i++) {
                results.add(listed(String.valueOf(8000 + i), "IDX", expand));
            }
            window(exchange, response, query, "/rest/api/content/search");
            return;
        } else if (cql.contains("space in (\"BIG\")")) {
            FIXTURES.values().stream().filter(f -> "BIG".equals(f.space())).forEach(f -> results.add(contentNode(f, expand)));
            window(exchange, response, query, "/rest/api/content/search");
            return;
        } else if (cql.contains("text ~ \"FAIL\"")) {
            results.add(contentNode(new Fixture("1500", "page", "FAIL", "Failing", 1, List.of(), null, null), expand));
        } else {
            results.add(listed("1000", "ENG", expand));
            results.add(listed("1001", "ENG", expand));
            results.add(listed("1002", "ENG", expand));
            results.add(listed("2000", "ENG", expand));
            results.add(listed("2002", "ENG", expand));
            results.add(listed("4002", "ENG", expand));
        }
        response.put("start", 0).put("limit", Integer.parseInt(query.getOrDefault("limit", "25")))
                .put("size", results.size());
        response.putObject("_links").put("base", baseUrl()).put("context", "")
                .put("self", baseUrl() + "/rest/api/content/search");
        respond(exchange, 200, response.toString());
    }

    /**
     * {@code /rest/api/search}: hits with an excerpt and without any space. Searching ENG returns the pages of ENG, a
     * blog post, a comment and pages that are in HR or OPS now; the index lists them all, which real Confluence would
     * not, so the server must look every hit up and drop what is not in the space asked for.
     */
    private void siteSearch(HttpExchange exchange) throws IOException {
        String authorization = String.valueOf(exchange.getRequestHeaders().getFirst("Authorization"));
        Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
        requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(), query, authorization, ""));
        String cql = query.getOrDefault("cql", "");
        if (EXPIRED_CREDENTIAL.equals(authorization)) {
            respond(exchange, 401, error(401, "Unauthorized"));
            return;
        }
        if (cql.contains("\"REDIR\"")) {
            redirect(exchange);
            return;
        }
        if (cql.contains("\"BAD\"")) {
            respond(exchange, 400, error(400, "Could not parse cql: " + BODY_MARKER));
            return;
        }
        ObjectNode response = JSON.createObjectNode();
        ArrayNode results = response.putArray("results");
        if (cql.contains("space in (\"IDX\")")) {
            for (int i = 0; i < 40; i++) {
                searchHit(results, String.valueOf(8000 + i), "Moved " + i, "moved text " + i);
            }
        } else if (cql.contains("space in (\"BIG\")")) {
            for (int i = 0; i < 30; i++) {
                searchHit(results, String.valueOf(9000 + i), "Bulk " + i, "bulk " + i);
            }
        } else if (cql.contains("space in (\"ENG\")")) {
            searchHit(results, "1000", "Engineering home", "Welcome to @@@hl@@@engineering@@@endhl@@@.\n\n  Second   line");
            searchHit(results, "1001", "Release notes", "Release 2.1 ships on Friday.");
            searchHit(results, "1002", "Team news", "News");
            searchHit(results, "2000", "Salaries", HR_SECRET);
            searchHit(results, "4002", "Operations home", "Operations.");
            searchHit(results, "9999", "Gone", "no such page now");
            results.addObject().put("title", "Comment").put("excerpt", "a comment").putObject("content")
                    .put("id", "7001").put("type", "comment").put("status", "current");
        }
        window(exchange, response, query, "/rest/api/search");
    }

    private static void searchHit(ArrayNode results, String id, String title, String excerpt) {
        Fixture fixture = FIXTURES.get(id);
        ObjectNode hit = results.addObject();
        hit.putObject("content").put("id", id).put("type", fixture == null ? "page" : fixture.type())
                .put("status", "current").put("title", title);
        hit.put("title", title).put("excerpt", excerpt).put("url", "/pages/viewpage.action?pageId=" + id)
                .put("lastModified", "2026-10-20T10:15:30.000+02:00");
    }

    private void create(HttpExchange exchange, JsonNode request) throws IOException {
        if ("comment".equals(request.path("type").asString())) {
            String page = request.path("container").path("id").asString();
            if (!FIXTURES.containsKey(page)) {
                respond(exchange, 404, error(404, "No content found"));
                return;
            }
            String parent = request.path("ancestors").path(0).path("id").asString(null);
            respond(exchange, 200, comment(new CommentFixture("7999", page, parent, "Test User", "footer",
                    request.path("body").path("storage").path("value").asString()), List.of()).toString());
            return;
        }
        String space = request.path("space").path("key").asString();
        String title = request.path("title").asString();
        if ("LOCKED".equals(space)) {
            respond(exchange, 403, error(403, "Not permitted to create page in space LOCKED"));
        } else if ("Existing".equals(title)) {
            respond(exchange, 400, error(400, "A page with this title already exists: A page already exists "
                    + "with the title Existing in the space with key " + space));
        } else {
            String parent = request.path("ancestors").path(0).path("id").asString();
            Fixture parentPage = FIXTURES.get(parent);
            List<String> placed = new java.util.ArrayList<>(parentPage == null ? List.of() : parentPage.ancestors());
            if ("Moved parent".equals(title)) {
                placed = new java.util.ArrayList<>(List.of("9999"));
            }
            placed.add(parent);
            List<String> expand = "No space in reply".equals(title)
                    ? List.of("version", "ancestors", "body.storage")
                    : List.of("space", "version", "ancestors", "body.storage");
            respond(exchange, 200, content(new Fixture("5001", "page", space, title, 1, placed,
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
    private void space(HttpExchange exchange, String requestedKey) throws IOException {
        String key = requestedKey.toUpperCase(java.util.Locale.ROOT);
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
            listing.put("limit", "FULL".equals(key) ? 2 : 200);
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
        labels.put("FULL", List.of("a", "b"));
        labels.put("BIG", List.of());
        labels.put("IDX", List.of());
        for (String ordinary : List.of("SECRET", "REDIR", "BAD", "FAIL")) {
            labels.put(ordinary, List.of());
        }
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
                new Fixture("4004", "page", "FULL", "Full label page", 1, List.of(), "<p>full</p>", null),
                new Fixture("1700", "page", "ENG", "Contended", 5, List.of("1000"), "<p>Edited by two people.</p>", null),
                new Fixture("4003", "page", "MANY", "Many labels", 1, List.of(), "<p>unclear</p>", null))) {
            fixtures.put(fixture.id(), fixture);
        }
        for (int i = 0; i < 40; i++) {
            String id = String.valueOf(8000 + i);
            // 8000-8029 sit in HR now, 8030-8039 in IDX; the search index lists all of them under IDX.
            fixtures.put(id, new Fixture(id, "page", i < 30 ? "HR" : "IDX", "Moved " + i, 1, List.of(), "<p>m</p>", null));
        }
        for (int i = 0; i < 30; i++) {
            String id = String.valueOf(9000 + i);
            fixtures.put(id, new Fixture(id, "page", "BIG", "Bulk " + i, 1, List.of(), "<p>bulk " + i + "</p>", null));
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
