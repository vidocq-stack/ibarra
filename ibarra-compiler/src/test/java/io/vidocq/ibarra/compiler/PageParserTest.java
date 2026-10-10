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

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Corpus based on Pages 4.0 sections 1.3, 1.5, 1.6 and 6.3. */
class PageParserTest {
    private final PageParser parser = new PageParser();

    @Test
    void preservesStandardTemplateMarkupAndSplitsExpressions() {
        var page = parser.parse("/hello.jsp", "<p>Hello ${user.name}!</p>");
        assertEquals(Page.Syntax.STANDARD, page.syntax());
        assertEquals("<p>Hello ", text(page.nodes().get(0)));
        var el = assertInstanceOf(PageNode.Expression.class, page.nodes().get(1));
        assertEquals("user.name", el.code());
        assertFalse(el.deferred());
        assertEquals("!</p>", text(page.nodes().get(2)));
    }

    @Test
    void preservesHtmlCommentsButDropsJspComments() {
        var page = parser.parse("/comment.jsp", "A<%-- ${broken <% anything --%><!-- <%= value %> -->B");
        assertEquals("A<!-- ", text(page.nodes().get(0)));
        assertEquals(" value ", assertInstanceOf(PageNode.Script.class, page.nodes().get(1)).code());
        assertEquals(" -->B", text(page.nodes().get(2)));
    }

    @Test
    void parsesDirectivesAndThreeKindsOfScripting() {
        var page = parser.parse("/script.jsp", "<%@ page contentType='text/html' import=\"java.util.List,java.util.Map\" %>"
                + "<%! int counter; %><% counter++; %><%= counter %>");
        var directive = assertInstanceOf(PageNode.Directive.class, page.nodes().get(0));
        assertEquals("page", directive.name());
        assertEquals("text/html", directive.attributes().get("contentType"));
        assertEquals(PageNode.ScriptKind.DECLARATION, assertInstanceOf(PageNode.Script.class, page.nodes().get(1)).kind());
        assertEquals(PageNode.ScriptKind.SCRIPTLET, assertInstanceOf(PageNode.Script.class, page.nodes().get(2)).kind());
        assertEquals(PageNode.ScriptKind.EXPRESSION, assertInstanceOf(PageNode.Script.class, page.nodes().get(3)).kind());
    }

    @Test
    void preservesDirectiveAttributeOrderAndRejectsDuplicates() {
        var page = parser.parse("/directive.jsp", "<%@ page language=\"java\" contentType=\"text/plain\" %>");
        var directive = assertInstanceOf(PageNode.Directive.class, page.nodes().getFirst());
        assertEquals(List.of("language", "contentType"), List.copyOf(directive.attributes().keySet()));
        assertThrows(PageParseException.class,
                () -> parser.parse("/bad.jsp", "<%@ page language='java' language='java' %>"));
    }

    @Test
    void parsesNestedActionsAndCustomTagsUsingTaglibBindings() {
        var page = parser.parse("/tags.jsp", "<%@ taglib prefix='c' uri='urn:example' %>"
                + "<c:box label=\"a > b\"><jsp:include page='part.jsp'><jsp:param name='x' value='${x}'/>"
                + "</jsp:include>body</c:box>");
        var box = assertInstanceOf(PageNode.Element.class, page.nodes().get(1));
        assertEquals("c:box", box.name());
        assertEquals("urn:example", box.namespace());
        assertEquals("a > b", box.attributes().get("label"));
        assertFalse(box.selfClosing());
        var include = assertInstanceOf(PageNode.Element.class, box.children().get(0));
        assertEquals(PageParser.JSP_NAMESPACE, include.namespace());
        var param = assertInstanceOf(PageNode.Element.class, include.children().getFirst());
        assertTrue(param.selfClosing());
        assertEquals("${x}", param.attributes().get("value"));
        assertEquals("body", text(box.children().get(1)));
    }

