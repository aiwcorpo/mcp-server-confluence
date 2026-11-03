package pl.aiwcorpo.mcp.confluence.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import pl.aiwcorpo.mcp.confluence.core.domain.PageNotFoundException;
import pl.aiwcorpo.mcp.confluence.core.port.out.ConfluenceResourcePort;
import pl.aiwcorpo.mcp.platform.security.CredentialHeaderFilter;

/**
 * The adapter's delete, which the sample policy bundle never lets a tool call reach: driven directly, with the
 * credential bound by the starter's own filter, against the stub.
 */
class AdapterWritesTest extends ServerTestSupport {

    @Autowired
    ConfluenceResourcePort port;

    @BeforeEach
    void resetBackend() {
        BACKEND.reset();
    }

    private void asUser(Runnable action) throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("X-MCP-Credential", PAT);
        request.addHeader("X-MCP-Credential-Type", "user-secret");
        new CredentialHeaderFilter(SYSTEM, null).doFilter(request, new MockHttpServletResponse(),
                new MockFilterChain() {
                    @Override
                    public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                        action.run();
                    }
                });
    }

    @Test
    void trashingDeletesTheContentAsTheUser() throws Exception {
        asUser(() -> port.trashPage("1000"));

        assertThat(BACKEND.requests()).hasSize(1).allSatisfy(request -> {
            assertThat(request.method()).isEqualTo("DELETE");
            assertThat(request.path()).isEqualTo("/rest/api/content/1000");
            assertThat(request.authorization()).isEqualTo(PAT);
        });
    }

    @Test
    void missingAndForbiddenPagesAreTheSameNotFound() throws Exception {
        for (String id : List.of("1403", "1404")) {
            asUser(() -> assertThatThrownBy(() -> port.trashPage(id)).isInstanceOf(PageNotFoundException.class));
        }
    }
}
