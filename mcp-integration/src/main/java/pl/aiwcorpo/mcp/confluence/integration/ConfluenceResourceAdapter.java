package pl.aiwcorpo.mcp.confluence.integration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;
import pl.aiwcorpo.mcp.confluence.core.domain.Attachment;
import pl.aiwcorpo.mcp.confluence.core.domain.AttachmentText;
import pl.aiwcorpo.mcp.confluence.core.domain.Comment;
import pl.aiwcorpo.mcp.confluence.core.domain.ConfluenceIds;
import pl.aiwcorpo.mcp.confluence.core.domain.ContentHit;
import pl.aiwcorpo.mcp.confluence.core.domain.NewComment;
import pl.aiwcorpo.mcp.confluence.core.domain.NewPage;
import pl.aiwcorpo.mcp.confluence.core.domain.Page;
import pl.aiwcorpo.mcp.confluence.core.domain.PageConflictException;
import pl.aiwcorpo.mcp.confluence.core.domain.PageEdit;
import pl.aiwcorpo.mcp.confluence.core.domain.PageNotEditableException;
import pl.aiwcorpo.mcp.confluence.core.domain.PageNotFoundException;
import pl.aiwcorpo.mcp.confluence.core.domain.PageRecord;
import pl.aiwcorpo.mcp.confluence.core.domain.PageRejectedException;
import pl.aiwcorpo.mcp.confluence.core.domain.PageSummary;
import pl.aiwcorpo.mcp.confluence.core.domain.PageVersion;
import pl.aiwcorpo.mcp.confluence.core.domain.Slice;
import pl.aiwcorpo.mcp.confluence.core.domain.Space;
import pl.aiwcorpo.mcp.confluence.core.port.out.ConfluenceResourcePort;
import pl.aiwcorpo.mcp.platform.error.InvalidToolArgumentException;
import pl.aiwcorpo.mcp.platform.security.BackendCredential;
import pl.aiwcorpo.mcp.platform.security.CredentialContext;
import pl.aiwcorpo.mcp.platform.security.CredentialRequiredException;
import pl.aiwcorpo.mcp.platform.text.TextHygiene;
import pl.aiwcorpo.mcp.platform.text.UntrustedText;

/**
 * Confluence Data Center REST API v1 ({@code /rest/api/...}, the same paths from 8.5 to 10.x) as the calling user: with
 * the personal access token the gateway resolved for this request (C3, {@code Authorization: Bearer}), never with an
 * account of the server.
 *
 * <p>Error mapping matters for security: 404 and 403 both become "not visible" (one client-facing code,
 * {@code not_found_or_denied}), and 401 means the stored token no longer works, so the user is sent to the
 * portal to reconnect ({@code credential_required}). A failed call becomes an exception with the status
 * alone, an unusable answer (redirect, unparsable or oversized body) a {@link BackendResponseException}, so no
 * response body reaches a message or a log line.
 *
 * <p>Page bodies are read as {@code body.storage} only. {@code body.view} and {@code body.export_view} are
 * rendered by Confluence with include and excerpt-include macros expanded, i.e. with content of other pages
 * and spaces, so this adapter never asks for them.
 */
@Component
@EnableConfigurationProperties(ConfluenceBackendProperties.class)
public class ConfluenceResourceAdapter implements ConfluenceResourcePort {

    /** System id of this server: {@code server-id} is {@code mcp-<system>}, and the starter refuses any other. */
    static final String SYSTEM = "confluence";

    static final String CONTENT = "/rest/api/content";
    static final String SPACE = "/rest/api/space";
    static final String SEARCH = "/rest/api/search";
    static final String PAGE_EXPAND = "body.storage,space,version,ancestors";
    static final String RECORD_EXPAND = "space,version,ancestors";
    static final String SUMMARY_EXPAND = "space,version";
    /** The tree needs no version, and a window of pages should stay well under the response size limit. */
    static final String TREE_EXPAND = "space,ancestors";
    static final String SPACE_EXPAND = "metadata.labels";
    static final String VERSION_EXPAND = "version,space";
    static final String COMMENT_EXPAND = "body.storage,version,ancestors,container,extensions.inlineProperties";
    static final String ATTACHMENT_EXPAND = "version,container";

    /** Size limit of the page text handed to the model. */
    static final int MAX_PAGE_TEXT = 50_000;

    /** Size limit of one comment handed to the model. */
    static final int MAX_COMMENT_TEXT = 4_000;

    /** Attachments larger than this are not downloaded; the model gets the reason instead. */
    static final long MAX_ATTACHMENT_BYTES = 200_000;

    static final int TITLE_MAX = 255;
    static final int LABEL_LIST_LIMIT = 200;

    private static final String DEV_PROFILE = "dev";
    private static final Logger LOG = LoggerFactory.getLogger(ConfluenceResourceAdapter.class);

    /** Only attachments of the page just checked, only below the download root of Confluence. */
    private static final Pattern DOWNLOAD_PATH = Pattern.compile("^/download/attachments/[1-9][0-9]{0,18}/[^?#\\s]{1,500}(\\?[A-Za-z0-9=&._%-]{0,300})?$");