    @Test
    void customTagBindingsApplyBeforeTheirDirectiveAppears() {
        var page = parser.parse("/late.jsp", "<c:item/><%@ taglib prefix='c' uri='urn:items' %>");
        assertEquals("urn:items", assertInstanceOf(PageNode.Element.class, page.nodes().get(0)).namespace());
    }

    @Test
    void undeclaredMarkupStaysTemplateText() {
        var page = parser.parse("/html.jsp", "<svg:svg><input disabled></svg:svg>");
        assertEquals("<svg:svg><input disabled></svg:svg>", text(page.nodes().getFirst()));
    }

    @Test
    void quotedDelimitersDoNotEndDirectiveAttributes() {
        var page = parser.parse("/quote.jsp", "<%@ page info=\"quoted %> and \\\"quote\\\"\" %>");
        var directive = assertInstanceOf(PageNode.Directive.class, page.nodes().getFirst());
        assertEquals("quoted %> and \"quote\"", directive.attributes().get("info"));
    }

    @Test
    void unescapesTemplateAndScriptingDelimiters() {
        var page = parser.parse("/escape.jsp", "<\\% literal \\${x} \\#{y}<%= \"%\\>\" %>");
        assertEquals("<% literal ${x} #{y}", text(page.nodes().getFirst()));
        assertEquals(" \"%>\" ", assertInstanceOf(PageNode.Script.class, page.nodes().get(1)).code());
    }

    @Test
    void elScannerUnderstandsQuotedBracesAndNestedCollections() {
        var page = parser.parse("/el.jsp", "${{'key': '}'}['key']}#{fn({'x': 1})}");
        assertEquals("{'key': '}'}['key']", assertInstanceOf(PageNode.Expression.class, page.nodes().get(0)).code());
        assertTrue(assertInstanceOf(PageNode.Expression.class, page.nodes().get(1)).deferred());
    }

    @Test
    void pageElDirectivesApplyToTheWholeDocument() {
        var page = parser.parse("/ignored.jsp", "${x}<%@ page isELIgnored='true' %>\\${y}");
        assertEquals("${x}", text(page.nodes().get(0)));
        assertEquals("\\${y}", text(page.nodes().get(2)));
        var deferred = parser.parse("/deferred.jsp", "#{x}<%@ page deferredSyntaxAllowedAsLiteral='true' %>${y}");
        assertEquals("#{x}", text(deferred.nodes().get(0)));
        assertInstanceOf(PageNode.Expression.class, deferred.nodes().get(2));
    }

    @Test
    void explicitOptionsSupportPropertyGroupSettings() {
        var page = parser.parse("/configured.jsp", "${x} #{y}", Page.Syntax.STANDARD, new ParserOptions(true, false));
        assertEquals("${x} #{y}", text(page.nodes().getFirst()));
    }

    @Test
    void reportsStandardErrorsAtTheirOpeningLocationWithCrLfSupport() {
        var error = assertThrows(PageParseException.class, () -> parser.parse("/broken.jsp", "one\r\ntwo\r\n<% unclosed"));
        assertEquals("/broken.jsp", error.location().source());
        assertEquals(3, error.location().line());
        assertEquals(1, error.location().column());
        assertTrue(error.getMessage().contains("/broken.jsp:3:1"));
    }

    @Test
    void rejectsMalformedDirectivesElementsAndExpressions() {
        for (String source : List.of("<%@ unknown x='y' %>", "<%@ page x=unquoted %>",
                "<%@ page x='unterminated %>", "<jsp:include>", "<jsp:a></jsp:b>",
                "</jsp:include>", "${unclosed", "<%-- unclosed", "<jsp:include page='x' page='y'/>")) {
            assertThrows(PageParseException.class, () -> parser.parse("/bad.jsp", source), source);
        }
    }

    @Test
    void pageModelCollectionsCannotBeMutated() {
        var page = parser.parse("/immutable.jsp", "<%@ page language='java' %><jsp:include page='part.jsp'/>");
        assertThrows(UnsupportedOperationException.class, () -> page.nodes().clear());
        var directive = assertInstanceOf(PageNode.Directive.class, page.nodes().getFirst());
        assertThrows(UnsupportedOperationException.class, () -> directive.attributes().put("x", "y"));
        var element = assertInstanceOf(PageNode.Element.class, page.nodes().get(1));
        assertThrows(UnsupportedOperationException.class, () -> element.children().clear());
    }

