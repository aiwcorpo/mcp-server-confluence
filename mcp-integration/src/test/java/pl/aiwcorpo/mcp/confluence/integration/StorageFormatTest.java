package pl.aiwcorpo.mcp.confluence.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.confluence.core.domain.PageText;

class StorageFormatTest {

    @Test
    void includeAndExcerptIncludeAreDroppedWithTheirReferences() {
        PageText text = StorageFormat.toText("""
                <p>Before</p>
                <ac:structured-macro ac:name="include" ac:schema-version="1">
                  <ac:parameter ac:name=""><ac:link><ri:page ri:space-key="HR" ri:content-title="Salaries" /></ac:link></ac:parameter>
                </ac:structured-macro>
                <ac:structured-macro ac:name="excerpt-include">
                  <ac:parameter ac:name="nopanel">true</ac:parameter>
                  <ac:parameter ac:name="">HR:Salaries</ac:parameter>
                </ac:structured-macro>
                <ac:macro ac:name="include"><ac:default-parameter>HR:Bonuses</ac:default-parameter></ac:macro>
                <p>After</p>""", 1000);

        assertThat(text.text()).contains("Before", "After", "[macro omitted: include]",
                "[macro omitted: excerpt-include]").doesNotContain("HR", "Salaries", "Bonuses");
        assertThat(text.omittedMacros()).containsExactly("include", "excerpt-include");
    }

    @Test
    void unknownMacrosAreDroppedAndLocalBodiesKept() {
        PageText text = StorageFormat.toText("""
                <ac:structured-macro ac:name="info"><ac:parameter ac:name="title">Heads up</ac:parameter>
                  <ac:rich-text-body><p>Freeze on Friday</p></ac:rich-text-body></ac:structured-macro>
                <ac:structured-macro ac:name="code"><ac:plain-text-body><![CDATA[if (a < b) { run(); }]]></ac:plain-text-body></ac:structured-macro>
                <ac:structured-macro ac:name="contentbylabel"><ac:parameter ac:name="spaces">HR</ac:parameter></ac:structured-macro>
                <ac:structured-macro ac:name="Evil&quot; name"><ac:rich-text-body><p>hidden</p></ac:rich-text-body></ac:structured-macro>
                """, 1000);

        assertThat(text.text()).contains("Freeze on Friday", "if (a < b) { run(); }",
                "[macro omitted: contentbylabel]", "[macro omitted: unknown]")
                .doesNotContain("Heads up", "HR", "hidden", "Evil");
        assertThat(text.omittedMacros()).containsExactly("contentbylabel", "unknown");
    }

    @Test
    void markupBecomesPlainText() {
        PageText text = StorageFormat.toText("""
                <h1>Release&nbsp;notes</h1><p>Version <strong>2.1</strong> &amp; more</p>
                <ul><li>first</li><li>second</li></ul>
                <table><tbody><tr><th>Key</th><th>Value</th></tr><tr><td>a</td><td>1</td></tr></tbody></table>
                <p><ac:link><ri:page ri:content-title="Other" /><ac:plain-text-link-body><![CDATA[see other]]></ac:plain-text-link-body></ac:link></p>
                """, 1000);

        assertThat(text.text()).contains("Release notes", "Version 2.1 & more", "- first\n- second",
                "Key | Value |", "see other").doesNotContain("<", "strong");
        assertThat(text.truncated()).isFalse();
    }

    @Test
    void longTextIsTruncated() {
        PageText text = StorageFormat.toText("<p>" + "x".repeat(200) + "</p>", 50);

        assertThat(text.text()).hasSize(50);
        assertThat(text.truncated()).isTrue();
    }

    @Test
    void plainTextCannotBecomeMarkup() {
        String storage = StorageFormat.fromPlainText("""
                Line one
                <ac:structured-macro ac:name="include"/> & "quotes" 'single'

                <script>alert(1)</script>""");

        assertThat(storage).isEqualTo("<p>Line one<br />&lt;ac:structured-macro ac:name=&quot;include&quot;/&gt; "
                + "&amp; &quot;quotes&quot; &#39;single&#39;</p><p>&lt;script&gt;alert(1)&lt;/script&gt;</p>");
        assertThat(StorageFormat.toText(storage, 1000).omittedMacros()).isEmpty();
    }

    @Test
    void cqlKeepsTheSpaceAndReducesTextToWords() {
        assertThat(Cql.pagesInSpaceMatching("ENG", "release\" OR space = \"HR\" notes*"))
                .isEqualTo("space = \"ENG\" AND type = page AND text ~ \"release OR space HR notes\"");
        assertThat(Cql.words("a\\b (c) [d] {e} ~f ?g :h +i -j !k &l |m ^n /o 'p' q.r"))
                .isEqualTo("a b c d e f g h i j k l m n o 'p' q.r");
        assertThat(Cql.quoted("a\"b\\c")).isEqualTo("\"a\\\"b\\\\c\"");
    }
}
