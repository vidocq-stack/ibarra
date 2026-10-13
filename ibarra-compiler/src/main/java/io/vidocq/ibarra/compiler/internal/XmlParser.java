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
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.stream.*;

/** Namespace-aware XML parser with no external document lookup. */
public final class XmlParser {
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
            boolean dependent, boolean[] envelope) { }

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
        XMLStreamReader reader = null;
        try {
            reader = factory.createXMLStreamReader(new StringReader(source.text));
            while (reader.hasNext()) {
                int event = reader.next();
                switch (event) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        int start = lexical.tag();
                        String name = qualified(reader.getPrefix(), reader.getLocalName());
                        String namespace = reader.getNamespaceURI() == null ? "" : reader.getNamespaceURI();
                        boolean wrapper = !stack.isEmpty() && stack.peek().dependent()
                                && (stack.peek().envelope()[0] || stack.peek().children().stream().allMatch(node -> node instanceof PageNode.RawText text
                                        && text.value().chars().allMatch(c -> c == ' ' || c == '\t' || c == '\r' || c == '\n')))
                                && namespace.equals(PageParser.JSP_NAMESPACE)
                                && (reader.getLocalName().equals("attribute") || reader.getLocalName().equals("body"));
                        if (wrapper && !stack.peek().envelope()[0]) {
                            stack.peek().envelope()[0] = true;
                            stack.peek().children().clear(); // Outer whitespace separates envelopes; it is not body data.
                        }
                        boolean parentRaw = !stack.isEmpty() && stack.peek().raw() && !stack.peek().envelope()[0];
                        if (!parentRaw && namespace.equals(PageParser.JSP_NAMESPACE) && reader.getLocalName().equals("root")) {
                            if (!stack.isEmpty()) throw new PageParseException(source.at(start), "jsp:root must be the document root");
                            if (reader.getAttributeValue(null, "version") == null)
                                throw new PageParseException(source.at(start), "jsp:root requires a version");
                        }
                        var attributes = new LinkedHashMap<String, String>();
                        for (int i = 0; i < reader.getNamespaceCount(); i++) {
                            String prefix = reader.getNamespacePrefix(i);
                            String uri = reader.getNamespaceURI(i);
                            attributes.put(prefix == null || prefix.isEmpty() ? "xmlns" : "xmlns:" + prefix, uri == null ? "" : uri);
                        }
                        for (int i = 0; i < reader.getAttributeCount(); i++)
                            attributes.put(qualified(reader.getAttributePrefix(i), reader.getAttributeLocalName(i)), reader.getAttributeValue(i));
                        if (parentRaw) stack.peek().children().add(new PageNode.RawText(
                                source.text.substring(start, lexical.cursor), source.at(start)));
                        boolean dependent = !parentRaw && !wrapper && rawBodies != null && rawBodies.raw(name, namespace, source.at(start), false);
                        boolean raw = parentRaw || dependent || wrapper && reader.getLocalName().equals("body");
                        stack.push(new Frame(name, namespace, attributes, new ArrayList<>(),
                                source.text.charAt(lexical.cursor - 2) == '/', source.at(start), raw, parentRaw, dependent, new boolean[1]));
                    }
                    case XMLStreamConstants.END_ELEMENT -> {
                        var frame = stack.pop();
                        int end = frame.empty() ? lexical.cursor : lexical.tag();
                        if (frame.literalMarkup()) {
                            stack.peek().children().addAll(frame.children());
                            if (!frame.empty()) stack.peek().children().add(new PageNode.RawText(
                                    source.text.substring(end, lexical.cursor), source.at(end)));
                            break;
                        }
                        var node = new PageNode.Element(frame.name(), frame.namespace(), frame.attributes(),
                                frame.children(), frame.empty(), frame.location());
                        if (stack.isEmpty()) roots.add(node);
                        else stack.peek().children().add(node);
                    }
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.SPACE, XMLStreamConstants.CDATA -> {
                        String value = reader.getText();
                        var positions = lexical.text(value);
                        if (!stack.isEmpty() && !value.isEmpty()) {
                            if (stack.peek().raw() && !stack.peek().envelope()[0]) {
                                stack.peek().children().add(new PageNode.RawText(value, positions.getFirst()));
                                break;
                            }
                            var text = new PageNode.Text(value, positions.getFirst());
                            origins.put(text, positions);
                            stack.peek().children().add(text);
                        }
                    }
                    case XMLStreamConstants.DTD -> throw new PageParseException(source.at(0), "DOCTYPE declarations are not supported");
                    default -> { }
                }
            }
        } catch (XMLStreamException exception) {
            var location = exception.getLocation();
            var at = location == null ? source.at(0) : new SourceLocation(source.name,
                    Math.max(1, location.getLineNumber()), Math.max(1, location.getColumnNumber()));
            throw new PageParseException(at, "Malformed XML document: " + exception.getMessage(), exception);
        } finally {
            if (reader != null) {
                try { reader.close(); }
                catch (XMLStreamException ignored) { /* A StringReader owns no external resource. */ }
            }
        }
        return roots;
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
            while (true) {
                int start = source.text.indexOf('<', cursor);
                if (start < 0) throw new PageParseException(source.at(cursor), "Missing XML markup");
                if (source.text.startsWith("<!--", start)) {
                    cursor = source.text.indexOf("-->", start + 4) + 3;
                    continue;
                }
                if (source.text.startsWith("<?", start)) {
                    cursor = source.text.indexOf("?>", start + 2) + 2;
                    continue;
                }
                cursor = start + 1;
                char quote = 0;
                while (cursor < source.text.length()) {
                    char c = source.text.charAt(cursor++);
                    if (quote != 0) { if (c == quote) quote = 0; }
                    else if (c == '\'' || c == '"') quote = c;
                    else if (c == '>') return start;
                }
                throw new PageParseException(source.at(start), "Unclosed XML markup");
            }
        }

        List<SourceLocation> text(String expected) {
            var positions = new ArrayList<SourceLocation>(expected.length());
            int matched = 0;
            while (matched < expected.length()) {
                if (!cdata && source.text.startsWith("<!--", cursor)) {
                    cursor = source.text.indexOf("-->", cursor + 4) + 3; continue;
                }
                if (!cdata && source.text.startsWith("<?", cursor)) {
                    cursor = source.text.indexOf("?>", cursor + 2) + 2; continue;
                }
                if (!cdata && source.text.startsWith("<![CDATA[", cursor)) { cursor += 9; cdata = true; continue; }
                if (cdata && source.text.startsWith("]]>", cursor)) { cursor += 3; cdata = false; continue; }
                int start = cursor;
                String decoded;
                if (!cdata && source.text.charAt(cursor) == '&') {
                    int end = source.text.indexOf(';', cursor);
                    String entity = source.text.substring(cursor + 1, end);
                    decoded = switch (entity) {
                        case "amp" -> "&"; case "lt" -> "<"; case "gt" -> ">";
                        case "quot" -> "\""; case "apos" -> "'";
                        default -> new String(Character.toChars(entity.startsWith("#x")
                                ? Integer.parseInt(entity.substring(2), 16) : Integer.parseInt(entity.substring(1))));
                    };
                    cursor = end + 1;
                } else {
                    char c = source.text.charAt(cursor++);
                    if (c == '\r') {
                        if (cursor < source.text.length() && source.text.charAt(cursor) == '\n') cursor++;
                        c = '\n';
                    }
                    decoded = String.valueOf(c);
                }
                if (!expected.startsWith(decoded, matched))
                    throw new PageParseException(source.at(start), "Cannot locate XML character data");
                for (int i = 0; i < decoded.length(); i++) positions.add(source.at(start));
                matched += decoded.length();
            }
            return List.copyOf(positions);
        }
    }
}

