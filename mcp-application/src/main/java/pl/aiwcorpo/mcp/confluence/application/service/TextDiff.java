package pl.aiwcorpo.mcp.confluence.application.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Unified line diff on a longest-common-subsequence table. The JDK has no diff and Confluence Data Center has no diff
 * endpoint; the table is quadratic, so the caller bounds the number of lines and the number of diffs running at once,
 * and {@link #unified} refuses a comparison whose table would exceed {@link #MAX_CELLS} (2 MB: a cell is a short, which
 * holds any common length below that bound).
 */
final class TextDiff {

    static final int CONTEXT = 3;
    static final long MAX_CELLS = 1_000_000L;

    private TextDiff() {
    }

    /** Raised when the changed middle of the two texts is too large to compare. */
    static final class TooLargeException extends RuntimeException {
        TooLargeException() {
            super("diff too large", null, false, false);
        }
    }

    static List<String> lines(String text) {
        return text.isEmpty() ? List.of() : Arrays.asList(text.split("\n", -1));
    }

    /** @return the unified diff with {@link #CONTEXT} lines around each change, or an empty string for equal texts */
    static String unified(List<String> from, List<String> to, String fromLabel, String toLabel) {
        int prefix = 0;
        int limit = Math.min(from.size(), to.size());
        while (prefix < limit && from.get(prefix).equals(to.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < limit - prefix && from.get(from.size() - 1 - suffix).equals(to.get(to.size() - 1 - suffix))) {
            suffix++;
        }
        List<String> oldMiddle = from.subList(prefix, from.size() - suffix);
        List<String> newMiddle = to.subList(prefix, to.size() - suffix);
        if (oldMiddle.isEmpty() && newMiddle.isEmpty()) {
            return "";
        }
        if ((long) (oldMiddle.size() + 1) * (newMiddle.size() + 1) > MAX_CELLS) {
            throw new TooLargeException();
        }
        List<String> script = new ArrayList<>();
        for (int i = 0; i < prefix; i++) {
            script.add(" " + from.get(i));
        }
        script.addAll(edits(oldMiddle, newMiddle));
        for (int i = from.size() - suffix; i < from.size(); i++) {
            script.add(" " + from.get(i));
        }
        return render(script, fromLabel, toLabel);
    }

    private static List<String> edits(List<String> a, List<String> b) {
        int n = a.size();
        int m = b.size();
        short[][] common = new short[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                common[i][j] = a.get(i).equals(b.get(j)) ? (short) (common[i + 1][j + 1] + 1)
                        : (short) Math.max(common[i + 1][j], common[i][j + 1]);
            }
        }
        List<String> script = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (a.get(i).equals(b.get(j))) {
                script.add(" " + a.get(i));
                i++;
                j++;
            } else if (common[i + 1][j] >= common[i][j + 1]) {
                script.add("-" + a.get(i++));
            } else {
                script.add("+" + b.get(j++));
            }
        }
        while (i < n) {
            script.add("-" + a.get(i++));
        }
        while (j < m) {
            script.add("+" + b.get(j++));
        }
        return script;
    }

    private static String render(List<String> script, String fromLabel, String toLabel) {
        int size = script.size();
        boolean[] show = new boolean[size];
        for (int k = 0; k < size; k++) {
            if (script.get(k).charAt(0) != ' ') {
                for (int c = Math.max(0, k - CONTEXT); c <= Math.min(size - 1, k + CONTEXT); c++) {
                    show[c] = true;
                }
            }
        }
        StringBuilder out = new StringBuilder("--- ").append(fromLabel).append('\n').append("+++ ").append(toLabel);
        int oldLine = 1;
        int newLine = 1;
        int k = 0;
        while (k < size) {
            if (!show[k]) {
                oldLine++;
                newLine++;
                k++;
                continue;
            }
            int end = k;
            int oldCount = 0;
            int newCount = 0;
            while (end < size && show[end]) {
                char mark = script.get(end).charAt(0);
                if (mark != '+') {
                    oldCount++;
                }
                if (mark != '-') {
                    newCount++;
                }
                end++;
            }
            out.append('\n').append("@@ -").append(oldCount == 0 ? oldLine - 1 : oldLine).append(',').append(oldCount)
                    .append(" +").append(newCount == 0 ? newLine - 1 : newLine).append(',').append(newCount)
                    .append(" @@");
            for (int line = k; line < end; line++) {
                out.append('\n').append(script.get(line));
                char mark = script.get(line).charAt(0);
                if (mark != '+') {
                    oldLine++;
                }
                if (mark != '-') {
                    newLine++;
                }
            }
            k = end;
        }
        return out.toString();
    }
}