    @Test
    void recognizesJspNamespaceByUriInXmlDocuments() {
        var page = parser.parse("/hello.jspx", "<j:root xmlns:j='" + PageParser.JSP_NAMESPACE + "' version='4.0'>"
                + "<j:directive.page contentType='text/plain'/><j:text>Hello &amp; ${name}!</j:text></j:root>");
        assertEquals(Page.Syntax.XML, page.syntax());
        var root = assertInstanceOf(PageNode.Element.class, page.nodes().getFirst());
        assertEquals("j:root", root.name());
        assertEquals(PageParser.JSP_NAMESPACE, root.namespace());
        var directive = assertInstanceOf(PageNode.Directive.class, root.children().get(0));
        assertEquals("page", directive.name());
        var jspText = assertInstanceOf(PageNode.Element.class, root.children().get(1));
        assertEquals("Hello & ", text(jspText.children().get(0)));
        assertEquals("name", assertInstanceOf(PageNode.Expression.class, jspText.children().get(1)).code());
    }

    @Test
    void xmlCommentsAreIgnoredAndCdataCarriesScriptingText() {
        var page = parser.parse("/script.jspx", "<jsp:root xmlns:jsp='" + PageParser.JSP_NAMESPACE + "' version='4.0'>"
                + "<!-- ${ignored} --><jsp:declaration><![CDATA[int x = 1 < 2 ? 1 : 0;]]></jsp:declaration>"
                + "<jsp:scriptlet>x++;</jsp:scriptlet><jsp:expression>x</jsp:expression></jsp:root>");
        var root = assertInstanceOf(PageNode.Element.class, page.nodes().getFirst());
        assertEquals(3, root.children().size());
        assertEquals("int x = 1 < 2 ? 1 : 0;", assertInstanceOf(PageNode.Script.class, root.children().get(0)).code());
        assertEquals(PageNode.ScriptKind.EXPRESSION, assertInstanceOf(PageNode.Script.class, root.children().get(2)).kind());
    }

    @Test
    void xmlTemplateElementsKeepNamespaceBindingsAndAttributes() {
        var page = parser.parse("/xml.jspx", "<html xmlns='urn:html'><p class='message'>Hi</p></html>");
        var html = assertInstanceOf(PageNode.Element.class, page.nodes().getFirst());
        assertEquals("urn:html", html.namespace());
        assertEquals("urn:html", html.attributes().get("xmlns"));
        var paragraph = assertInstanceOf(PageNode.Element.class, html.children().getFirst());
        assertEquals(Map.of("class", "message"), paragraph.attributes());
        assertEquals("Hi", text(paragraph.children().getFirst()));
    }

    @Test
    void xmlIgnoresFormattingWhitespaceOutsideJspText() {
        var page = parser.parse("/space.jspx", "<jsp:root xmlns:jsp='" + PageParser.JSP_NAMESPACE + "' version='4.0'>\n"
                + "  <jsp:text>  \n </jsp:text>\n</jsp:root>");
        var root = assertInstanceOf(PageNode.Element.class, page.nodes().getFirst());
        assertEquals(1, root.children().size());
        assertEquals("  \n ", text(assertInstanceOf(PageNode.Element.class, root.children().getFirst()).children().getFirst()));
    }

    @Test
    void xmlPageDirectivesControlExpressionsEvenBeforeTheDirective() {
        var page = parser.parse("/ignored.jspx", "<jsp:root xmlns:jsp='" + PageParser.JSP_NAMESPACE + "' version='4.0'>"
                + "<jsp:text>${x}</jsp:text><jsp:directive.page isELIgnored='true'/></jsp:root>");
        var root = assertInstanceOf(PageNode.Element.class, page.nodes().getFirst());
        assertEquals("${x}", text(assertInstanceOf(PageNode.Element.class, root.children().getFirst()).children().getFirst()));
    }

