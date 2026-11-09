package pl.aiwcorpo.mcp.confluence.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TextDiffTest {

    private static String diff(String from, String to) {
        return TextDiff.unified(TextDiff.lines(from), TextDiff.lines(to), "a", "b");
    }

    @Test
    void equalTextsGiveNoDiff() {
        assertThat(diff("one\ntwo", "one\ntwo")).isEmpty();
        assertThat(diff("", "")).isEmpty();
    }

    @Test
    void aChangedLineIsRemovedAndAddedWithContext() {
        assertThat(diff("a\nb\nc\nd", "a\nB\nc\nd")).isEqualTo("""
                --- a
                +++ b
                @@ -1,4 +1,4 @@
                 a
                -b
                +B
                 c
                 d""");
    }

    @Test
    void anInsertionAndADeletionAreSeparateHunksWhenFarApart() {
        List<String> old = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            old.add("line " + i);
        }
        List<String> changed = new ArrayList<>(old);
        changed.remove(1);
        changed.add(18, "added");

        String result = TextDiff.unified(old, changed, "a", "b");

        assertThat(result).contains("@@ -1,5 +1,4 @@", "-line 2", "+added");
        assertThat(result.lines().filter(line -> line.startsWith("@@"))).hasSize(2);
        assertThat(result).doesNotContain(" line 9\n");
    }

    @Test
    void textAddedToAnEmptyPageIsAllPlus() {
        assertThat(diff("", "x\ny")).isEqualTo("""
                --- a
                +++ b
                @@ -0,0 +1,2 @@
                +x
                +y""");
    }

    @Test
    void aReplacedBlockKeepsTheLongestCommonLines() {
        String result = diff("a\nb\nc\nd\ne", "a\nc\nx\ne");

        assertThat(result).contains("-b", " c", "-d", "+x").doesNotContain("-c", "-a");
    }

    @Test
    void aChangeTooLargeToCompareIsRefused() {
        List<String> left = new ArrayList<>();
        List<String> right = new ArrayList<>();
        for (int i = 0; i < 2_500; i++) {
            left.add("l" + i);
            right.add("r" + i);
        }

        assertThatThrownBy(() -> TextDiff.unified(left, right, "a", "b")).isInstanceOf(TextDiff.TooLargeException.class);
    }
}
