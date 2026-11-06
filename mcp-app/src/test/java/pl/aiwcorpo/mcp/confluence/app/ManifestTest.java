package pl.aiwcorpo.mcp.confluence.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.manifest.DefinitionHash;
import pl.aiwcorpo.mcp.manifest.ManifestRules;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The C6 manifest at {@code GET /mcp-manifest}: it passes the schema and rules the gateway applies at registration, and
 * its tool definitions are exactly what {@code tools/list} publishes, so the {@code definitionHash} the gateway pins is
 * the one of the tools clients see. The starter checks the same at startup outside dev; this keeps it from being
 * switched off unnoticed.
 */
class ManifestTest extends ServerTestSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The reviewed catalog: ws-09 writes the Cedar policies from exactly this table. */
    static final Map<String, String> RISKS = Map.ofEntries(
            Map.entry("search_pages", "READ_ONLY"), Map.entry("list_pages", "READ_ONLY"),
            Map.entry("list_child_pages", "READ_ONLY"), Map.entry("list_spaces", "READ_ONLY"),
            Map.entry("get_page", "READ_ONLY"), Map.entry("get_page_by_title", "READ_ONLY"),
            Map.entry("get_space", "READ_ONLY"), Map.entry("get_page_version", "READ_ONLY"),
            Map.entry("list_comments", "READ_ONLY"), Map.entry("list_labels", "READ_ONLY"),
            Map.entry("list_attachments", "READ_ONLY"), Map.entry("read_attachment", "READ_ONLY"),
            Map.entry("create_page", "UPDATE"), Map.entry("update_page", "UPDATE"),
            Map.entry("add_comment", "UPDATE"), Map.entry("add_label", "UPDATE"),
            Map.entry("delete_page", "DESTRUCTIVE"));

    static final Map<String, String> REFS = Map.ofEntries(
            Map.entry("search_pages", "space:Confluence::Space"), Map.entry("list_pages", "space:Confluence::Space"),
            Map.entry("list_child_pages", "pageId:Confluence::Page"), Map.entry("list_spaces", ""),
            Map.entry("get_page", "pageId:Confluence::Page"), Map.entry("get_page_by_title", "space:Confluence::Space"),
            Map.entry("get_space", "space:Confluence::Space"), Map.entry("get_page_version", "pageId:Confluence::Page"),
            Map.entry("list_comments", "pageId:Confluence::Page"), Map.entry("list_labels", "pageId:Confluence::Page"),
            Map.entry("list_attachments", "pageId:Confluence::Page"), Map.entry("read_attachment", "pageId:Confluence::Page"),
            Map.entry("create_page", "parentId:Confluence::Page,space:Confluence::Space"),
            Map.entry("update_page", "pageId:Confluence::Page"), Map.entry("add_comment", "pageId:Confluence::Page"),
            Map.entry("add_label", "pageId:Confluence::Page"), Map.entry("delete_page", "pageId:Confluence::Page"));

    static final Map<String, String> TITLES = Map.ofEntries(
            Map.entry("search_pages", "Search pages"), Map.entry("list_pages", "List pages"),
            Map.entry("list_child_pages", "List child pages"), Map.entry("list_spaces", "List spaces"),
            Map.entry("get_page", "Get page"), Map.entry("get_page_by_title", "Get page by title"),
            Map.entry("get_space", "Get space"), Map.entry("get_page_version", "Get page version"),
            Map.entry("list_comments", "List comments"), Map.entry("list_labels", "List labels"),
            Map.entry("list_attachments", "List attachments"), Map.entry("read_attachment", "Read attachment"),
            Map.entry("create_page", "Create page"), Map.entry("update_page", "Update page"),
            Map.entry("add_comment", "Add comment"), Map.entry("add_label", "Add label"),
            Map.entry("delete_page", "Delete page"));
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();

    private HttpResponse<String> get(String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl() + path)).GET();
        headers.forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String body, Map<String, String> headers, String session) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl() + ENDPOINT))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        if (session != null) {
            request.header("Mcp-Session-Id", session);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode manifest() throws Exception {
        HttpResponse<String> response = get("/mcp-manifest", GATEWAY.service().mint().headers());
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    /** The version in the parent pom, which is what the manifest must carry (the surefire working directory is mcp-app). */
    private static String projectVersion() throws java.io.IOException {
        String pom = java.nio.file.Files.readString(java.nio.file.Path.of("..", "pom.xml"));
        java.util.regex.Matcher version = java.util.regex.Pattern
                .compile("<artifactId>mcp-confluence-parent</artifactId>\\s*<version>([^<]+)</version>").matcher(pom);
        assertThat(version.find()).as("version in the parent pom").isTrue();
        return version.group(1);
    }

    private static JsonNode withoutHash(JsonNode tools) {
        JsonNode copy = tools.deepCopy();
        copy.forEach(tool -> ((tools.jackson.databind.node.ObjectNode) tool).remove("definitionHash"));
        return copy;
    }

    @Test
    void theBuildManifestHasTheToolsTheServerPublishes() throws Exception {
        JsonNode exported = JSON.readTree(ManifestExport.manifest());
        JsonNode served = manifest();

        // The hash covers the accepted credential types. The tests run with the types they configure, the production
        // configuration of a system may list others; the hashes are comparable only when the lists are equal.
        boolean sameTypes = exported.at("/credential/acceptedTypes").equals(served.at("/credential/acceptedTypes"));
        if (sameTypes) {
            assertThat(exported.get("tools")).as("same definitions and definitionHash as tools/list").isEqualTo(served.get("tools"));
        } else {
            assertThat(withoutHash(exported.get("tools"))).as("same definitions as tools/list").isEqualTo(withoutHash(served.get("tools")));
        }
        // The served manifest carries the version the tests configure; the file of the build must carry the one of the project.
        assertThat(exported.get("version").asString()).as("version in the build manifest").isEqualTo(projectVersion());
        assertThat(exported.get("resourceTypes")).isEqualTo(served.get("resourceTypes"));
        assertThat(exported.get("serverId")).isEqualTo(served.get("serverId"));
        assertThat(exported.has("environment")).as("only the devstack profile marks a manifest as dev").isFalse();
        assertThat(exported.path("credential").path("acceptedTypes").toString()).isEqualTo("[\"user-secret\"]");
    }

    /** The raw {@code tools/list} result, as a client receives it. */
    private List<JsonNode> listedTools() throws Exception {
        HttpResponse<String> initialize = post("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
                "capabilities":{},"clientInfo":{"name":"manifest-test","version":"1"}}}""",
                GATEWAY.user().mint().headers(), null);
        assertThat(initialize.statusCode()).isEqualTo(200);
        String session = initialize.headers().firstValue("Mcp-Session-Id").orElseThrow();
        post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", GATEWAY.user().mint().headers(), session);
        String body = post("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}", GATEWAY.user().mint().headers(), session).body();
        int data = body.indexOf("data:");
        JsonNode message = JSON.readTree(data >= 0 ? body.substring(data + 5).strip() : body);
        List<JsonNode> tools = new ArrayList<>();
        message.at("/result/tools").forEach(tools::add);
        return tools;
    }

    @Test
    void theManifestPassesTheRulesOfTheGateway() throws Exception {
        JsonNode manifest = manifest();

        assertThat(ManifestRules.violations(manifest.toString())).isEmpty();
        assertThat(manifest.get("serverId").asString()).isEqualTo(SERVER_ID);
        assertThat(manifest.get("system").asString()).isEqualTo(SYSTEM);
    }

    @Test
    void theManifestCarriesThePoliciesAndTheDeclarationsOfTheJarWithTheirHash() throws Exception {
        JsonNode manifest = manifest();

        assertThat(manifest.at("/policies/namespace").asString()).isEqualTo("Confluence");
        assertThat(manifest.at("/policies/files").findValuesAsString("name")).containsExactly("read.cedar", "update.cedar");
        assertThat(manifest.get("policiesHash").asString()).startsWith("sha256:");
        List<String> declared = new ArrayList<>();
        manifest.get("resourceTypes").forEach(type -> declared.add(type.get("type").asString()));
        assertThat(declared).containsExactlyInAnyOrder("Confluence::Space", "Confluence::Page");
        JsonNode page = manifest.get("resourceTypes").valueStream()
                .filter(type -> "Confluence::Page".equals(type.get("type").asString())).findFirst().orElseThrow();
        assertThat(page.get("parent").asString()).isEqualTo("Confluence::Space");
        assertThat(page.get("parentAttribute").asString()).isEqualTo("space");
    }

    @Test
    void theDefinitionsAreExactlyWhatToolsListPublishesSoTheHashesAgree() throws Exception {
        JsonNode manifest = manifest();
        List<JsonNode> listed = listedTools();

        assertThat(listed).hasSameSizeAs(manifest.get("tools"));
        for (JsonNode tool : manifest.get("tools")) {
            String name = tool.get("name").asString();
            JsonNode published = listed.stream().filter(t -> t.get("name").asString().equals(name)).findFirst().orElseThrow();
            assertThat(tool.get("definition")).as(name).isEqualTo(published);
            assertThat(DefinitionHash.ofManifestTool(manifest.toString(), name)).as(name)
                    .isEqualTo(tool.get("definitionHash").asString());
        }
    }

    @Test
    void everyToolHasATitleAndAClosedInputSchema() throws Exception {
        for (JsonNode tool : manifest().get("tools")) {
            assertThat(tool.at("/definition/title").asString()).isNotBlank();
            assertThat(tool.at("/definition/inputSchema/additionalProperties").asBoolean(true)).isFalse();
        }
    }

    @Test
    void theResourceRefsAndRisksAreDeclared() throws Exception {
        Map<String, String> refs = new java.util.HashMap<>();
        Map<String, String> risks = new java.util.HashMap<>();
        for (JsonNode tool : manifest().get("tools")) {
            String name = tool.get("name").asString();
            risks.put(name, tool.get("risk").asString());
            refs.put(name, "");
            if (tool.has("resourceRefs")) {
                List<String> declared = new ArrayList<>();
                tool.get("resourceRefs").forEach(ref -> declared.add(ref.get("argument").asString() + ":" + ref.get("type").asString()));
                java.util.Collections.sort(declared);
                refs.put(name, String.join(",", declared));
            }
        }

        assertThat(risks).isEqualTo(RISKS);
        assertThat(refs).isEqualTo(REFS);
    }

    @Test
    void everyToolTitleIsPolish() throws Exception {
        Map<String, String> titles = new java.util.HashMap<>();
        for (JsonNode tool : manifest().get("tools")) {
            titles.put(tool.get("name").asString(), tool.at("/definition/title").asString());
        }
        assertThat(titles).isEqualTo(TITLES);
    }

    @Test
    void theManifestNeedsTheInternalJwtAndIsNotATool() throws Exception {
        assertThat(get("/mcp-manifest", Map.of()).statusCode()).isEqualTo(401);
        assertThat(get("/mcp-manifest", GATEWAY.user().tool("get_page").mint().headers()).statusCode()).isEqualTo(401);
    }
}
