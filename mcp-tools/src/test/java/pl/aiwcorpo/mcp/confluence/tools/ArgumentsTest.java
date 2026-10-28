package pl.aiwcorpo.mcp.confluence.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ArgumentsTest {

    @Test
    void searchTextIsReducedToWords() {
        assertThat(Arguments.searchWords("release\" OR space = \"HR\" notes*"))
                .isEqualTo("release OR space HR notes");
        assertThat(Arguments.searchWords("a\\b (c) [d] {e} ~f ?g :h +i -j !k &l |m ^n /o 'p' q.r"))
                .isEqualTo("a b c d e f g h i j k l m n o 'p' q.r");
    }
}
