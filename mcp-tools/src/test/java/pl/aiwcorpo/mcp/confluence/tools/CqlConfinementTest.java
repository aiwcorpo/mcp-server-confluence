package pl.aiwcorpo.mcp.confluence.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.platform.error.InvalidToolArgumentException;

class CqlConfinementTest {

    private static final String SCOPE = "space in (\"ENG\")";

    private static String confine(String cql) {
        return CqlConfinement.confine(cql, SCOPE);
    }

    @Test
    void theConditionIsWrappedAndTheServersScopeIsAndedOutsideIt() {
        assertThat(confine("text ~ \"release\" OR label = \"handbook\""))
                .isEqualTo("(text ~ \"release\" OR label = \"handbook\") AND space in (\"ENG\") AND type = page");
        assertThat(confine("  created >= now(\"-1w\") AND (title ~ 'a' OR title ~ \"b\")  "))
                .isEqualTo("(created >= now(\"-1w\") AND (title ~ 'a' OR title ~ \"b\")) AND space in (\"ENG\") AND type = page");
    }

    @Test
    void aTrailingOrderByMovesAfterTheScope() {
        assertThat(confine("title ~ \"x\" order by lastmodified desc"))
                .isEqualTo("(title ~ \"x\") AND space in (\"ENG\") AND type = page ORDER BY lastmodified DESC");
        assertThat(confine("text ~ \"x\" ORDER BY title"))
                .isEqualTo("(text ~ \"x\") AND space in (\"ENG\") AND type = page ORDER BY title");
        assertThat(confine("text ~ \"order by space\"")).as("inside a string it is text")
                .isEqualTo("(text ~ \"order by space\") AND space in (\"ENG\") AND type = page");
    }

    @Test
    void textThatTriesToCloseTheParenthesisOrTheQuoteIsRefused() {
        List<String> attempts = List.of(
                "text ~ \"a\") OR space = \"HR\" OR (text ~ \"b\"",
                "text ~ \"a\") OR (space = \"HR\"",
                ") OR space = HR OR (",
                "text ~ \"a\" OR (space = HR",
                "text ~ \"a",
                "text ~ 'a\" OR space = HR",
                "text ~ \"a\\\"",
                "text ~ a\\ OR space = HR",
                "text ~ a; space = HR",
                "text ~ a*",
                "text ~ a\nOR space = HR",
                "text ~ \"a\nb\"",
                "space = HR {x}",
                "");
        for (String attempt : attempts) {
            assertThatThrownBy(() -> confine(attempt)).as(attempt).isInstanceOf(InvalidToolArgumentException.class);
        }
    }

    @Test
    void anEscapedQuoteStaysInsideItsStringAndTheScopeStaysOutside() {
        String confined = confine("text ~ \"a\\\" OR space = \\\"HR\"");

        assertThat(confined).startsWith("(text ~ \"a\\\" OR space = \\\"HR\")").endsWith(" AND space in (\"ENG\") AND type = page");
    }

    @Test
    void orderByAnywhereElseOrOnOtherFieldsIsRefused() {
        for (String attempt : List.of("(title ~ \"a\" order by title)", "text ~ \"a\" order by title order by created",
                "text ~ \"a\" order by space", "text ~ \"a\" order by", "order by title", "text ~ \"a\" order by title, created",
                "text ~ \"a\" order bytitle")) {
            assertThatThrownBy(() -> confine(attempt)).as(attempt).isInstanceOf(InvalidToolArgumentException.class);
        }
    }

    @Test
    void theLengthIsCapped() {
        assertThatThrownBy(() -> confine("text ~ \"" + "a".repeat(CqlConfinement.MAX_LENGTH) + "\""))
                .isInstanceOf(InvalidToolArgumentException.class);
        assertThat(confine("text ~ \"" + "a".repeat(CqlConfinement.MAX_LENGTH - 10) + "\"")).startsWith("(text");
    }
}
