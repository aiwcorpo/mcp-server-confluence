package pl.aiwcorpo.mcp.confluence.core.port.out;

import java.util.Optional;
import pl.aiwcorpo.mcp.confluence.core.domain.ConfluenceRecord;

/** Outbound port to the backend system, implemented in mcp-integration. */
public interface ConfluenceResourcePort {

    /** Empty when the backend does not show the record to the caller, whether it exists or not. */
    Optional<ConfluenceRecord> findById(String recordId);
}