    @Test
    void xmlUsesExplicitSyntaxAndRecognizesAnXmlJspRootInJspFiles() {
        String source = "<x:root xmlns:x='" + PageParser.JSP_NAMESPACE + "' version='4.0'><x:text>x</x:text></x:root>";
        assertEquals(Page.Syntax.XML, parser.parse("/xml.jsp", source).syntax());
        assertEquals(Page.Syntax.XML, parser.parse("/fragment", "<p/>", Page.Syntax.XML, ParserOptions.DEFAULT).syntax());
    }

    @Test
    void reportsMalformedXmlAndDisallowsExternalEntityResolution() {
        var malformed = assertThrows(PageParseException.class, () -> parser.parse("/bad.jspx", "<p>\n<a></p>"));
        assertEquals("/bad.jspx", malformed.location().source());
        assertEquals(2, malformed.location().line());
        assertThrows(PageParseException.class, () -> parser.parse("/entity.jspx",
                "<!DOCTYPE p [<!ENTITY x SYSTEM 'file:///does-not-exist'>]><p>&x;</p>"));
    }

    @Test
    void xmlRejectsNestedJspRootAndNonTextScriptingBodies() {
        String uri = PageParser.JSP_NAMESPACE;
        assertThrows(PageParseException.class, () -> parser.parse("/root.jspx",
                "<jsp:root xmlns:jsp='" + uri + "' version='4.0'><jsp:root version='4.0'/></jsp:root>"));
        assertThrows(PageParseException.class, () -> parser.parse("/script.jspx",
                "<jsp:scriptlet xmlns:jsp='" + uri + "'><p/></jsp:scriptlet>"));
    }

    private static String text(PageNode node) {
        return assertInstanceOf(PageNode.Text.class, node).value();
    }

    @Test
    void scriptingAndDirectiveMarkersInsideElStringsStayInsideTheExpression() {
        var page = parser.parse("/quoted-el.jsp", "${'<%'}${'<%@ taglib prefix=\"c\" uri=\"urn:fake\" %>'}<c:item/>");
        assertEquals("'<%'", assertInstanceOf(PageNode.Expression.class, page.nodes().get(0)).code());
        assertInstanceOf(PageNode.Expression.class, page.nodes().get(1));
        assertEquals("<c:item/>", text(page.nodes().get(2)));
    }

    @Test
    void xmlRootTextInsideJspCommentsDoesNotSelectXmlSyntax() {
        var page = parser.parse("/comment.jsp", "<%-- <jsp:root xmlns:jsp='" + PageParser.JSP_NAMESPACE
                + "' version='4.0'/> --%>plain");
        assertEquals(Page.Syntax.STANDARD, page.syntax());
        assertEquals("plain", text(page.nodes().getFirst()));
    }

    @Test
    void xmlRetainsCharactersThatAreNotXmlFormattingWhitespace() {
        var page = parser.parse("/unicode.jspx", "<p>\u2003</p>");
        var paragraph = assertInstanceOf(PageNode.Element.class, page.nodes().getFirst());
        assertEquals("\u2003", text(paragraph.children().getFirst()));
    }

    @Test
    void xmlLocationsReferToTheOriginalCharactersBeforeEntityAndCdataDecoding() {
        String source = "<p>&amp;<![CDATA[abc]]>${value}</p>";
        var page = parser.parse("/locations.jspx", source);
        var paragraph = assertInstanceOf(PageNode.Element.class, page.nodes().getFirst());
        assertEquals(1, paragraph.location().column());
        var expression = assertInstanceOf(PageNode.Expression.class, paragraph.children().get(1));
        assertEquals(source.indexOf("${") + 1, expression.location().column());
        assertEquals("&abc", text(paragraph.children().getFirst()));
    }

    @Test
    void xmlHandlesCommentsProcessingInstructionsSupplementaryEntitiesAndLargeText() {
        String content = "a".repeat(50_000);
        var page = parser.parse("/large.jspx", "<?xml version='1.0'?><?before data?><p>"
                + content + "<!-- omitted --><?inside data?>&#x1f600;</p>");
        var paragraph = assertInstanceOf(PageNode.Element.class, page.nodes().getFirst());
        assertEquals(content + "😀", text(paragraph.children().getFirst()));
    }