    private static final Set<String> TEXT_APPLICATION_TYPES = Set.of("application/json", "application/xml");

    private final RestClient client;
    private final String baseUrl;

    public ConfluenceResourceAdapter(ConfluenceBackendProperties properties, ObjectProvider<SslBundles> sslBundles,
            Environment environment) {
        this.baseUrl = requireBaseUrl(properties.baseUrl(),
                environment.matchesProfiles(DEV_PROFILE) || properties.allowPlainHttp());
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory(properties, sslBundles))
                // The limit applies to the bytes read, before Jackson and before Jsoup see any of them.
                .requestInterceptor(new BoundedBodyInterceptor(properties.maxResponseSize().toBytes()))
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultStatusHandler(status -> status.isError() || status.is3xxRedirection(), (request, response) -> {
                    // Status only: the body and headers of a failed call may echo the credential or carry backend
                    // detail, and the exception message ends up in the starter's tool_failed log line.
                    HttpStatusCode status = response.getStatusCode();
                    if (status.is3xxRedirection()) {
                        // Redirects are not followed (the credential must not go elsewhere) and an empty body
                        // must not read as "no page": an SSO login page would turn into not_found_or_denied.
                        throw new BackendResponseException("redirect " + status.value());
                    }
                    throw status.is4xxClientError()
                            ? HttpClientErrorException.create(status, "", HttpHeaders.EMPTY, new byte[0], null)
                            : HttpServerErrorException.create(status, "", HttpHeaders.EMPTY, new byte[0], null);
                })
                .build();
    }

    // ---- lists ------------------------------------------------------------------------------------------------

    @Override
    public Slice<PageSummary> searchPages(String cql, int start, int limit) {
        SearchResponse response = send(() -> getJson(CONTENT + "/search", Map.of(),
                        query("cql", cql, "start", start, "limit", limit, "expand", SUMMARY_EXPAND), SearchResponse.class),
                rejected(() -> new InvalidToolArgumentException(
                        // Text made only of words CQL reserves; the value stays out of the message (it came from the model).
                        "Confluence did not accept the search words; try other words"), () -> null));
        return pageSlice(response, start);
    }

    /**
     * {@code /rest/api/search} and not {@code content/search}, because only it returns the excerpt. {@code excerpt=indexed}
     * asks for the stored text without highlight markers; the markers are removed anyway.
     */
    @Override
    public Slice<ContentHit> searchContent(String cql, int start, int limit) {
        SearchResultsResponse response = send(() -> getJson(SEARCH, Map.of(),
                        query("cql", cql, "start", start, "limit", limit, "excerpt", "indexed"), SearchResultsResponse.class),
                rejected(() -> new InvalidToolArgumentException("Confluence did not accept the query"), () -> null));
        if (response == null || response.results() == null) {
            return Slice.empty();
        }
        List<ContentHit> hits = response.results().stream()
                .filter(result -> result.content() != null && "page".equals(result.content().type())
                        && ConfluenceIds.isPageId(result.content().id()))
                .map(result -> new ContentHit(result.content().id(), excerptText(result.excerpt()))).toList();
        return new Slice<>(hits, nextStart(start, response.results().size(), response._links()));
    }

    private static String excerptText(String excerpt) {
        if (excerpt == null) {
            return "";
        }
        return TextHygiene.normalize(excerpt.replace("@@@hl@@@", "").replace("@@@endhl@@@", ""))
                .replaceAll("\\s+", " ").strip();
    }

    @Override
    public Slice<PageSummary> listPages(String spaceKey, int start, int limit) {
        SearchResponse response = send(() -> getJson(CONTENT, Map.of(),
                query("type", "page", "status", "current", "spaceKey", spaceKey, "start", start, "limit", limit,
                        "expand", SUMMARY_EXPAND), SearchResponse.class), hidden(() -> null));
        return pageSlice(response, start);
    }

    @Override
    public Slice<PageRecord> listSpacePages(String spaceKey, int start, int limit) {
        SearchResponse response = send(() -> getJson(CONTENT, Map.of(),
                query("type", "page", "status", "current", "spaceKey", spaceKey, "start", start, "limit", limit,
                        "expand", TREE_EXPAND), SearchResponse.class), hidden(() -> null));
        if (response == null || response.results() == null) {
            return Slice.empty();
        }
        List<PageRecord> pages = response.results().stream().map(ContentResponse::withId)
                .filter(ContentResponse::isCurrentPage).filter(ContentResponse::isInGlobalSpace)
                .map(content -> toRecord(content, null)).toList();
        return new Slice<>(pages, nextStart(start, response.results().size(), response._links()));
    }

    @Override
    public Slice<PageSummary> listChildPages(String pageId, int start, int limit) {
        SearchResponse response = send(() -> getJson(CONTENT + "/{id}/child/page", Map.of("id", pageId),
                query("start", start, "limit", limit, "expand", RECORD_EXPAND), SearchResponse.class),
                hidden(() -> null));
        return pageSlice(response, start, content -> pageId.equals(lastOf(content.ancestorIds())));
    }

    @Override
    public Slice<Space> listSpaces(int start, int limit) {
        SpaceListResponse response = send(() -> getJson(SPACE, Map.of(),
                query("type", "global", "status", "current", "start", start, "limit", limit, "expand", SPACE_EXPAND),
                SpaceListResponse.class), hidden(() -> null));
        if (response == null || response.results() == null) {
            return Slice.empty();
        }
        List<Space> spaces = response.results().stream()
                .filter(space -> space.key() != null && ConfluenceIds.isSpaceKey(space.key()))
                .map(SpaceResponse::toSpace).toList();
        return new Slice<>(spaces, nextStart(start, response.results().size(), response._links()));
    }

    private static Slice<PageSummary> pageSlice(SearchResponse response, int start) {
        return pageSlice(response, start, content -> true);
    }

    /** {@code belongs} drops what is not a child of the page asked about, whatever the endpoint returned. */
    private static Slice<PageSummary> pageSlice(SearchResponse response, int start,
            java.util.function.Predicate<ContentResponse> belongs) {
        if (response == null || response.results() == null) {
            return Slice.empty();
        }
        List<PageSummary> pages = response.results().stream()
                .map(ContentResponse::withId)
                .filter(ContentResponse::isCurrentPage)
                .filter(ContentResponse::isInGlobalSpace)
                .filter(belongs)
                .map(ContentResponse::toSummary)
                .toList();
        return new Slice<>(pages, nextStart(start, response.results().size(), response._links()));
    }

    private static String lastOf(List<String> ids) {
        return ids.isEmpty() ? null : ids.getLast();
    }

    private static Integer nextStart(int start, int returned, LinksResponse links) {
        return links != null && links.next() != null && returned > 0 ? start + returned : null;
    }

    // ---- pages and spaces ---------------------------------------------------------------------------------------

    @Override
    public Optional<PageRecord> findPageRecord(String pageId) {
        return fetch(pageId, RECORD_EXPAND).map(content -> toRecord(content, labelsOf(content.spaceKey())));
    }

    /**
     * One query for the pages of a result, then one lookup per distinct space: a search hit set is mostly one space, so
     * its labels are read once, not per page.
     */
    @Override
    public Map<String, PageRecord> findPageRecords(Collection<String> pageIds) {
        Set<String> ids = new LinkedHashSet<>(pageIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        if (!ids.stream().allMatch(ConfluenceIds::isPageId)) {
            throw new IllegalArgumentException("not a page id");
        }
        SearchResponse response = send(() -> getJson(CONTENT + "/search", Map.of(),
                        query("cql", Cql.contentIn(ids), "start", 0, "limit", ids.size(), "expand", RECORD_EXPAND),
                        SearchResponse.class),
                rejected(() -> new InvalidToolArgumentException("Confluence did not accept the query"), () -> null));
        if (response == null || response.results() == null) {
            return Map.of();
        }
        Map<String, Optional<List<String>>> labelsBySpace = new LinkedHashMap<>();
        Map<String, PageRecord> records = new LinkedHashMap<>();
        for (ContentResponse content : response.results().stream().map(ContentResponse::withId)
                .filter(ContentResponse::isCurrentPage).filter(ContentResponse::isInGlobalSpace).toList()) {
            if (ids.contains(content.id())) {
                records.put(content.id(), toRecord(content,
                        labelsBySpace.computeIfAbsent(content.spaceKey(), key -> Optional.ofNullable(labelsOf(key))).orElse(null)));
            }
        }
        return records;
    }

    @Override
    public Optional<PageRecord> findPageRecordByTitle(String spaceKey, String title) {
        SearchResponse response = send(() -> getJson(CONTENT, Map.of(),
                query("type", "page", "status", "current", "spaceKey", spaceKey, "title", title, "limit", 2,
                        "expand", RECORD_EXPAND), SearchResponse.class), hidden(() -> null));
        if (response == null || response.results() == null) {
            return Optional.empty();
        }
        return response.results().stream().map(ContentResponse::withId).filter(ContentResponse::isCurrentPage)
                .filter(ContentResponse::isInGlobalSpace)
                .filter(content -> title.equals(content.title()))
                .findFirst().map(content -> toRecord(content, labelsOf(content.spaceKey())));
    }

    @Override
    public Optional<Space> findSpace(String spaceKey) {
        SpaceResponse response = send(() -> getJson(SPACE + "/{key}", Map.of("key", spaceKey),
                query("expand", SPACE_EXPAND), SpaceResponse.class), hidden(() -> null));
        if (response == null) {
            return Optional.empty();
        }
        if (response.key() == null || response.key().isBlank()) {
            throw new BackendResponseException("space without key");
        }
        return Optional.of(response).filter(space -> ConfluenceIds.isSpaceKey(space.key())).map(SpaceResponse::toSpace);
    }

    @Override
    public Map<String, Space> findSpaces(Collection<String> spaceKeys) {
        Set<String> keys = new LinkedHashSet<>(spaceKeys);
        if (keys.isEmpty()) {
            return Map.of();
        }
        if (!keys.stream().allMatch(ConfluenceIds::isSpaceKey)) {
            throw new IllegalArgumentException("not a space key");
        }
        SpaceListResponse response = send(() -> getJson(SPACE, Map.of(),
                query("spaceKey", List.copyOf(keys), "limit", keys.size(), "expand", SPACE_EXPAND),
                SpaceListResponse.class), hidden(() -> null));
        Map<String, Space> found = new LinkedHashMap<>();
        if (response != null && response.results() != null) {
            for (SpaceResponse space : response.results()) {
                if (space.key() != null && keys.contains(space.key()) && ConfluenceIds.isSpaceKey(space.key())) {
                    found.put(space.key(), space.toSpace());
                }
            }
        }
        return found;
    }

    @Override
    public Optional<Page> findPage(String pageId) {
        return fetch(pageId, PAGE_EXPAND).map(ContentResponse::toPage);
    }

    @Override
    public Optional<Page> findPageVersion(String pageId, int version) {
        ContentResponse response = send(() -> getJson(CONTENT + "/{id}", Map.of("id", pageId),
                query("status", "historical", "version", version, "expand", PAGE_EXPAND), ContentResponse.class),
                hidden(() -> null));
        if (response == null) {
            return Optional.empty();
        }
        // A stored version has status "historical"; only the type, the id asked for and the space matter here.
        return Optional.of(response.withId()).filter(content -> pageId.equals(content.id())).filter(ContentResponse::isPage).filter(ContentResponse::isInGlobalSpace)
                .map(ContentResponse::toPage);
    }

    @Override
    public Optional<PageVersion> findPageVersionInfo(String pageId, int version, boolean current) {
        Map<String, Object> query = current ? query("expand", VERSION_EXPAND)
                : query("status", "historical", "version", version, "expand", VERSION_EXPAND);
        ContentResponse response = send(() -> getJson(CONTENT + "/{id}", Map.of("id", pageId), query,
                ContentResponse.class), hidden(() -> null));
        if (response == null) {
            return Optional.empty();
        }
        return Optional.of(response.withId()).filter(content -> pageId.equals(content.id()))
                .filter(ContentResponse::isPage).filter(ContentResponse::isInGlobalSpace)
                .filter(content -> content.version() != null && content.version().number() == version)
                .map(content -> new PageVersion(version,
                        content.version().by() == null || content.version().by().displayName() == null ? ""
                                : content.version().by().displayName(),
                        content.version().when(), content.version().message() == null ? "" : content.version().message()));
    }

    /**
     * Labels of a space as its classification. Null (unreported) when the lookup finds nothing, the space is hidden or
     * the list is longer than one page of Confluence's answer: a classification that may be incomplete must not read as
     * complete, and the policy refuses a page whose labels are unknown.
     */
    private List<String> labelsOf(String spaceKey) {
        return findSpace(spaceKey).map(Space::labels).orElse(null);
    }

    private static PageRecord toRecord(ContentResponse content, List<String> spaceLabels) {
        PageSummary summary = content.toSummary();
        return new PageRecord(summary.id(), summary.spaceKey(), summary.title(), summary.version(),
                summary.lastUpdated(), content.ancestorIds(), spaceLabels);
    }

    private Optional<ContentResponse> fetch(String pageId, String expand) {
        ContentResponse response = send(() -> getJson(CONTENT + "/{id}", Map.of("id", pageId),
                query("expand", expand), ContentResponse.class), hidden(() -> null));
        if (response == null) {
            return Optional.empty();
        }
        return Optional.of(response.withId()).filter(ContentResponse::isCurrentPage)
                .filter(ContentResponse::isInGlobalSpace);
    }

    // ---- comments, labels, attachments --------------------------------------------------------------------------

    @Override
    public Slice<Comment> listComments(String pageId, int start, int limit) {
        SearchResponse response = send(() -> getJson(CONTENT + "/{id}/child/comment", Map.of("id", pageId),
                query("depth", "all", "start", start, "limit", limit, "expand", COMMENT_EXPAND), SearchResponse.class),
                hidden(() -> null));
        if (response == null || response.results() == null) {
            return Slice.empty();
        }
        List<Comment> comments = response.results().stream().map(ContentResponse::withId)
                .filter(content -> "comment".equals(content.type()))
                .map(content -> content.toComment(null)).toList();
        return new Slice<>(comments, nextStart(start, response.results().size(), response._links()));
    }

    @Override
    public Optional<Comment> findComment(String commentId) {
        ContentResponse response = send(() -> getJson(CONTENT + "/{id}", Map.of("id", commentId),
                query("expand", COMMENT_EXPAND), ContentResponse.class), hidden(() -> null));
        if (response == null || !"comment".equals(response.withId().type())) {
            return Optional.empty();
        }
        return Optional.of(response.toComment(null));
    }

    @Override
    public Comment addComment(NewComment comment) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "comment");
        body.put("container", Map.of("id", comment.page().id(), "type", "page"));
        if (comment.parentId() != null) {
            body.put("ancestors", List.of(Map.of("id", comment.parentId())));
        }
        body.put("body", storageBody(StorageFormat.fromPlainText(comment.body())));
        ContentResponse created = send(() -> postJson(CONTENT, Map.of(), query("expand", COMMENT_EXPAND), body),
                hiddenOrRejected());
        return created.withId().toComment(comment.page().id());
    }

    @Override
    public Slice<String> listLabels(String pageId) {
        LabelListResponse response = send(() -> getJson(CONTENT + "/{id}/label", Map.of("id", pageId),
                query("limit", LABEL_LIST_LIMIT), LabelListResponse.class), hidden(() -> null));
        if (response == null || response.results() == null) {
            return Slice.empty();
        }
        List<String> names = response.results().stream().filter(label -> label.name() != null)
                .filter(label -> label.prefix() == null || "global".equals(label.prefix()))
                .map(LabelResponse::name).toList();
        boolean more = response.results().size() >= LABEL_LIST_LIMIT
                || (response._links() != null && response._links().next() != null);
        return new Slice<>(names, more ? response.results().size() : null);
    }

    @Override
    public void addLabel(String pageId, String label) {
        send(() -> postJson(CONTENT + "/{id}/label", Map.of("id", pageId), Map.of(),
                List.of(Map.of("prefix", "global", "name", label)), LabelListResponse.class), hiddenOrRejected());
    }

    @Override
    public Slice<Attachment> listAttachments(String pageId, int start, int limit) {
        SearchResponse response = send(() -> getJson(CONTENT + "/{id}/child/attachment", Map.of("id", pageId),
                query("start", start, "limit", limit, "expand", "version"), SearchResponse.class), hidden(() -> null));
        if (response == null || response.results() == null) {
            return Slice.empty();
        }
        List<Attachment> attachments = response.results().stream().map(ContentResponse::withId)
                .filter(content -> "attachment".equals(content.type())).map(ContentResponse::toAttachment).toList();
        return new Slice<>(attachments, nextStart(start, response.results().size(), response._links()));
    }

    @Override
    public Optional<AttachmentText> readAttachment(String pageId, String attachmentId, int maxChars) {
        ContentResponse content = send(() -> getJson(CONTENT + "/{id}", Map.of("id", attachmentId),
                query("expand", ATTACHMENT_EXPAND), ContentResponse.class), hidden(() -> null));
        if (content == null || !"attachment".equals(content.withId().type()) || content.container() == null
                || !pageId.equals(content.container().id())) {
            return Optional.empty();
        }
        Attachment attachment = content.toAttachment();
        if (!isTextMedia(attachment.mediaType())) {
            return Optional.of(new AttachmentText(attachment, "", false, "binary_attachment"));
        }
        if (attachment.fileSize() > MAX_ATTACHMENT_BYTES) {
            return Optional.of(new AttachmentText(attachment, "", false, "attachment_too_large"));
        }
        String download = content.links() == null ? null : content.links().download();
        if (download == null || !DOWNLOAD_PATH.matcher(download).matches()
                || !download.startsWith("/download/attachments/" + pageId + "/") || hasDotSegment(download)) {
            throw new BackendResponseException("attachment without a usable download link");
        }
        byte[] data = send(() -> {
            byte[] bytes = client.get().uri(URI.create(baseUrl + download)).accept(MediaType.ALL)
                    .headers(credential()::applyTo).retrieve().body(byte[].class);
            return bytes == null ? new byte[0] : bytes;
        }, hidden(() -> null));
        if (data == null) {
            return Optional.empty();
        }
        String text = TextHygiene.normalize(new String(data, StandardCharsets.UTF_8));
        String cut = UntrustedText.cut(text, maxChars);
        return Optional.of(new AttachmentText(attachment, cut, cut.length() < text.length(), null));
    }

    /** Obstacles to a path that stays under the attachment folder: dot segments, also percent-encoded, and encoded slashes. */
    private static boolean hasDotSegment(String path) {
        String lower = path.split("\\?", 2)[0].toLowerCase(Locale.ROOT);
        if (lower.contains("%2f") || lower.contains("%5c") || lower.contains("\\")) {
            return true;
        }
        for (String segment : lower.split("/")) {
            if (segment.equals(".") || segment.equals("..") || segment.equals("%2e") || segment.equals(".%2e")
                    || segment.equals("%2e.") || segment.equals("%2e%2e")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTextMedia(String mediaType) {
        if (mediaType == null) {
            return false;
        }
        String base = mediaType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        return base.startsWith("text/") || TEXT_APPLICATION_TYPES.contains(base);
    }

    // ---- writes -------------------------------------------------------------------------------------------------

    @Override
    public PageSummary createPage(NewPage page) {
        CreateRequest request = new CreateRequest("page", page.title(), new SpaceRef(page.spaceKey()),
                List.of(new AncestorRef(page.parentId())),
                new Body(new Storage(StorageFormat.fromPlainText(page.body()), "storage")));
        ContentResponse created = send(() -> postJson(CONTENT, Map.of(), query("expand", RECORD_EXPAND), request),
                hiddenOrRejected());
        created.withId();
        requireInsideJudgedTree(created, page);
        return created.toSummary();
    }

    @Override
    public PageSummary updatePage(PageEdit edit) {
        PageRecord judged = edit.judged();
        ContentResponse current = fetch(judged.id(), PAGE_EXPAND).orElseThrow(PageNotFoundException::new);
        boolean samePlace = judged.spaceKey().equalsIgnoreCase(current.spaceKey())
                && judged.ancestorIds().equals(current.ancestorIds());
        if (!samePlace) {
            throw new PageNotFoundException();
        }
        int storedVersion = current.version() == null ? 0 : current.version().number();
        if (storedVersion != edit.expectedVersion()) {
            throw new PageConflictException();
        }
        String existing = current.body() == null || current.body().storage() == null ? ""
                : current.body().storage().value();
        String written = StorageFormat.fromPlainText(edit.body());
        String storage;
        if (edit.mode() == PageEdit.Mode.APPEND) {
            storage = existing + written;
        } else {
            if (!StorageFormat.isPlainParagraphs(existing)) {
                throw new PageNotEditableException();
            }
            storage = written;
        }
        String title = edit.title() == null ? current.title() : edit.title();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("type", "page");
        request.put("title", title);
        request.put("version", Map.of("number", edit.expectedVersion() + 1));
        request.put("body", storageBody(storage));
        ContentResponse updated = send(() -> putJson(CONTENT + "/{id}", Map.of("id", judged.id()),
                query("expand", RECORD_EXPAND), request), conflictHiddenOrRejected());
        updated.withId();
        return updated.toSummary();
    }

    @Override
    public void trashPage(String pageId) {
        send(() -> {
            client.delete().uri(uri -> uri.path(CONTENT + "/{id}").build(pageId)).headers(credential()::applyTo)
                    .retrieve().toBodilessEntity();
            return Boolean.TRUE;
        }, e -> {
            if (isHidden(e)) {
                throw new PageNotFoundException();
            }
            throw e;
        });
    }

    /**
     * The parent was judged before the write; if it moved in between, Confluence put the page under its new place. The
     * page exists by then and is not deleted (a delete is a write the user never asked for), so the call fails with a
     * line for the audit trail and the client is told only that the result is not usable.
     */
    private static void requireInsideJudgedTree(ContentResponse created, NewPage requested) {
        List<String> expected = new ArrayList<>(requested.parentAncestorIds());
        expected.add(requested.parentId());
        boolean spaceMatches = created.spaceKey() == null || requested.spaceKey().equalsIgnoreCase(created.spaceKey());
        if (!spaceMatches || !expected.equals(created.ancestorIds())) {
            LOG.warn("event=page_created_outside_judged_tree pageId={} parentId={}", created.id(), requested.parentId());
            throw new BackendResponseException("created page is not under the judged parent");
        }
    }

    private static Map<String, Object> storageBody(String storage) {
        return Map.of("storage", Map.of("value", storage, "representation", "storage"));
    }

    // ---- version probe ------------------------------------------------------------------------------------------

    /**
     * A GET without any credential, for the capability probe. Null when the instance answers 401, 403 or 404 (it keeps
     * that path behind a login or does not have it); other failures propagate and make the probe unavailable.
     */
    String readAnonymous(String path, MediaType accept) {
        try {
            return client.get().uri(uri -> uri.path(path).build()).accept(accept).retrieve().body(String.class);
        } catch (HttpClientErrorException notThere) {
            return null;
        } catch (BackendResponseException redirected) {
            // A login redirect in front of the path is "not available here", so the probe can try the next source.
            return null;
        }
    }

    // ---- HTTP plumbing ------------------------------------------------------------------------------------------

    private interface ClientErrorHandler<T> {
        T handle(HttpClientErrorException error);
    }

    /** Runs one call; the same mapping of failures for all of them, so no body or header ever reaches a message. */
    private <T> T send(Supplier<T> call, ClientErrorHandler<T> onClientError) {
        try {
            return call.get();
        } catch (HttpClientErrorException.Unauthorized expired) {
            throw new CredentialRequiredException(SYSTEM);
        } catch (HttpClientErrorException other) {
            return onClientError.handle(other);
        } catch (RestClientResponseException | ResourceAccessException statusOrIo) {
            throw statusOrIo;
        } catch (RestClientException unreadable) {
            throw new BackendResponseException("body not readable");
        }
    }

    private static boolean isHidden(HttpClientErrorException error) {
        return error instanceof HttpClientErrorException.NotFound || error instanceof HttpClientErrorException.Forbidden;
    }

    /** 404 and 403 are "not visible": {@code whenHidden} says what that means for the call. */
    private static <T> ClientErrorHandler<T> hidden(Supplier<T> whenHidden) {
        return error -> {
            if (isHidden(error)) {
                return whenHidden.get();
            }
            throw error;
        };
    }

    /** As {@link #hidden}, with 400 turned into an exception of the caller's choice. */
    private static <T> ClientErrorHandler<T> rejected(Supplier<RuntimeException> onBadRequest, Supplier<T> whenHidden) {
        return error -> {
            if (error instanceof HttpClientErrorException.BadRequest) {
                throw onBadRequest.get();
            }
            if (isHidden(error)) {
                return whenHidden.get();
            }
            throw error;
        };
    }

    private static <T> ClientErrorHandler<T> hiddenOrRejected() {
        return rejected(PageRejectedException::new, () -> {
            throw new PageNotFoundException();
        });
    }

    private static <T> ClientErrorHandler<T> conflictHiddenOrRejected() {
        return error -> {
            if (error instanceof HttpClientErrorException.Conflict) {
                throw new PageConflictException();
            }
            return ConfluenceResourceAdapter.<T>hiddenOrRejected().handle(error);
        };
    }

    /** Query parameters in order; a list value repeats the parameter. */
    private static Map<String, Object> query(Object... namesAndValues) {
        Map<String, Object> query = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            query.put((String) namesAndValues[i], namesAndValues[i + 1]);
        }
        return query;
    }

    /** Every value goes in as a template variable, so the builder encodes it and nothing from the model is a path part. */
    private java.util.function.Function<UriBuilder, URI> uriOf(String path, Map<String, Object> pathVariables,
            Map<String, Object> query) {
        return builder -> {
            Map<String, Object> variables = new HashMap<>(pathVariables);
            builder.path(path);
            query.forEach((name, value) -> {
                List<?> values = value instanceof List<?> list ? list : List.of(value);
                for (int i = 0; i < values.size(); i++) {
                    String variable = "q_" + name + "_" + i;
                    builder.queryParam(name, "{" + variable + "}");
                    variables.put(variable, values.get(i));
                }
            });
            return builder.build(variables);
        };
    }

    private <T> T getJson(String path, Map<String, Object> pathVariables, Map<String, Object> query, Class<T> type) {
        BackendCredential credential = credential();
        T body = client.get().uri(uriOf(path, pathVariables, query)).headers(credential::applyTo).retrieve().body(type);
        if (body == null) {
            throw new BackendResponseException("empty body");
        }
        return body;
    }

    private ContentResponse postJson(String path, Map<String, Object> pathVariables, Map<String, Object> query,
            Object payload) {
        return postJson(path, pathVariables, query, payload, ContentResponse.class);
    }

    private <T> T postJson(String path, Map<String, Object> pathVariables, Map<String, Object> query, Object payload,
            Class<T> type) {
        BackendCredential credential = credential();
        T body = client.post().uri(uriOf(path, pathVariables, query)).contentType(MediaType.APPLICATION_JSON)
                .headers(credential::applyTo).body(payload).retrieve().body(type);
        if (body == null) {
            throw new BackendResponseException("empty body");
        }
        return body;
    }

    private ContentResponse putJson(String path, Map<String, Object> pathVariables, Map<String, Object> query,
            Object payload) {
        BackendCredential credential = credential();
        ContentResponse body = client.put().uri(uriOf(path, pathVariables, query)).contentType(MediaType.APPLICATION_JSON)
                .headers(credential::applyTo).body(payload).retrieve().body(ContentResponse.class);
        if (body == null) {
            throw new BackendResponseException("empty body");
        }
        return body;
    }

    /**
     * Confluence Data Center takes personal access tokens (and OAuth access tokens) as {@code Bearer}. Any other
     * scheme cannot work against the API, so the user is sent to the portal to connect a token instead.
     */
    private static BackendCredential credential() {
        BackendCredential credential = CredentialContext.require(SYSTEM);
        if (credential.scheme() != BackendCredential.Scheme.BEARER) {
            throw new CredentialRequiredException(SYSTEM);
        }
        return credential;
    }

    private static String requireBaseUrl(String baseUrl, boolean plainHttpAllowed) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("confluence.backend.base-url (MCP_CONFLUENCE_URL) must point at the backend API");
        }
        URI uri = URI.create(baseUrl);
        if (uri.getHost() == null || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) {
            throw new IllegalStateException("confluence.backend.base-url must be an absolute http(s) URL");
        }
        if (!"https".equals(uri.getScheme())) {
            if (!plainHttpAllowed) {
                // The user's token travels in a header; over http it would cross the network in clear text.
                throw new IllegalStateException("confluence.backend.base-url must use https outside the 'dev' profile "
                        + "(confluence.backend.allow-plain-http exists for the mocks of the dev stack only)");
            }
            LOG.warn("event=backend_plain_http detail=\"credentials go to the backend unencrypted\"");
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private static JdkClientHttpRequestFactory requestFactory(ConfluenceBackendProperties properties,
            ObjectProvider<SslBundles> sslBundles) {
        // Redirects are not followed: the credential header must not travel to wherever a backend points.
        HttpClient.Builder http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER);
        if (properties.sslBundle() != null && !properties.sslBundle().isBlank()) {
            http.sslContext(sslBundles.getObject().getBundle(properties.sslBundle()).createSslContext());
        }
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http.build());
        factory.setReadTimeout(properties.readTimeout());
        return factory;
    }

    // Confluence's representations; unknown fields are dropped here and never reach the model.

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchResponse(List<ContentResponse> results, Integer size, Integer limit, LinksResponse _links) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchResultsResponse(List<SearchResultResponse> results, Integer size, Integer limit, LinksResponse _links) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchResultResponse(TypedRefResponse content, String excerpt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TypedRefResponse(String id, String type) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SpaceListResponse(List<SpaceResponse> results, Integer size, Integer limit, LinksResponse _links) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ContentResponse(String id, String type, String status, String title, SpaceResponse space,
                           VersionResponse version, List<AncestorResponse> ancestors, BodyResponse body,
                           AncestorResponse container, ExtensionsResponse extensions, MetadataResponse metadata,
                           LinksResponse _links) {

        LinksResponse links() {
            return _links;
        }

        /** A content record without an id is an unusable answer, not a missing page. */
        ContentResponse withId() {
            if (id == null || id.isBlank()) {
                throw new BackendResponseException("content without id");
            }
            return this;
        }

        boolean isPage() {
            return "page".equals(type);
        }

        /** Blog posts, comments and attachments share the content API; the tools are about pages only. */
        boolean isCurrentPage() {
            return isPage() && (status == null || "current".equals(status));
        }

        /**
         * Personal spaces ({@code ~user}) have no key the policy can grant or narrow, so their pages are treated as not
         * visible. A page whose reply omits the space cannot be placed anywhere and is the backend's fault.
         */
        boolean isInGlobalSpace() {
            if (spaceKey() == null) {
                throw new BackendResponseException("content without space");
            }
            return ConfluenceIds.isSpaceKey(spaceKey());
        }

        List<String> ancestorIds() {
            return ancestors == null ? List.of() : ancestors.stream().map(AncestorResponse::id).filter(Objects::nonNull).toList();
        }

        PageSummary toSummary() {
            return new PageSummary(id, spaceKey(), title, version == null ? 0 : version.number(),
                    version == null ? null : version.when());
        }

        Page toPage() {
            String storage = body == null || body.storage() == null ? "" : body.storage().value();
            return new Page(id, spaceKey(), title, version == null ? 0 : version.number(),
                    version == null ? null : version.when(), ancestorIds(),
                    StorageFormat.toText(storage, MAX_PAGE_TEXT));
        }

        Comment toComment(String fallbackPageId) {
            String storage = body == null || body.storage() == null ? "" : body.storage().value();
            String parent = ancestorIds().isEmpty() ? null : ancestorIds().getLast();
            String author = version == null || version.by() == null || version.by().displayName() == null ? ""
                    : version.by().displayName();
            String location = extensions != null && "inline".equals(extensions.location()) ? "inline" : "footer";
            String containerId = container != null && container.id() != null ? container.id() : fallbackPageId;
            return new Comment(id, containerId, parent, author, version == null ? null : version.when(), location,
                    StorageFormat.toText(storage, MAX_COMMENT_TEXT));
        }

        Attachment toAttachment() {
            String mediaType = extensions != null && extensions.mediaType() != null ? extensions.mediaType()
                    : metadata != null ? metadata.mediaType() : null;
            long size = extensions != null && extensions.fileSize() != null ? extensions.fileSize() : 0;
            return new Attachment(id, title == null ? "" : title, mediaType == null ? "" : mediaType, size,
                    version == null ? 0 : version.number());
        }

        String spaceKey() {
            return space == null ? null : space.key();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ExtensionsResponse(String location, String mediaType, Long fileSize) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MetadataResponse(String mediaType) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SpaceResponse(String key, String name, SpaceMetadataResponse metadata) {

        Space toSpace() {
            return new Space(key, name == null ? "" : name, labelNames());
        }

        /** Label names, or null when the reply has no label list or says there are more than it lists. */
        List<String> labelNames() {
            if (metadata == null || metadata.labels() == null || metadata.labels().results() == null
                    || !metadata.labels().isComplete()) {
                return null;
            }
            return metadata.labels().results().stream().map(LabelResponse::name).filter(Objects::nonNull).toList();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SpaceMetadataResponse(LabelsResponse labels) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LabelsResponse(List<LabelResponse> results, Integer size, Integer limit, LinksResponse _links) {

        /**
         * Complete only when the answer says how long it is and that it stopped before its limit. A list that fills
         * its page ({@code size >= limit}), has no {@code size} or {@code limit}, disagrees with its own size or links on
         * to a next page may hide the label that classifies the space, and a classification that may be incomplete is
         * unknown, not empty.
         */
        boolean isComplete() {
            return size != null && limit != null && size == results.size() && size < limit
                    && (_links == null || _links.next() == null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LabelListResponse(List<LabelResponse> results, LinksResponse _links) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LabelResponse(String prefix, String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LinksResponse(String next, String download) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UserResponse(String displayName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record VersionResponse(int number, String when, UserResponse by, String message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AncestorResponse(String id) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BodyResponse(StorageResponse storage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StorageResponse(String value) {
    }

    record CreateRequest(String type, String title, SpaceRef space, List<AncestorRef> ancestors, Body body) {
    }

    record SpaceRef(String key) {
    }

    record AncestorRef(String id) {
    }

    record Body(Storage storage) {
    }

    record Storage(String value, String representation) {
    }
}
