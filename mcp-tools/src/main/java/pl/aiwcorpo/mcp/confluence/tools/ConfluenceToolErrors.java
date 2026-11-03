package pl.aiwcorpo.mcp.confluence.tools;

import java.util.Optional;
import org.springframework.stereotype.Component;
import pl.aiwcorpo.mcp.confluence.core.domain.PageConflictException;
import pl.aiwcorpo.mcp.confluence.core.domain.PageNotEditableException;
import pl.aiwcorpo.mcp.confluence.core.domain.PageNotFoundException;
import pl.aiwcorpo.mcp.confluence.core.domain.PageRejectedException;
import pl.aiwcorpo.mcp.confluence.core.domain.WritesNotVerifiedException;
import pl.aiwcorpo.mcp.platform.error.McpErrorMapper.SafeError;
import pl.aiwcorpo.mcp.platform.error.ToolErrorCode;
import pl.aiwcorpo.mcp.platform.error.ToolErrorMapping;

/** Domain exceptions of this server as C10 errors; everything else falls through to the starter's defaults. */
@Component
public class ConfluenceToolErrors implements ToolErrorMapping {

    @Override
    public Optional<SafeError> map(Throwable ex) {
        if (ex instanceof PageNotFoundException) {
            return Optional.of(new SafeError(ToolErrorCode.NOT_FOUND_OR_DENIED.code(),
                    ToolErrorCode.NOT_FOUND_OR_DENIED_MESSAGE));
        }
        if (ex instanceof PageRejectedException) {
            return Optional.of(new SafeError(ToolErrorCode.INVALID_ARGUMENT.code(),
                    "Confluence rejected the change, for example because the space already has a page with this title."));
        }
        if (ex instanceof PageConflictException) {
            return Optional.of(new SafeError(ToolErrorCode.INVALID_ARGUMENT.code(),
                    "The page is not at the version you read. Read it again and repeat the edit with its current version."));
        }
        if (ex instanceof PageNotEditableException) {
            return Optional.of(new SafeError(ToolErrorCode.INVALID_ARGUMENT.code(),
                    "The page has formatting that replacing its text would remove. Use mode append, or edit it in Confluence."));
        }
        if (ex instanceof WritesNotVerifiedException) {
            return Optional.of(new SafeError(ToolErrorCode.UPSTREAM_UNAVAILABLE.code(),
                    "Changes are switched off because this Confluence version has not been verified. Reading still works."));
        }
        return Optional.empty();
    }
}
