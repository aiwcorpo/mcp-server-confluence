package pl.aiwcorpo.mcp.confluence.integration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.jsoup.select.NodeVisitor;
import pl.aiwcorpo.mcp.confluence.core.domain.PageText;
import pl.aiwcorpo.mcp.platform.text.TextHygiene;
import pl.aiwcorpo.mcp.platform.text.UntrustedText;

/**
 * Confluence storage format (XHTML with {@code ac:} and {@code ri:} elements) to plain text and back.
 *
 * <p>Reading works on the page's own storage, never on the rendered view: Confluence renders include and
 * excerpt-include macros by copying in content of other pages, possibly from other spaces the policy never
 * looked at. Here a macro is kept only when it is on a short allow list of macros whose body is written on
 * this page (code, panels, expand, the excerpt definition); every other macro is dropped with its parameters
 * and reported by name. An unknown macro is dropped, not kept.
 *
 * <p>Writing escapes every markup character, so the model's text can never become a macro or an HTML element.
 */
final class StorageFormat {

    /** Macros whose content is part of this page's own storage; their parameters are still dropped. */
    private static final Set<String> LOCAL_BODY_MACROS = Set.of(
            "code", "noformat", "info", "note", "warning", "tip", "panel", "expand", "excerpt");

    private static final Set<String> MACRO_ELEMENTS = Set.of("ac:structured-macro", "ac:macro");

    /** Elements without readable content: macro parameters, placeholders shown only in the editor, task ids. */
    private static final Set<String> DROPPED_ELEMENTS = Set.of(
            "ac:parameter", "ac:placeholder", "ac:task-id", "ac:task-status", "ac:task-uuid");

    private static final Set<String> BLOCK_ELEMENTS = Set.of(
            "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "pre", "blockquote", "ul", "ol", "table",
            "hr", "ac:plain-text-body", "ac:rich-text-body", "ac:task", "ac:layout-section", "ac:layout-cell");

    /** Start a new line but, unlike blocks, are not separated from their siblings by a blank line. */
    private static final Set<String> LINE_ELEMENTS = Set.of("li", "tr", "br");

    private static final Set<String> PRESERVE_WHITESPACE = Set.of("pre", "ac:plain-text-body");

    private static final Pattern MACRO_NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");

    private StorageFormat() {
    }

    static PageText toText(String storage, int maxChars) {
        Document document = Jsoup.parse(storage == null ? "" : storage, "", Parser.xmlParser());
        Set<String> omitted = new LinkedHashSet<>();
        for (Element element : new ArrayList<>(document.getAllElements())) {
            if (element.ownerDocument() != document) {
                continue; // inside a macro that was already dropped
            }
            String tag = element.normalName();
            if (MACRO_ELEMENTS.contains(tag)) {
                String name = macroName(element);
                if (!LOCAL_BODY_MACROS.contains(name)) {
                    omitted.add(name);
                    element.replaceWith(new TextNode(" [macro omitted: " + name + "] "));
                }
            } else if (DROPPED_ELEMENTS.contains(tag)) {
                element.remove();
            }
        }
        // Order for text from a system: conversion (above, entities decoded by the parser), normalize, cut, server
        // filter, plainCut. The filter here is structural: macros, images and link targets are dropped as elements,
        // which no invisible character in the text can hide, so nothing is left to filter after the cut.
        String text = TextHygiene.normalize(render(document));
        String cut = UntrustedText.cut(text, maxChars);
        return new PageText(cut, List.copyOf(omitted), cut.length() < text.length());
    }

    /**
     * Paragraphs separated by blank lines become {@code <p>} elements, single line breaks {@code <br />}.
     * Every {@code & < > " '} is escaped, so nothing in {@code text} is interpreted as storage markup.
     */
    static String fromPlainText(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (normalized.isEmpty()) {
            return "";
        }
        StringBuilder storage = new StringBuilder();
        for (String paragraph : normalized.split("\n\\s*\n")) {
            String trimmed = paragraph.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            storage.append("<p>");
            String[] lines = trimmed.split("\n");
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) {
                    storage.append("<br />");
                }
                storage.append(escape(lines[i]));
            }
            storage.append("</p>");
        }
        return storage.toString();
    }

    private static String macroName(Element macro) {
        String name = macro.attr("ac:name").toLowerCase(Locale.ROOT);
        // The name comes from page content and is echoed to the model; anything unusual is not repeated.
        return MACRO_NAME.matcher(name).matches() ? name : "unknown";
    }

    private static String escape(String line) {
        StringBuilder escaped = new StringBuilder(line.length());
        for (char c : line.toCharArray()) {
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    private static String render(Document document) {
        StringBuilder out = new StringBuilder();
        document.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode text) {
                    out.append(insidePreformatted(text) ? text.getWholeText() : collapse(text.getWholeText()));
                } else if (node instanceof Element element) {
                    String tag = element.normalName();
                    if (BLOCK_ELEMENTS.contains(tag) || LINE_ELEMENTS.contains(tag)) {
                        out.append('\n');
                    }
                    if ("li".equals(tag)) {
                        out.append("- ");
                    }
                }
            }

            @Override
            public void tail(Node node, int depth) {
                if (node instanceof Element element) {
                    String tag = element.normalName();
                    if (BLOCK_ELEMENTS.contains(tag)) {
                        out.append('\n');
                    } else if ("td".equals(tag) || "th".equals(tag)) {
                        out.append(" | ");
                    }
                }
            }
        });
        return tidy(out.toString());
    }

    private static boolean insidePreformatted(TextNode text) {
        for (Node parent = text.parent(); parent != null; parent = parent.parent()) {
            if (parent instanceof Element element && PRESERVE_WHITESPACE.contains(element.normalName())) {
                return true;
            }
        }
        return false;
    }

    private static String collapse(String text) {
        return text.replace('\u00a0', ' ').replaceAll("\\s+", " ");
    }

    private static String tidy(String text) {
        StringBuilder lines = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            lines.append(line.strip()).append('\n');
        }
        return lines.toString().replaceAll("\n{3,}", "\n\n").strip();
    }
}
