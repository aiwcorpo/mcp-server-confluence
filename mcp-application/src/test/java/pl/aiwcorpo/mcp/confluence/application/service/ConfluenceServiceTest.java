package pl.aiwcorpo.mcp.confluence.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.confluence.core.domain.ConfluenceRecord;
import pl.aiwcorpo.mcp.confluence.core.domain.RecordNotFoundException;

class ConfluenceServiceTest {

    private final Map<String, ConfluenceRecord> backend = Map.of("ABC-1", new ConfluenceRecord("ABC-1", "ABC", "First", "open"));
    private final ConfluenceService service = new ConfluenceService(id -> Optional.ofNullable(backend.get(id)));

    @Test
    void returnsTheRecordTheBackendShows() {
        assertThat(service.getRecord("ABC-1").title()).isEqualTo("First");
    }

    @Test
    void aRecordTheBackendDoesNotShowIsNotFoundOrDenied() {
        assertThatThrownBy(() -> service.getRecord("ABC-2")).isInstanceOf(RecordNotFoundException.class);
    }
}
