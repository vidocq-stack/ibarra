/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.ibarra.compiler.internal;

import io.vidocq.ibarra.compiler.*;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.stream.*;

/** Namespace-aware XML parser with no external document lookup. */
public final class XmlParser {
    private static final String COMMENT_START = "<!--";
    private static final String COMMENT_END = "-->";
    private static final String INSTRUCTION_START = "<?";
    private static final String INSTRUCTION_END = "?>";
    private final SourceDocument source;
    private final Map<PageNode.Text, List<SourceLocation>> origins = new IdentityHashMap<>();
    private final LexicalCursor lexical;
    private final TagDependentBodies rawBodies;

    public XmlParser(SourceDocument source) { this(source, null); }
    public XmlParser(SourceDocument source, TagDependentBodies rawBodies) {
        this.source = source; this.rawBodies = rawBodies; lexical = new LexicalCursor();
    }
    public Map<PageNode.Text, List<SourceLocation>> origins() { return origins; }

    private record Frame(String name, String namespace, Map<String, String> attributes,
            List<PageNode> children, boolean empty, SourceLocation location, boolean raw, boolean literalMarkup,
            boolean dependent, Envelope envelope) { }

    /** Whether a tag-dependent body has started its jsp:attribute / jsp:body envelopes. */
    private static final class Envelope {
        private boolean entered;
    }

    /** Closes the stream reader; a StringReader owns no external resource. */
    private record ClosingReader(XMLStreamReader reader) implements AutoCloseable {
        @Override public void close() {
            try {
                reader.close();
            } catch (XMLStreamException _) {
                // Nothing is left to release: the reader only reads an in-memory string.
            }
        }
    }

    public List<PageNode> parse() {
        var factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setXMLResolver((publicId, systemId, base, namespace) -> {
            throw new XMLStreamException("External entities are not supported");
        });
        var roots = new ArrayList<PageNode>();
        var stack = new ArrayDeque<Frame>();
        try (var closing = new ClosingReader(factory.createXMLStreamReader(new StringReader(source.text)))) {
            var reader = closing.reader();
            while (reader.hasNext()) event(reader.next(), reader, roots, stack);
        } catch (XMLStreamException exception) {
            var location = exception.getLocation();
            var at = location == null ? source.at(0) : new SourceLocation(source.name,
                    Math.max(1, location.getLineNumber()), Math.max(1, location.getColumnNumber()));
            throw new PageParseException(at, "Malformed XML document: " + exception.getMessage(), exception);
        }
        return roots;
    }

    private void event(int event, XMLStreamReader reader, List<PageNode> roots, Deque<Frame> stack) {
        switch (event) {
            case XMLStreamConstants.START_ELEMENT -> startElement(reader, stack);
            case XMLStreamConstants.END_ELEMENT -> endElement(roots, stack);
            case XMLStreamConstants.CHARACTERS, XMLStreamConstants.SPACE, XMLStreamConstants.CDATA -> characters(reader.getText(), stack);
            case XMLStreamConstants.DTD -> throw new PageParseException(source.at(0), "DOCTYPE declarations are not supported");
            default -> {
                // Comments, processing instructions and document events carry no page content.
            }
        }
    }

    private void startElement(XMLStreamReader reader, Deque<Frame> stack) {
        int start = lexical.tag();
        String name = qualified(reader.getPrefix(), reader.getLocalName());
        String namespace = reader.getNamespaceURI() == null ? "" : reader.getNamespaceURI();
        boolean wrapper = envelope(stack, namespace, reader.getLocalName());
        if (wrapper && !stack.peek().envelope().entered) {
            stack.peek().envelope().entered = true;
            stack.peek().children().clear(); // Outer whitespace separates envelopes; it is not body data.
        }
        boolean parentRaw = !stack.isEmpty() && stack.peek().raw() && !stack.peek().envelope().entered;
        if (!parentRaw && namespace.equals(PageParser.JSP_NAMESPACE) && reader.getLocalName().equals("root"))
            checkRoot(reader, stack, start);
        var attributes = attributes(reader);
        if (parentRaw) stack.peek().children().add(new PageNode.RawText(
                source.text.substring(start, lexical.cursor), source.at(start)));
        boolean dependent = !parentRaw && !wrapper && rawBodies != null && rawBodies.raw(name, namespace, source.at(start), false);
        boolean raw = parentRaw || dependent || wrapper && reader.getLocalName().equals("body");
        stack.push(new Frame(name, namespace, attributes, new ArrayList<>(),
                source.text.charAt(lexical.cursor - 2) == '/', source.at(start), raw, parentRaw, dependent, new Envelope()));
    }

    /** Whether an element is a jsp:attribute or jsp:body envelope of a tag-dependent body. */
    private static boolean envelope(Deque<Frame> stack, String namespace, String localName) {
        if (stack.isEmpty() || !stack.peek().dependent()) return false;
        boolean separated = stack.peek().envelope().entered || stack.peek().children().stream().allMatch(XmlParser::whitespace);
        return separated && namespace.equals(PageParser.JSP_NAMESPACE)
                && (localName.equals("attribute") || localName.equals("body"));
    }

    private static boolean whitespace(PageNode node) {
        return node instanceof PageNode.RawText text
                && text.value().chars().allMatch(c -> c == ' ' || c == '\t' || c == '\r' || c == '\n');
    }

    private void checkRoot(XMLStreamReader reader, Deque<Frame> stack, int start) {
        if (!stack.isEmpty()) throw new PageParseException(source.at(start), "jsp:root must be the document root");
        if (reader.getAttributeValue(null, "version") == null)
            throw new PageParseException(source.at(start), "jsp:root requires a version");
    }

