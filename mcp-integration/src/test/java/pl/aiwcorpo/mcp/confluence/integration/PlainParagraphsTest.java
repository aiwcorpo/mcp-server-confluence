package pl.aiwcorpo.mcp.confluence.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** A page may be replaced by plain text only when nothing but paragraphs would be lost. */
class PlainParagraphsTest {

    @Test
    void textThisServerWroteRoundTrips() {
        assertThat(StorageFormat.isPlainParagraphs(StorageFormat.fromPlainText("one\ntwo\n\nthree & <b>"))).isTrue();
        assertThat(StorageFormat.isPlainParagraphs("")).isTrue();
    }

    @Test
    void anythingRichIsNotPlain() {
        assertThat(StorageFormat.isPlainParagraphs("<p>a</p><h1>b</h1>")).isFalse();
        assertThat(StorageFormat.isPlainParagraphs("<p>a <strong>b</strong></p>")).isFalse();
        assertThat(StorageFormat.isPlainParagraphs("<ul><li>a</li></ul>")).isFalse();
        assertThat(StorageFormat.isPlainParagraphs("<p>a</p><ac:structured-macro ac:name=\"toc\"/>")).isFalse();
        assertThat(StorageFormat.isPlainParagraphs("<p><ac:image><ri:attachment ri:filename=\"a.png\"/></ac:image></p>")).isFalse();
        assertThat(StorageFormat.isPlainParagraphs("loose text outside a paragraph")).isFalse();
    }
}
