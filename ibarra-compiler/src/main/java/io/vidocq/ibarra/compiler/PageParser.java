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
package io.vidocq.ibarra.compiler;

import io.vidocq.ibarra.compiler.internal.PageNormalizer;
import io.vidocq.ibarra.compiler.internal.SourceDocument;
import io.vidocq.ibarra.compiler.internal.StandardParser;
import io.vidocq.ibarra.compiler.internal.XmlParser;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Build-time source parser. Syntax structure is separate from generation semantics. */
public final class PageParser {
    public static final String JSP_NAMESPACE = "http://java.sun.com/JSP/Page";
    private static final Pattern XML_ROOT = Pattern.compile(
            "<([\\p{L}_][\\p{L}\\p{N}_.-]*):root\\b[^>]*\\bxmlns:\\1\\s*=\\s*(['\"])"
                    + Pattern.quote(JSP_NAMESPACE) + "\\2", Pattern.DOTALL);
    private static final Pattern DEFAULT_XML_ROOT = Pattern.compile(
            "<root\\b[^>]*\\bxmlns\\s*=\\s*(['\"])" + Pattern.quote(JSP_NAMESPACE) + "\\1", Pattern.DOTALL);

    public Page parse(String name, String source) {
        Objects.requireNonNull(name);
        Objects.requireNonNull(source);
        var syntax = detectSyntax(name, source);
        return parse(name, source, syntax, ParserOptions.DEFAULT);
    }

    /** Selects syntax from the source suffix and a namespace-aware JSP document root. */
    public static Page.Syntax detectSyntax(String name, String source) {
        Objects.requireNonNull(name); Objects.requireNonNull(source);
        return name.endsWith(".jspx") || name.endsWith(".tagx") || xmlRoot(source)
                ? Page.Syntax.XML : Page.Syntax.STANDARD;
    }

    public Page parse(String name, String source, Page.Syntax syntax, ParserOptions options) {
        Objects.requireNonNull(name);
        Objects.requireNonNull(source);
        Objects.requireNonNull(syntax);
        Objects.requireNonNull(options);
        var document = new SourceDocument(name, source);
        List<PageNode> raw;
        Map<PageNode.Text, List<SourceLocation>> origins = Map.of();
        if (syntax == Page.Syntax.XML) {
            var parser = new XmlParser(document);
            raw = parser.parse();
            origins = parser.origins();
        } else raw = new StandardParser(document, options).parse();
        return new Page(name, syntax, new PageNormalizer(syntax, options, origins).normalize(raw));
    }

    private static boolean xmlRoot(String source) {
        int start = source.startsWith("\ufeff") ? 1 : 0;
        while (start < source.length()) {
            if (Character.isWhitespace(source.charAt(start))) { start++; continue; }
            String end = source.startsWith("<?", start) ? "?>" : source.startsWith("<!--", start) ? "-->" : null;
            if (end == null) return XML_ROOT.matcher(source).region(start, source.length()).lookingAt()
                    || DEFAULT_XML_ROOT.matcher(source).region(start, source.length()).lookingAt();
            int closing = source.indexOf(end, start + 2);
            if (closing < 0) return false;
            start = closing + end.length();
        }
        return false;
    }
}

