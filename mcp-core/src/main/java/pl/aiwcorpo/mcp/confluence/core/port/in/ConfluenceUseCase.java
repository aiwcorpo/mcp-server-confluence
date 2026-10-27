package pl.aiwcorpo.mcp.confluence.core.port.in;

import pl.aiwcorpo.mcp.confluence.core.domain.ConfluenceRecord;

/** Inbound port: what the server can do, in business terms. Implemented in mcp-application, called by mcp-tools. */
public interface ConfluenceUseCase {

    /**
     * @throws pl.aiwcorpo.mcp.confluence.core.domain.RecordNotFoundException when the record does not exist or
     *         the caller's backend account may not see it
     */
    ConfluenceRecord getRecord(String recordId);
}