    private static Map<String, String> attributes(XMLStreamReader reader) {
        var attributes = new LinkedHashMap<String, String>();
        for (int i = 0; i < reader.getNamespaceCount(); i++) {
            String prefix = reader.getNamespacePrefix(i);
            String uri = reader.getNamespaceURI(i);
            attributes.put(prefix == null || prefix.isEmpty() ? "xmlns" : "xmlns:" + prefix, uri == null ? "" : uri);
        }
        for (int i = 0; i < reader.getAttributeCount(); i++)
            attributes.put(qualified(reader.getAttributePrefix(i), reader.getAttributeLocalName(i)), reader.getAttributeValue(i));
        return attributes;
    }

    private void endElement(List<PageNode> roots, Deque<Frame> stack) {
        var frame = stack.pop();
        int end = frame.empty() ? lexical.cursor : lexical.tag();
        if (frame.literalMarkup()) {
            stack.peek().children().addAll(frame.children());
            if (!frame.empty()) stack.peek().children().add(new PageNode.RawText(
                    source.text.substring(end, lexical.cursor), source.at(end)));
            return;
        }
        var node = new PageNode.Element(frame.name(), frame.namespace(), frame.attributes(),
                frame.children(), frame.empty(), frame.location());
        if (stack.isEmpty()) roots.add(node);
        else stack.peek().children().add(node);
    }

    private void characters(String value, Deque<Frame> stack) {
        var positions = lexical.text(value);
        if (stack.isEmpty() || value.isEmpty()) return;
        if (stack.peek().raw() && !stack.peek().envelope().entered) {
            stack.peek().children().add(new PageNode.RawText(value, positions.getFirst()));
            return;
        }
        var text = new PageNode.Text(value, positions.getFirst());
        origins.put(text, positions);
        stack.peek().children().add(text);
    }

    private static String qualified(String prefix, String name) {
        return prefix == null || prefix.isEmpty() ? name : prefix + ":" + name;
    }

    /** Tracks original source locations through entity decoding and CDATA. */
    private final class LexicalCursor {
        int cursor;
        boolean cdata;

        int tag() {
            cdata = false;
            int start = nextTag();
            cursor = start + 1;
            char quote = 0;
            while (cursor < source.text.length()) {
                char c = source.text.charAt(cursor++);
                if (quote != 0) {
                    if (c == quote) quote = 0;
                } else if (c == '\'' || c == '"') quote = c;
                else if (c == '>') return start;
            }
            throw new PageParseException(source.at(start), "Unclosed XML markup");
        }

        /** The offset of the next tag, after any comments and processing instructions. */
        private int nextTag() {
            while (true) {
                int start = source.text.indexOf('<', cursor);
                if (start < 0) throw new PageParseException(source.at(cursor), "Missing XML markup");
                int skipped = afterCommentOrInstruction(start);
                if (skipped < 0) return start;
                cursor = skipped;
            }
        }

        /** The offset after a comment or processing instruction starting at {@code start}, or -1 for any other markup. */
        private int afterCommentOrInstruction(int start) {
            if (source.text.startsWith(COMMENT_START, start)) return source.text.indexOf(COMMENT_END, start + 4) + 3;
            if (source.text.startsWith(INSTRUCTION_START, start)) return source.text.indexOf(INSTRUCTION_END, start + 2) + 2;
            return -1;
        }

        List<SourceLocation> text(String expected) {
            var positions = new ArrayList<SourceLocation>(expected.length());
            int matched = 0;
            while (matched < expected.length()) {
                if (!skipMarkup()) {
                    int start = cursor;
                    String decoded = decodeNext();
                    if (!expected.startsWith(decoded, matched))
                        throw new PageParseException(source.at(start), "Cannot locate XML character data");
                    for (int i = 0; i < decoded.length(); i++) positions.add(source.at(start));
                    matched += decoded.length();
                }
            }
            return List.copyOf(positions);
        }

        /** Skips a comment, a processing instruction or a CDATA delimiter at the cursor; whether one was skipped. */
        private boolean skipMarkup() {
            if (cdata) {
                if (!source.text.startsWith("]]>", cursor)) return false;
                cursor += 3;
                cdata = false;
            } else if (source.text.startsWith(COMMENT_START, cursor)) {
                cursor = source.text.indexOf(COMMENT_END, cursor + 4) + 3;
            } else if (source.text.startsWith(INSTRUCTION_START, cursor)) {
                cursor = source.text.indexOf(INSTRUCTION_END, cursor + 2) + 2;
            } else if (source.text.startsWith("<![CDATA[", cursor)) {
                cursor += 9;
                cdata = true;
            } else return false;
            return true;
        }

        /** Decodes the character or entity at the cursor, normalising line ends outside CDATA as XML does. */
        private String decodeNext() {
            if (!cdata && source.text.charAt(cursor) == '&') return entity();
            char c = source.text.charAt(cursor++);
            if (c == '\r') {
                if (cursor < source.text.length() && source.text.charAt(cursor) == '\n') cursor++;
                c = '\n';
            }
            return String.valueOf(c);
        }

        private String entity() {
            int end = source.text.indexOf(';', cursor);
            String entity = source.text.substring(cursor + 1, end);
            String decoded = switch (entity) {
                case "amp" -> "&"; case "lt" -> "<"; case "gt" -> ">";
                case "quot" -> "\""; case "apos" -> "'";
                default -> new String(Character.toChars(entity.startsWith("#x")
                        ? Integer.parseInt(entity.substring(2), 16) : Integer.parseInt(entity.substring(1))));
            };
            cursor = end + 1;
            return decoded;
        }
    }
}
