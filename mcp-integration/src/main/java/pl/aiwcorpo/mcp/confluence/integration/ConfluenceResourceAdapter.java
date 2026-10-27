package pl.aiwcorpo.mcp.confluence.integration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
import pl.aiwcorpo.mcp.confluence.core.domain.NewPage;
import pl.aiwcorpo.mcp.confluence.core.domain.Page;
import pl.aiwcorpo.mcp.confluence.core.domain.PageNotFoundException;
import pl.aiwcorpo.mcp.confluence.core.domain.PageRejectedException;
import pl.aiwcorpo.mcp.confluence.core.domain.PageSummary;
import pl.aiwcorpo.mcp.confluence.core.port.out.ConfluenceResourcePort;
import pl.aiwcorpo.mcp.platform.security.BackendCredential;
import pl.aiwcorpo.mcp.platform.security.CredentialContext;
import pl.aiwcorpo.mcp.platform.error.InvalidToolArgumentException;
import pl.aiwcorpo.mcp.platform.security.CredentialRequiredException;

/**
 * Confluence Data Center REST API ({@code /rest/api/content}) as the calling user: with the personal access
 * token the gateway resolved for this request (C3, {@code Authorization: Bearer}), never with an account of
 * the server.
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
    static final String PAGE_EXPAND = "body.storage,space,version,ancestors";
    static final String SUMMARY_EXPAND = "space,version";

    /** Size limit of the page text handed to the model. */
    static final int MAX_PAGE_TEXT = 50_000;

    private static final String DEV_PROFILE = "dev";
    private static final Logger LOG = LoggerFactory.getLogger(ConfluenceResourceAdapter.class);

    private final RestClient client;

    public ConfluenceResourceAdapter(ConfluenceBackendProperties properties, ObjectProvider<SslBundles> sslBundles,
            Environment environment) {
        this.client = RestClient.builder()
                .baseUrl(requireBaseUrl(properties.baseUrl(),
                        environment.matchesProfiles(DEV_PROFILE) || properties.allowPlainHttp()))
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

    @Override
    public List<PageSummary> searchPages(String spaceKey, String text, int limit) {
        BackendCredential credential = credential();
        try {
            SearchResponse response = client.get()
                    .uri(uri -> uri.path(CONTENT + "/search")
                            .queryParam("cql", "{cql}")
                            .queryParam("limit", limit)
                            .queryParam("expand", SUMMARY_EXPAND)
                            .build(Map.of("cql", Cql.pagesInSpaceMatching(spaceKey, text))))
                    .headers(credential::applyTo)
                    .retrieve()
                    .body(SearchResponse.class);
            if (response == null || response.results() == null) {
                throw new BackendResponseException("empty body");
            }
            return response.results().stream()
                    .map(ContentResponse::withId)
                    .filter(ContentResponse::isCurrentPage)
                    .map(ContentResponse::toSummary)
                    .toList();
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.Forbidden hidden) {
            return List.of();
        } catch (HttpClientErrorException.Unauthorized expired) {
            throw new CredentialRequiredException(SYSTEM);
        } catch (HttpClientErrorException.BadRequest rejected) {
            // Text made only of words CQL reserves; the value stays out of the message (it came from the model).
            throw new InvalidToolArgumentException("Confluence did not accept the search words; try other words");
        } catch (RestClientResponseException | ResourceAccessException statusOrIo) {
            throw statusOrIo;
        } catch (RestClientException unreadable) {
            throw new BackendResponseException("body not readable");
        }
    }

    @Override
    public Optional<Page> findPage(String pageId) {
        return fetch(pageId, PAGE_EXPAND).map(ContentResponse::toPage);
    }

    @Override
    public Optional<PageSummary> findPageSummary(String pageId) {
        return fetch(pageId, SUMMARY_EXPAND).map(ContentResponse::toSummary);
    }

    @Override
    public PageSummary createPage(NewPage page) {
        BackendCredential credential = credential();
        CreateRequest request = new CreateRequest("page", page.title(), new SpaceRef(page.spaceKey()),
                List.of(new AncestorRef(page.parentId())),
                new Body(new Storage(StorageFormat.fromPlainText(page.body()), "storage")));
        try {
            ContentResponse created = client.post()
                    .uri(CONTENT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(credential::applyTo)
                    .body(request)
                    .retrieve()
                    .body(ContentResponse.class);
            if (created == null) {
                throw new BackendResponseException("empty body");
            }
            return created.withId().toSummary();
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.Forbidden hidden) {
            throw new PageNotFoundException();
        } catch (HttpClientErrorException.Unauthorized expired) {
            throw new CredentialRequiredException(SYSTEM);
        } catch (HttpClientErrorException.BadRequest rejected) {
            throw new PageRejectedException();
        } catch (RestClientResponseException | ResourceAccessException statusOrIo) {
            throw statusOrIo;
        } catch (RestClientException unreadable) {
            throw new BackendResponseException("body not readable");
        }
    }

    private Optional<ContentResponse> fetch(String pageId, String expand) {
        BackendCredential credential = credential();
        try {
            ContentResponse response = client.get()
                    .uri(uri -> uri.path(CONTENT + "/{id}").queryParam("expand", expand).build(pageId))
                    .headers(credential::applyTo)
                    .retrieve()
                    .body(ContentResponse.class);
            if (response == null) {
                throw new BackendResponseException("empty body");
            }
            return Optional.of(response.withId()).filter(ContentResponse::isCurrentPage);
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.Forbidden hidden) {
            return Optional.empty();
        } catch (HttpClientErrorException.Unauthorized expired) {
            throw new CredentialRequiredException(SYSTEM);
        } catch (RestClientResponseException | ResourceAccessException statusOrIo) {
            throw statusOrIo;
        } catch (RestClientException unreadable) {
            throw new BackendResponseException("body not readable");
        }
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
                        + "(confluence.backend.allow-plain-http exists for the mocks of mcp-platform-dev only)");
            }
            LOG.warn("event=backend_plain_http detail=\"credentials go to the backend unencrypted\"");
        }
        return baseUrl;
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
    record SearchResponse(List<ContentResponse> results) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ContentResponse(String id, String type, String status, String title, SpaceResponse space,
                           VersionResponse version, List<AncestorResponse> ancestors, BodyResponse body) {

        /** A content record without an id is an unusable answer, not a missing page. */
        ContentResponse withId() {
            if (id == null || id.isBlank()) {
                throw new BackendResponseException("content without id");
            }
            return this;
        }

        /** Blog posts, comments and attachments share the content API; the tools are about pages only. */
        boolean isCurrentPage() {
            return "page".equals(type) && (status == null || "current".equals(status));
        }

        PageSummary toSummary() {
            return new PageSummary(id, spaceKey(), title, version == null ? 0 : version.number(),
                    version == null ? null : version.when());
        }

        Page toPage() {
            List<String> ancestorIds = ancestors == null ? List.of()
                    : ancestors.stream().map(AncestorResponse::id).filter(Objects::nonNull).toList();
            String storage = body == null || body.storage() == null ? "" : body.storage().value();
            return new Page(id, spaceKey(), title, version == null ? 0 : version.number(),
                    version == null ? null : version.when(), ancestorIds,
                    StorageFormat.toText(storage, MAX_PAGE_TEXT));
        }

        private String spaceKey() {
            return space == null ? null : space.key();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SpaceResponse(String key) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record VersionResponse(int number, String when) {
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
