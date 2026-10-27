package pl.aiwcorpo.mcp.confluence.application.service;

import pl.aiwcorpo.mcp.confluence.core.domain.ConfluenceRecord;
import pl.aiwcorpo.mcp.confluence.core.domain.RecordNotFoundException;
import pl.aiwcorpo.mcp.confluence.core.port.in.ConfluenceUseCase;
import pl.aiwcorpo.mcp.confluence.core.port.out.ConfluenceResourcePort;

/** Use cases of the server. A plain class: mcp-app makes it a bean, so this module stays free of Spring. */
public class ConfluenceService implements ConfluenceUseCase {

    private final ConfluenceResourcePort resource;

    public ConfluenceService(ConfluenceResourcePort resource) {
        this.resource = resource;
    }

    @Override
    public ConfluenceRecord getRecord(String recordId) {
        return resource.findById(recordId).orElseThrow(RecordNotFoundException::new);
    }
}