    @Test
    void tagFilesUseTagDirectivesAndXmlSuffixDetection() {
        var tag = parser.parse("/body.tag", "<%@ tag body-content='empty' %><%@ attribute name='title' required='true' %>");
        assertEquals("tag", assertInstanceOf(PageNode.Directive.class, tag.nodes().get(0)).name());
        assertEquals("attribute", assertInstanceOf(PageNode.Directive.class, tag.nodes().get(1)).name());
        assertEquals(Page.Syntax.XML, parser.parse("/body.tagx", "<jsp:directive.tag xmlns:jsp='"
                + PageParser.JSP_NAMESPACE + "' body-content='empty'/>").syntax());
    }

    @Test
    void rejectsConflictingElSettingsAndDynamicDirectiveValues() {
        for (String source : List.of("<%@ page isELIgnored='true' %><%@ page isELIgnored='false' %>",
                "<%@ page isELIgnored='yes' %>", "<%@ page info='${value}' %>", "<%@ page info='<%= value %>' %>"))
            assertThrows(PageParseException.class, () -> parser.parse("/directive.jsp", source), source);
    }

    @Test
    void expressionPositionsAfterJspCommentsUseTheOriginalSource() {
        var page = parser.parse("/positions.jsp", "a<%-- removed --%>\r\n${value}");
        var expression = assertInstanceOf(PageNode.Expression.class, page.nodes().get(1));
        assertEquals(2, expression.location().line());
        assertEquals(1, expression.location().column());
    }

    @Test
    void ignoredElDoesNotRequireBalancedExpressionDelimitersBeforeTheDirective() {
        var page = parser.parse("/literal.jsp", "${unfinished<%@ page isELIgnored='true' %>");
        assertEquals("${unfinished", text(page.nodes().getFirst()));
    }

    @Test
    void escapingElDoesNotHideScriptingElementsInTemplateText() {
        var page = parser.parse("/escaped.jsp", "\\${<%= value %>}");
        assertEquals("${", text(page.nodes().get(0)));
        assertEquals(" value ", assertInstanceOf(PageNode.Script.class, page.nodes().get(1)).code());
        assertEquals("}", text(page.nodes().get(2)));
    }

    @Test
    void defaultNamespaceRootAlsoSelectsXmlSyntax() {
        assertEquals(Page.Syntax.XML, parser.parse("/default.jsp", "<root xmlns='"
                + PageParser.JSP_NAMESPACE + "' version='4.0'><text>hello</text></root>").syntax());
    }

    @Test
    void doctypeIsRejectedBeforeAReadableExternalEntityCouldBeUsed(@TempDir Path directory) throws Exception {
        var entity = directory.resolve("entity.txt");
        Files.writeString(entity, "EXTERNAL_ENTITY_MARKER");
        var error = assertThrows(PageParseException.class, () -> parser.parse("/external.jspx",
                "<!DOCTYPE p [<!ENTITY x SYSTEM '" + entity.toUri() + "'>]><p>&x;</p>"));
        assertTrue(error.getMessage().contains("DOCTYPE declarations are not supported"));
        for (String declaration : List.of("<!DOCTYPE p SYSTEM '" + entity.toUri() + "'>",
                "<!DOCTYPE p [<!ENTITY x 'internal'>]>")) {
            var rejected = assertThrows(PageParseException.class,
                    () -> parser.parse("/doctype.jspx", declaration + "<p/>"));
            assertTrue(rejected.getMessage().contains("DOCTYPE declarations are not supported"));
        }
    }

    @Test
    void escapedElInDirectiveAttributesIsLiteral() {
        var page = parser.parse("/info.jsp", "<%@ page info='\\${literal} \\#{literal}' %>");
        assertEquals("page", assertInstanceOf(PageNode.Directive.class, page.nodes().getFirst()).name());
    }
}

