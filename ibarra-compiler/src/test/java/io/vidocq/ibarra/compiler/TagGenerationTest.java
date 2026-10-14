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

import java.io.File;
import java.lang.module.ModuleFinder;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TagGenerationTest {
    @TempDir Path directory;

    @Test
    void tagDependentBodiesPreserveElActionsDirectivesAndUnfinishedScriptingAsLiteral() throws Exception {
        var options = rawOptions();
        String body = "${missing} #{missing}<jsp:include page='absent.jsp'/><%@ include file='absent.jsp' %><% unfinished";
        assertEquals("[null:" + body + "]", render("<x:raw>" + body + "</x:raw>", false, options));
    }

    @Test
    void standardTagDependentBodiesEndAtFirstExactClosingDelimiterAndReportOpeningLocation() throws Exception {
        var options = rawOptions();
        assertEquals("[null:a<x:raw>inside]b", render("<x:raw>a<x:raw>inside</x:raw >b", false, options));
        assertEquals("[null:<!-- literal]tail -->", render("<x:raw><!-- literal</x:raw>tail -->", false, options));
        var failure = assertThrows(PageParseException.class, () -> generator().generate("/raw.jsp",
                prefix()+"\n<x:raw>missing", "fixture.GeneratedPage", options));
        assertEquals(2, failure.location().line());
        assertTrue(failure.getMessage().contains("Unclosed"));
    }

    @Test
    void synchronizesStaticTagExtraInfoVariablesAcrossClassicTagLifecycle() throws Exception {
        assertEquals("2:2;3:3;5:5", render("<x:sync><% out.print(begin + \":\" + nested + \";\"); %></x:sync>"
                + "<% out.print(begin + \":\" + end); %>"));
    }

    @Test
    void synchronizesStaticTagExtraInfoVariablesAfterSimpleTagExecution() throws Exception {
        assertEquals("1:2", render("<x:simpleSync/><% out.print(begin + \":\" + end); %>"));
    }

    @Test
    void tagDependentBindingCanComeFromTranslationTimeInclude() throws Exception {
        var options = rawOptions();
        var included = new GenerationOptions(options.propertyGroups(), Map.of("/binding.jspf", "<%@ taglib prefix='r' uri='urn:test' %>"),
                false, options.byteSources(), options.tagLibraries());
        assertEquals("[null:<% unfinished]", render("<%@ include file='binding.jspf' %><r:raw><% unfinished</r:raw>", false, included));
    }

    @Test
    void tagDependentBindingsFlowFromPreludeAndIntoIncludedBodies() throws Exception {
        var options = rawOptions();
        var configured = new GenerationOptions(List.of(new JspPropertyGroup(List.of("*.jsp"), Map.of(), List.of("/binding.jspf"), List.of())),
                Map.of("/binding.jspf", "<%@ taglib prefix='r' uri='urn:test' %>", "/raw.jspf", "<r:raw><% unfinished</r:raw>"),
                false, options.byteSources(), options.tagLibraries());
        assertEquals("[null:<% unfinished]", render("<%@ include file='raw.jspf' %>", false, configured));
        String bytes = "<r:raw><%@ page pageEncoding='UTF-8' %>é</r:raw>";
        assertTrue(generator().generate("/page.jsp", bytes.getBytes(StandardCharsets.ISO_8859_1), "fixture.GeneratedPage", configured).source().contains("é"));
    }

    private GenerationOptions rawOptions() throws Exception {
        var options = options();
        var library = options.tagLibraries().get("urn:test");
        var tags = new LinkedHashMap<>(library.tags());
        tags.put("raw", new TagDescriptor("raw", "fixture.Tags$Echo", "tagdependent", Map.of(
                "label", new TagAttribute("label", "java.lang.String", false, true, false),
                "count", new TagAttribute("count", "int", false, false, false)), false, false));
        tags.put("rawBuffer", new TagDescriptor("rawBuffer", "fixture.Tags$Buffer", "tagdependent", Map.of(), false, false));
        tags.put("rawFragment", new TagDescriptor("rawFragment", "fixture.Tags$Fragment", "tagdependent",
                library.tags().get("fragment").attributes(), false, false));
        return options.withTagLibraries(Map.of("urn:test", new TagLibraryDescriptor(library.resourcePath(), library.uri(), library.listenerClasses(), tags,
                library.validationRequired(), library.functions())));
    }

    @Test
    void xmlTagDependentBodiesPreserveLiteralMarkupAndDecodedCdata() throws Exception {
        var options = rawOptions();
        String head = "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' xmlns:x='urn:test' version='4.0'><jsp:directive.page session='false'/>";
        String markup = "${missing}<jsp:include page='missing.jsp'/><x:raw>nested</x:raw>";
        assertEquals("[null:" + markup + "]", render(head+"<x:raw>"+markup+"</x:raw></jsp:root>", false, options));
        String text = "${missing}<%@ include file='missing.jsp' %><% unfinished";
        assertEquals("[null:"+text+"]", render(head+"<x:raw><![CDATA["+text+"]]></x:raw></jsp:root>", false, options));
    }

    @Test
    void tagDependentByteBodyDoesNotChangeSourceEncoding() throws Exception {
        prepare();
        var options = rawOptions();
        String source = prefix()+"<x:raw><%@ page pageEncoding='UTF-8' %>é</x:raw>";
        var generated = generator().generate("/raw.jsp", source.getBytes(StandardCharsets.ISO_8859_1), "fixture.GeneratedPage", options);
        assertTrue(generated.source().contains("é"));
    }

    @Test
    void tagDependentExplicitEnvelopesEvaluateNamedAttributesAndEchoTheBodyVerbatim() throws Exception {
        var options = rawOptions();
        String envelope = "<x:raw><jsp:attribute name='label'>named</jsp:attribute><jsp:body>${missing}<% unfinished</jsp:body></x:raw>";
        assertEquals("[named:${missing}<% unfinished]", render(envelope, false, options));
        String head = "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' xmlns:x='urn:test' version='4.0'><jsp:directive.page session='false'/>";
        assertEquals("[named:${missing}<% unfinished]", render(head + envelope.replace("${missing}<% unfinished", "<![CDATA[${missing}<% unfinished]]>") + "</jsp:root>", false, options));
    }

    @Test
    void tagDependentExplicitBodiesRetainWhitespaceAndDoNotCreateBeanVariables() throws Exception {
        var options = rawOptions();
        String raw = "  ${unknown}<jsp:useBean id='bean' class='missing.Bean'/><%@ include file='absent.jsp' %>  ";
        assertEquals("[null:" + raw + "]", render("<x:raw>\n<jsp:body>" + raw + "</jsp:body>\t</x:raw>", false, options));
        assertEquals("[${UNKNOWN}]", render("<x:rawBuffer><jsp:body>${unknown}</jsp:body></x:rawBuffer>", false, options));
        assertEquals("[3:]", render("<x:raw><jsp:attribute name='label'>${1+2}</jsp:attribute></x:raw>", false, options));
        assertEquals("[named:]", render("<x:raw><jsp:attribute name='label'> named </jsp:attribute><jsp:body/></x:raw>", false, options));
        assertEquals("[script:${literal}]", render("<x:raw><jsp:attribute name='label'><%= \"script\" %></jsp:attribute><jsp:body>${literal}</jsp:body></x:raw>", false, options));
        assertEquals("<P>P${literal}", render("<x:rawFragment><jsp:attribute name='piece'><x:fragmentParent/></jsp:attribute><jsp:body>${literal}</jsp:body></x:rawFragment>", false, options));
    }

    @Test
    void tagDependentExplicitEnvelopeFailuresKeepTheirSourceLocations() throws Exception {
        prepare();
        var options = rawOptions();
        for (String content : List.of("<jsp:body>a</jsp:body><jsp:body>b</jsp:body>",
                "<jsp:body>a</jsp:body><jsp:attribute name='label'>late</jsp:attribute>",
                "<jsp:attribute name='label'>a</jsp:attribute><jsp:attribute name='label'>b</jsp:attribute>",
                "<jsp:attribute name='label'>a</jsp:attribute>unwrapped", "<jsp:body>missing")) {
            var failure = assertThrows(PageParseException.class, () -> generator().generate("/bad.jsp",
                    prefix() + "\n<x:raw>" + content + "</x:raw>", "fixture.GeneratedPage", options));
            assertEquals("/bad.jsp", failure.location().source()); assertEquals(2, failure.location().line());
            assertFalse(failure.getMessage().contains("absent from the build module path"), failure.getMessage());
        }
    }

    @Test
    void xmlTagDependentEnvelopesRecognizeJspAliasesAndKeepPayloadNamespacesLiteral() throws Exception {
        var options = rawOptions();
        String head = "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' xmlns:j='http://java.sun.com/JSP/Page' xmlns:x='urn:test' version='4.0'><jsp:directive.page session='false'/>";
        String payload = "${missing}<j:attribute name='label'>literal</j:attribute><j:body>nested</j:body>";
        assertEquals("[3:" + payload + "]", render(head + "<x:raw>\n<j:attribute name='label'>${1+2}</j:attribute>\n<j:body>" + payload + "</j:body>\n</x:raw></jsp:root>", false, options));
        assertEquals("[null:  literal  ]", render(head + "<x:raw><j:body><![CDATA[  literal  ]]></j:body></x:raw></jsp:root>", false, options));
        assertEquals("[3:]", render(head + "<x:raw><j:attribute name='label'>${1+2}</j:attribute></x:raw></jsp:root>", false, options));
        for (String content : List.of("<j:body>a</j:body><j:body>b</j:body>", "<j:attribute name='label'>a</j:attribute>unwrapped",
                "<j:body invalid='x'>a</j:body>"))
            assertThrows(PageParseException.class, () -> render(head + "<x:raw>" + content + "</x:raw></jsp:root>", false, options));
    }

    @Test
    void explicitDependentPayloadDoesNotLeakEncodingDirectivesOrBeanDeclarations() throws Exception {
        prepare();
        var options = rawOptions();
        String source = prefix() + "<x:raw><jsp:attribute name='label'>named</jsp:attribute><jsp:body><%@ page pageEncoding='UTF-8' %>é</jsp:body></x:raw>";
        assertTrue(generator().generate("/bytes.jsp", source.getBytes(StandardCharsets.ISO_8859_1), "fixture.GeneratedPage", options).source().contains("é"));
        String namedEncoding = prefix() + "<x:raw><jsp:attribute name='label'><%@ page pageEncoding='UTF-8' %>é</jsp:attribute>"
                + "<jsp:body><%@ page pageEncoding='ISO-8859-1' %></jsp:body></x:raw>";
        assertTrue(generator().generate("/named-bytes.jsp", namedEncoding.getBytes(StandardCharsets.UTF_8),
                "fixture.GeneratedPage", options).source().contains("é"));
        var failure = assertThrows(PageParseException.class, () -> generator().generate("/scope.jsp", prefix()
                + "<x:raw><jsp:body><jsp:useBean id='bean' class='missing.Bean'/></jsp:body></x:raw>"
                + "<jsp:getProperty name='bean' property='value'/>", "fixture.GeneratedPage", options));
        assertTrue(failure.getMessage().contains("bean"));
    }

    @Test
    void xmlOutputOmissionAcceptsItsSpecifiedStringAliasesWithoutChangingPageBooleans() throws Exception {
        String head = "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' version='4.0'><jsp:directive.page session='false'/>";
        for (String value : List.of("true", "yes", "false", "no"))
            assertEquals(value.equals("true") || value.equals("yes") ? "body" : "<?xml version=\"1.0\" encoding=\"UTF-8\"?>body",
                    render(head + "<jsp:output omit-xml-declaration='" + value + "'/><jsp:text>body</jsp:text></jsp:root>", false, options()));
        for (String value : List.of("YES", "False", "1", ""))
            assertThrows(PageParseException.class, () -> generator().generate("/output.jspx", head
                    + "<jsp:output omit-xml-declaration='" + value + "'/></jsp:root>", "fixture.GeneratedPage", options()));
        assertThrows(PageParseException.class, () -> generator().generate("/boolean.jspx",
                "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' version='4.0'><jsp:directive.page session='yes'/></jsp:root>",
                "fixture.GeneratedPage", options()));
    }

    @Test
    void xmlOutputRejectsNonemptyBodiesBeforeWritingADeclaration() {
        String head = "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' version='4.0'>";
        for (String body : List.of("Test FAILED", "${1+2}", "<jsp:text>payload</jsp:text>", "<b/>", " ", "\n")) {
            var failure = assertThrows(PageParseException.class, () -> generator().generate("/output-body.jspx",
                    head + "\n<jsp:output omit-xml-declaration='yes'>" + body + "</jsp:output></jsp:root>",
                    "fixture.GeneratedPage", options()));
            assertEquals(2, failure.location().line());
            assertTrue(failure.getMessage().contains("empty body"));
        }
        assertThrows(PageParseException.class, () -> generator().generate("/standard.jsp",
                "<jsp:output omit-xml-declaration='yes'/>", "fixture.GeneratedPage", options()));
        assertDoesNotThrow(() -> generator().generate("/empty.jspx",
                head + "<jsp:output omit-xml-declaration='yes'></jsp:output></jsp:root>", "fixture.GeneratedPage", options()));
    }

    @Test
    void xmlDoctypesAreEmittedBeforeTheDocumentRootWithoutExecutingTheOutputAction() throws Exception {
        String head = "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' version='4.0'><jsp:directive.page session='false'/>";
        assertEquals("<!DOCTYPE html SYSTEM \"test.dtd\"><html>body</html>", render(head
                + "<html><jsp:output omit-xml-declaration='yes' doctype-root-element='html' doctype-system='test.dtd'/>body</html></jsp:root>", false, options()));
        assertEquals("<!DOCTYPE html PUBLIC \"-//Example//DTD Page//EN\" \"https://example.invalid/page.dtd\"><html/>", render(head
                + "<jsp:scriptlet>if (false) {</jsp:scriptlet>"
                + "<jsp:output omit-xml-declaration='yes' doctype-root-element='html' doctype-system='https://example.invalid/page.dtd' doctype-public='-//Example//DTD Page//EN'/>"
                + "<jsp:scriptlet>}</jsp:scriptlet><html/></jsp:root>", false, options()));
    }

    @Test
    void xmlDoctypeIdentifiersSelectQuotesWithoutEscapingTheirLiteralValues() throws Exception {
        String head = "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' version='4.0'><jsp:directive.page session='false'/>";
        assertEquals("<!DOCTYPE p PUBLIC '-//Example \"Page\"//EN' 'page\"name.dtd'><p/>", render(head
                + "<jsp:output omit-xml-declaration='yes' doctype-root-element='p' doctype-public='-//Example &quot;Page&quot;//EN' doctype-system='page&quot;name.dtd'/><p/></jsp:root>", false, options()));
        assertEquals("<!DOCTYPE p SYSTEM \"page'name.dtd\"><p/>", render(head
                + "<jsp:output omit-xml-declaration='yes' doctype-root-element='p' doctype-system=\"page'name.dtd\"/><p/></jsp:root>", false, options()));
    }

    @Test
    void xmlDoctypePrecedesPreservedPrologMiscWithoutParsingOrFetchingItsSystemIdentifier() throws Exception {
        String head = "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' version='4.0'><jsp:directive.page session='false'/>";
        assertEquals("<!DOCTYPE p SYSTEM \"https://example.invalid/never-fetched.dtd\"><?before root?> \n<p/>", render(head
                + "<jsp:text>&lt;?before root?&gt; \n</jsp:text><p/>"
                + "<jsp:output omit-xml-declaration='yes' doctype-root-element='p' doctype-system='https://example.invalid/never-fetched.dtd'/></jsp:root>", false, options()));
    }

    @Test
    void xmlDoctypeCollectionDoesNotInspectTagdependentLiteralPayloads() throws Exception {
        String head = "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' xmlns:x='urn:test' version='4.0'><jsp:directive.page session='false'/>";
        String payload = "<jsp:output doctype-root-element='literal' doctype-system='literal.dtd'/>";
        assertEquals("[null:" + payload + "]", render(head + "<jsp:output omit-xml-declaration='yes'/>"
                + "<x:raw><jsp:body><![CDATA[" + payload + "]]></jsp:body></x:raw></jsp:root>", false, rawOptions()));
    }

    @Test
    void xmlDoctypeValuesStayStaticLiteralsAndRecognizeJspNamespaceAliases() throws Exception {
        String head = "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' version='4.0'><jsp:directive.page session='false'/>";
        assertEquals("<!DOCTYPE p SYSTEM \"${param.dtd}\"><p/>", render(head
                + "<alias:output xmlns:alias='http://java.sun.com/JSP/Page' omit-xml-declaration='yes' doctype-root-element='p' doctype-system='${param.dtd}'/><p/></jsp:root>", false, options()));
        assertEquals("<!DOCTYPE p SYSTEM \"a\nb\rc.dtd\"><p/>", render(head
                + "<output xmlns='http://java.sun.com/JSP/Page' omit-xml-declaration='yes' doctype-root-element='p' doctype-system='a&#10;b&#13;c.dtd'/><p/></jsp:root>", false, options()));
        var failure = assertThrows(PageParseException.class, () -> generator().generate("/qualified.jspx", head
                + "<jsp:output xmlns:foreign='urn:foreign' foreign:doctype-system='test.dtd'/><p/></jsp:root>", "fixture.GeneratedPage", options()));
        assertTrue(failure.getMessage().contains("Unsupported action attribute"));
    }

    @Test
    void xmlDoctypeAttributesRequireRootAndSystemTogetherAndPublicRequiresSystem() {
        String head = "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' version='4.0'>\n";
        for (String attributes : List.of("doctype-root-element='p'", "doctype-system='test.dtd'",
                "doctype-public='public'", "doctype-root-element='p' doctype-public='public'")) {
            var failure = assertThrows(PageParseException.class, () -> generator().generate("/doctype.jspx", head
                    + "<jsp:output " + attributes + "/></jsp:root>", "fixture.GeneratedPage", options()));
            assertEquals(2, failure.location().line());
            assertTrue(failure.getMessage().contains("requires"), failure.getMessage());
        }
        var split = assertThrows(PageParseException.class, () -> generator().generate("/split.jspx", head
                + "<jsp:output doctype-root-element='p'/><jsp:output doctype-system='test.dtd'/></jsp:root>", "fixture.GeneratedPage", options()));
        assertTrue(split.getMessage().contains("requires"));
    }

    @Test
    void xmlDoctypeMetadataIsDeduplicatedAcrossStaticIncludesAndConflictsKeepIncludeLocations() throws Exception {
        String head = "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' version='4.0'><jsp:directive.page session='false'/>";
        String output = "<jsp:output omit-xml-declaration='yes' doctype-root-element='p' doctype-system='test.dtd'/>";
        var defaults = options();
        String piece = "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' version='4.0'>" + output + "</jsp:root>";
        var included = new GenerationOptions(defaults.propertyGroups(), Map.of("/piece.jspx", piece), false,
                defaults.byteSources(), defaults.tagLibraries());
        assertEquals("<!DOCTYPE p SYSTEM \"test.dtd\"><p/>", render(head + output
                + "<jsp:directive.include file='piece.jspx'/><jsp:directive.include file='piece.jspx'/><p/></jsp:root>", false, included));
        for (String attribute : List.of("doctype-root-element", "doctype-system", "doctype-public")) {
            String original = output.replace("/>", " doctype-public='public'/>");
            String replacement = original.replace(attribute + "='" + switch (attribute) {
                case "doctype-root-element" -> "p"; case "doctype-system" -> "test.dtd"; default -> "public";
            } + "'", attribute + "='different'");
            var conflicting = new GenerationOptions(defaults.propertyGroups(), Map.of("/piece.jspx",
                    "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' version='4.0'>\n" + replacement + "</jsp:root>"), false,
                    defaults.byteSources(), defaults.tagLibraries());
            var failure = assertThrows(PageParseException.class, () -> generator().generate("/doctype.jspx", head + original
                    + "<jsp:directive.include file='piece.jspx'/><p/></jsp:root>", "fixture.GeneratedPage", conflicting));
            assertEquals("/piece.jspx", failure.location().source());
            assertEquals(2, failure.location().line());
            assertTrue(failure.getMessage().contains(attribute));
        }
    }

    @Test
    void jspIdsAreUniqueStableAndInitializedForInheritedClassicAndSimpleConsumers() throws Exception {
        var options = jspIdOptions();
        String output = render("<x:simple label='one'/>|<x:classic/>|<x:simple label='two'/>;", false, options, 2);
        String[] requests = output.split(";");
        assertEquals(2, requests.length); assertEquals(requests[0], requests[1]);
        String[] ids = requests[0].split("\\|");
        assertEquals(3, new HashSet<>(List.of(ids)).size());
        for (String id : ids) assertTrue(id.matches("[A-Za-z_][A-Za-z0-9_-]*"), id);
    }

    @Test
    void repeatedStaticIncludesReceiveDistinctIdsEvenWithIdenticalSourceLocations() throws Exception {
        var ids = jspIdOptions();
        var options = new GenerationOptions(ids.propertyGroups(), Map.of("/piece.jspf", "<x:simple/>"), false, ids.byteSources(), ids.tagLibraries());
        String[] values = render("<%@ include file='piece.jspf' %>|<%@ include file='piece.jspf' %>", false, options).split("\\|");
        assertEquals(2, values.length); assertNotEquals(values[0], values[1]);
    }

    @Test
    void tagsInRepeatedFragmentsKeepTheirOwnIdsAndFreshHandlerInstances() throws Exception {
        var options = jspIdOptions();
        String[] ids = render("<x:echo label='fragment' count='2'><x:simple/>|<x:classic/>;</x:echo>", false,
                new GenerationOptions(options.propertyGroups(), options.sources(), false, options.byteSources(),
                        Map.of("urn:test", mergeJspIds(options.tagLibraries().get("urn:test"), options().tagLibraries().get("urn:test"))))).replace("[fragment:", "").replace("]", "").split(";");
        assertEquals(2, ids.length); assertEquals(ids[0], ids[1]);
        String[] nested = ids[0].split("\\|"); assertNotEquals(nested[0], nested[1]);
    }

    private TagLibraryDescriptor mergeJspIds(TagLibraryDescriptor ids, TagLibraryDescriptor ordinary) {
        var tags = new LinkedHashMap<>(ordinary.tags()); tags.putAll(ids.tags());
        return new TagLibraryDescriptor(ids.resourcePath(), ids.uri(), ids.listenerClasses(), tags, ids.validationRequired(), ids.functions());
    }

    @Test
    void ordinaryAndNamedAttributesCannotOverwriteTheCompilerIdentifierSetter() throws Exception {
        jspIdOptions();
        var library = new TldParser().parse("/WEB-INF/override.tld", ("<taglib><uri>urn:test</uri><tag><name>simple</name><tag-class>fixture.Ids$Simple</tag-class><body-content>empty</body-content>"
                + "<attribute><name>jspId</name></attribute><attribute><name>JspId</name></attribute></tag>"
                + "<tag><name>dynamic</name><tag-class>fixture.Ids$Dynamic</tag-class><body-content>empty</body-content><dynamic-attributes>true</dynamic-attributes></tag></taglib>").getBytes(StandardCharsets.UTF_8));
        var options = GenerationOptions.DEFAULT.withTagLibraries(Map.of("urn:test", library));
        for (String source : List.of("<x:simple jspId='caller'/>", "<x:simple JspId='caller'/>",
                "<x:simple><jsp:attribute name='jspId'>caller</jsp:attribute></x:simple>",
                "<x:simple><jsp:attribute name='x:JspId'>caller</jsp:attribute></x:simple>",
                "<x:dynamic jspId='caller'/>", "<x:dynamic><jsp:attribute name='jspId'>caller</jsp:attribute></x:dynamic>")) {
            var failure = assertThrows(PageParseException.class, () -> generator().generate("/tags.jsp", prefix()+source, "fixture.GeneratedPage", options));
            assertTrue(failure.getMessage().contains("compiler-provided jspId"), failure.getMessage());
        }
    }

    @Test
    void xmlTagsReceiveStableIdentifiersAcrossRequests() throws Exception {
        String source = "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' xmlns:x='urn:test' version='3.0'>"
                + "<jsp:directive.page session='false'/><x:simple label='xml'/>|<x:classic/>;</jsp:root>";
        String[] requests = render(source, false, jspIdOptions(), 2).split(";");
        assertEquals(2, requests.length); assertEquals(requests[0], requests[1]);
        String[] ids = requests[0].split("\\|"); assertEquals(2, ids.length); assertNotEquals(ids[0], ids[1]);
    }

    private GenerationOptions jspIdOptions() throws Exception {
        prepare();
        compile(List.of(new GeneratedPage("fixture.Ids", """
                package fixture;
                import jakarta.servlet.jsp.*; import jakarta.servlet.jsp.tagext.*;
                public class Ids {
                    public interface Consumer extends JspIdConsumer { }
                    public static class Base extends SimpleTagSupport implements Consumer {
                        protected String id;
                        public void setJspId(String value) {
                            if (id != null || getJspContext() == null) throw new AssertionError("Reused tag or missing context");
                            id=value;
                        }
                        public void setLabel(String label) { if (id == null) throw new AssertionError("ID not initialized before properties"); }
                    }
                    public static class Simple extends Base {
                        public void doTag() throws java.io.IOException {
                            if (id == null) throw new AssertionError("Missing JSP ID"); getJspContext().getOut().write(id);
                        }
                    }
                    public static class Classic extends TagSupport implements Consumer {
                        private String id;
                        public void setJspId(String value) {
                            if (id != null || pageContext == null) throw new AssertionError("Reused tag or missing context"); id=value;
                        }
                        public int doStartTag() throws JspException {
                            if (id == null) throw new AssertionError("Missing JSP ID");
                            try { pageContext.getOut().write(id); } catch(java.io.IOException failure) { throw new JspException(failure); }
                            return SKIP_BODY;
                        }
                    }
                    public static class Dynamic extends Simple implements DynamicAttributes {
                        public void setDynamicAttribute(String uri, String name, Object value) { }
                    }
                }
                """, Map.of())));
        var library = new TldParser().parse("/WEB-INF/ids.tld", ("<taglib><uri>urn:test</uri>"
                + "<tag><name>simple</name><tag-class>fixture.Ids$Simple</tag-class><body-content>empty</body-content><attribute><name>label</name></attribute></tag>"
                + "<tag><name>classic</name><tag-class>fixture.Ids$Classic</tag-class><body-content>empty</body-content></tag></taglib>").getBytes(StandardCharsets.UTF_8));
        return GenerationOptions.DEFAULT.withTagLibraries(Map.of("urn:test", library));
    }

    @Test
    void ordinaryRuntimeAttributesBindActualInheritedSetterTypes() throws Exception {
        var library = new TldParser().parse("/WEB-INF/ordinary.tld", ("<taglib><uri>urn:test</uri>"
                + "<tag><name>implicitText</name><tag-class>fixture.Tags$RuntimeText</tag-class><body-content>scriptless</body-content><attribute><name>label</name><rtexprvalue>true</rtexprvalue></attribute></tag>"
                + "<tag><name>typedCount</name><tag-class>fixture.Tags$CountOnly</tag-class><body-content>empty</body-content><attribute><name>count</name><type>java.lang.String</type><rtexprvalue>true</rtexprvalue></attribute></tag></taglib>").getBytes(StandardCharsets.UTF_8));
        var options = GenerationOptions.DEFAULT.withTagLibraries(Map.of("urn:test", library));
        assertEquals("[text:bodybody]|[named:bodybody]|8|9", render("<x:implicitText label='<%=\"text\"%>'>body</x:implicitText>|"
                + "<x:implicitText><jsp:attribute name='label'><%=\"named\"%></jsp:attribute><jsp:body>body</jsp:body></x:implicitText>|"
                + "<x:typedCount count='08'/>|<x:typedCount><jsp:attribute name='count'>9</jsp:attribute></x:typedCount>", false, options));
    }

    @Test
    void ordinarySetterOverloadsChooseMostSpecificCompatiblePropertyType() throws Exception {
        assertEquals("Sliteral", render("<x:overload value='literal'/>"));
        prepare();
        compile(List.of(new GeneratedPage("fixture.Ambiguous", "package fixture; public class Ambiguous extends jakarta.servlet.jsp.tagext.SimpleTagSupport { public void setValue(String value){} public void setValue(Integer value){} }", Map.of())));
        var library = new TldParser().parse("/WEB-INF/ambiguous.tld", "<taglib><uri>urn:test</uri><tag><name>ambiguous</name><tag-class>fixture.Ambiguous</tag-class><attribute><name>value</name><rtexprvalue>true</rtexprvalue></attribute></tag></taglib>".getBytes(StandardCharsets.UTF_8));
        assertThrows(PageParseException.class, () -> generator().generate("/tags.jsp", prefix()+"<x:ambiguous value='x'/>", "fixture.GeneratedPage",
                GenerationOptions.DEFAULT.withTagLibraries(Map.of("urn:test", library))));
    }

    @Test
    void getterGuidanceAndNestedArrayCovarianceSelectTheCorrectOrdinarySetter() throws Exception {
        prepare();
        compile(List.of(new GeneratedPage("fixture.Relations", """
                package fixture;
                public class Relations {
                    public static class Parent { } public static class Child extends Parent { }
                    public static class Getter extends jakarta.servlet.jsp.tagext.SimpleTagSupport {
                        private String selected; public Number getValue(){return null;}
                        public void setValue(Integer value){selected="integer";} public void setValue(Object value){selected="object";}
                        public void doTag() throws java.io.IOException {getJspContext().getOut().write(selected);}
                    }
                    public static class Arrays extends jakarta.servlet.jsp.tagext.SimpleTagSupport {
                        private String selected;
                        public Parent[] getValue(){return null;}
                        public void setValue(Parent[] value){selected="parent";} public void setValue(Child[] value){selected="child";}
                        public void doTag() throws java.io.IOException {getJspContext().getOut().write(selected);}
                    }
                }
                """, Map.of())));
        var library = new TldParser().parse("/WEB-INF/relations.tld", ("<taglib><uri>urn:test</uri>"
                + "<tag><name>getter</name><tag-class>fixture.Relations$Getter</tag-class><body-content>empty</body-content><attribute><name>value</name><rtexprvalue>true</rtexprvalue></attribute></tag>"
                + "<tag><name>arrays</name><tag-class>fixture.Relations$Arrays</tag-class><body-content>empty</body-content><attribute><name>value</name><rtexprvalue>true</rtexprvalue></attribute></tag></taglib>").getBytes(StandardCharsets.UTF_8));
        assertEquals("integer|child", render("<x:getter value='7'/>|<x:arrays value='<%=new fixture.Relations.Child[0]%>'/>", false,
                GenerationOptions.DEFAULT.withTagLibraries(Map.of("urn:test", library))));
    }

    @Test
    void tagGetterCompatibilityMatchesJavaBeansForUniqueAndOverloadedSetters() throws Exception {
        prepare();
        compile(List.of(new GeneratedPage("fixture.GetterContracts", """
                package fixture;
                public class GetterContracts {
                    public static class ReadObject extends jakarta.servlet.jsp.tagext.SimpleTagSupport {
                        private String selected; public Object getValue(){return null;}
                        public void setValue(Object value){selected="object";} public void setValue(String value){selected="string";}
                        public void doTag() throws java.io.IOException {getJspContext().getOut().write(selected);}
                    }
                    public static class ReadString extends jakarta.servlet.jsp.tagext.SimpleTagSupport {
                        public String getValue(){return null;}
                        public void setValue(Object value){} public void setValue(CharSequence value){}
                    }
                    public static class Unique extends jakarta.servlet.jsp.tagext.SimpleTagSupport {
                        public String getValue(){return null;} public void setValue(Object value){}
                    }
                    public static class BooleanRead extends jakarta.servlet.jsp.tagext.SimpleTagSupport {
                        private String selected; public boolean isValue(){return false;} public String getValue(){return null;}
                        public void setValue(boolean value){selected="boolean";} public void setValue(String value){selected="string";}
                        public void doTag() throws java.io.IOException {getJspContext().getOut().write(selected);}
                    }
                }
                """, Map.of())));
        StringBuilder body = new StringBuilder("<taglib><uri>urn:test</uri>");
        for (String name : List.of("ReadObject", "ReadString", "Unique", "BooleanRead")) body.append("<tag><name>").append(name).append("</name><tag-class>fixture.GetterContracts$").append(name)
                .append("</tag-class><body-content>empty</body-content><attribute><name>value</name><rtexprvalue>true</rtexprvalue></attribute></tag>");
        var library = new TldParser().parse("/WEB-INF/getters.tld", body.append("</taglib>").toString().getBytes(StandardCharsets.UTF_8));
        var options = GenerationOptions.DEFAULT.withTagLibraries(Map.of("urn:test", library));
        assertEquals("string|boolean", render("<x:ReadObject value='text'/>|<x:BooleanRead value='true'/>", false, options));
        for (String name : List.of("ReadString", "Unique"))
            assertThrows(PageParseException.class, () -> generator().generate("/tags.jsp", prefix()+"<x:"+name+" value='text'/>", "fixture.GeneratedPage", options));
    }

    @Test
    void keywordAttributesRenderWithDirectPrimitiveAndNamedSetterCalls() throws Exception {
        prepare();
        compile(List.of(new GeneratedPage("fixture.Keywords", """
                package fixture;
                public class Keywords extends jakarta.servlet.jsp.tagext.SimpleTagSupport {
                    private boolean bool; private byte number; private String text;
                    public void setBoolean(boolean value){bool=value;} public void setByte(byte value){number=value;} public void setStatic(String value){text=value;}
                    public void doTag() throws java.io.IOException {getJspContext().getOut().write(bool+":"+number+":"+text);}
                }
                """, Map.of())));
        var library = new TldParser().parse("/WEB-INF/keywords.tld", ("<taglib><uri>urn:test</uri><tag><name>keywords</name><tag-class>fixture.Keywords</tag-class><body-content>empty</body-content>"
                + "<attribute><name>boolean</name><type>java.lang.Boolean</type><rtexprvalue>true</rtexprvalue></attribute>"
                + "<attribute><name>byte</name><rtexprvalue>true</rtexprvalue></attribute><attribute><name>static</name><type>java.lang.Object</type><rtexprvalue>true</rtexprvalue></attribute></tag></taglib>").getBytes(StandardCharsets.UTF_8));
        assertEquals("true:7:word", render("<x:keywords boolean='true' byte='7'><jsp:attribute name='static'>word</jsp:attribute></x:keywords>", false,
                GenerationOptions.DEFAULT.withTagLibraries(Map.of("urn:test", library))));
    }
    @Test void simpleAttributesAndRepeatedFragmentEvaluationRenderWithoutEl() throws Exception {
        assertEquals("[hello:bodybody]", render("<x:echo label='hello' count='2'>body</x:echo>"));
    }
    @Test void namedStaticAttributesTrimOnlyTemplateWhitespaceAndAllowQualifiedNames() throws Exception {
        assertEquals("[hello:body]", render("<x:echo>\n<jsp:attribute name='x:label'> hello </jsp:attribute>\n<jsp:body>body</jsp:body>\n</x:echo>"));
        assertEquals("[ hello : ]", render("<x:echo><jsp:attribute name='label' trim='false' omit='true'> hello </jsp:attribute><jsp:body> </jsp:body></x:echo>"));
        assertEquals("[:]|[\u2003: ]", render("<x:echo><jsp:attribute name='label'/></x:echo>|<x:echo><jsp:attribute name='label'>\u2003</jsp:attribute><jsp:body> </jsp:body></x:echo>"));
        assertEquals("[hello:]", render("<x:echo><jsp:attribute name='label'> <jsp:text> </jsp:text><jsp:text> hello </jsp:text><jsp:text> </jsp:text> </jsp:attribute></x:echo>"));
    }
    @Test void runtimeNamedAttributesEvaluateOnceAndPreserveProducedWhitespace() throws Exception {
        assertEquals("[ space :bodybody]|8|a= space ;", render("<x:runtimeText><jsp:attribute name='label'> <x:spaced/> </jsp:attribute><jsp:body>body</jsp:body></x:runtimeText>|<x:runtimeCount><jsp:attribute name='count'><%=8%></jsp:attribute></x:runtimeCount>|<x:dynamic><jsp:attribute name='a'><x:spaced/></jsp:attribute></x:dynamic>"));
    }
    @Test void fragmentAttributesAreLazyRepeatableAndAvailableToClassicAndSimpleHandlers() throws Exception {
        assertEquals("<P>P!|<classic>classic", render("<x:fragment><jsp:attribute name='piece'> <x:fragmentParent/> </jsp:attribute><jsp:body>!</jsp:body></x:fragment>|<x:classicFragment><jsp:attribute name='piece'>classic</jsp:attribute></x:classicFragment>"));
    }
    @Test void explicitBodiesRetainClassicIterationAndBuffering() throws Exception {
        assertEquals("aaR[ABC]", render("<x:repeat count='2'><jsp:body>a</jsp:body></x:repeat><x:buffer><jsp:body>abc</jsp:body></x:buffer>"));
    }
    @Test void namedAttributeFailuresRestoreWritersAndSkipPages() throws Exception {
        assertEquals("before", render("before<x:fragment><jsp:attribute name='piece'><x:simpleSkip/></jsp:attribute></x:fragment>after"));
        assertEquals("CFRtail", render("<x:guard><x:runtimeText><jsp:attribute name='label'><x:fail/></jsp:attribute></x:runtimeText></x:guard>tail"));
        assertEquals("R", render("<x:namedGuard><jsp:attribute name='count'><x:fail/></jsp:attribute></x:namedGuard>", true));
    }
    @Test void namedAttributeEnvelopeRejectsDuplicatesOrderingAndInvalidContent() throws Exception {
        prepare();
        for (String source : List.of(
                "<x:echo label='a'><jsp:attribute name='label'>b</jsp:attribute></x:echo>",
                "<x:echo><jsp:attribute name='label'>a</jsp:attribute><jsp:attribute name='label'>b</jsp:attribute></x:echo>",
                "<x:echo><jsp:attribute name='label'>a</jsp:attribute>body</x:echo>",
                "<x:echo label='a'><jsp:body/><jsp:body/></x:echo>",
                "<x:echo><jsp:body/><jsp:attribute name='label'>a</jsp:attribute></x:echo>",
                "<x:echo><jsp:attribute name='label'><x:spaced/></jsp:attribute></x:echo>",
                "<x:echo><jsp:attribute name='y:label'>a</jsp:attribute></x:echo>",
                "<x:fragment piece='text'/>",
                "<x:fragment><jsp:attribute name='piece'><%=1%></jsp:attribute></x:fragment>",
                "<x:fragment><jsp:attribute name='piece' trim='maybe'/></x:fragment>",
                "<x:fail><jsp:body>body</jsp:body></x:fail>")) {
            assertThrows(PageParseException.class, () -> generator().generate("/tags.jsp", prefix()+source,
                    "fixture.GeneratedPage", options()), source);
        }
    }
    @Test void jspStringConversionsAcceptEmptyNumbersAndCharacterPrefixes() throws Exception {
        assertEquals("0|0|A|A|0", render("<x:countOnly count=''/>|<x:runtimeCount><jsp:attribute name='count'/></x:runtimeCount>|<x:character value='ABC'/>|<x:character><jsp:attribute name='value'>ABC</jsp:attribute></x:character>|<x:character value=''/>").replace("\u0000", "0"));
    }
    @Test void staticXmlNamedAttributesRejectActionsAndActiveExpressions() throws Exception {
        prepare();
        for (String content : List.of("<x:spaced/>", "<b title='${1}'/>", "${1}")) {
            String xml = "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' xmlns:x='urn:test' version='4.0'><x:echo><jsp:attribute name='label'>" + content + "</jsp:attribute></x:echo></jsp:root>";
            assertThrows(PageParseException.class, () -> generator().generate("/tags.jspx", xml,
                    "fixture.GeneratedPage", options()), content);
        }
    }
    @Test void classicIterationAndBufferedBodiesRestoreTheEnclosingWriter() throws Exception {
        assertEquals("aaaRZ[ABC]Z", render("<x:repeat count='3'>a</x:repeat>Z<x:buffer>abc</x:buffer>Z"));
        assertEquals("Z", render("<x:buffer/>Z"));
    }
    @Test void catchFinallyAndReleaseRunWithTheRestoredWriter() throws Exception {
        assertEquals("CFRtail", render("<x:guard><x:fail/></x:guard>tail"));
    }
    @Test void mixedNestingUsesTagAdaptersAndFragmentWriterTargets() throws Exception {
        assertEquals("<P>", render("<x:capture><x:parent/></x:capture>"));
    }
    @Test void skipPageStopsRenderingWithoutInvokingErrorHandling() throws Exception {
        assertEquals("beforeR", render("before<x:skip/>after"));
        assertEquals("before", render("before<x:simpleSkip/>after"));
        assertEquals("beforeCFR", render("before<x:guard><x:skip/></x:guard>after"));
        assertEquals("beforeCFR", render("before<x:guard><x:simpleSkip/></x:guard>after"));
    }
    @Test void bodyContentSetterFailuresBypassCatchFinallyButStillRelease() throws Exception {
        assertEquals("R", render("<x:setterFailure>body</x:setterFailure>", true));
    }
    @Test void ordinaryValuesUseSetterTypesWhileObjectMetadataDefaultsStayIndependent() throws Exception {
        assertEquals("8|42", render("<x:countOnly count='08'/>|<x:objectValue value='<%=42%>'/>"));
        assertEquals("Sliteral",render("<x:overload value='literal'/>"));
    }
    @Test void ordinaryAttributesBeginningWithXmlnsAreNotNamespaceDeclarations() throws Exception {
        assertEquals("[name:body]", render("<x:xmlnsLabel xmlnsLabel='name'>body</x:xmlnsLabel>"));
    }
    @Test void aNormalSkipPageReturnDoesNotInvokeTheReturningTagsOwnCatch() throws Exception {
        assertEquals("beforeFR", render("before<x:ownSkip/>after"));
    }
    @Test void relativeTaglibBindingsRetainTheirDeclarationOriginAcrossIncludes() throws Exception {
        var defaults=options(); var first=defaults.tagLibraries().get("urn:test");
        var tags=new HashMap<>(first.tags()); var echo=tags.get("echo");
        tags.put("echo",new TagDescriptor("echo","fixture.Tags$Second",echo.bodyContent(),echo.attributes(),false,false));
        var second=new TagLibraryDescriptor("/two/lib.tld",null,List.of(),tags,false);
        var libraries=new HashMap<>(defaults.tagLibraries()); libraries.put("/one/lib.tld",first); libraries.put("/two/lib.tld",second);
        var options=new GenerationOptions(List.of(),Map.of(
                "/one/bind.jspf","<%@ taglib prefix='q' uri='lib.tld' %><q:echo label='a'>body</q:echo>",
                "/two/bind.jspf","<%@ taglib prefix='q' uri='lib.tld' %><q:echo label='b'/>"),false,Map.of(),libraries);
        assertEquals("[a:body]SECOND",render("<%@ include file='one/bind.jspf' %><%@ include file='two/bind.jspf' %>",false,options));
    }
    @Test void dynamicAttributesUseDirectInterfaceCalls() throws Exception {
        assertEquals("a=one;b=42;",render("<x:dynamic a='one' b='<%=42%>'/>"));
    }
    @Test void bufferedIterationsRetainContentUntilTheHandlerClearsIt() throws Exception {
        assertEquals("[ia][iaa]",render("<x:bufferedRepeat>a</x:bufferedRepeat>"));
    }
    @Test void unsupportedOrMissingMetadataFailsDuringTranslation() throws Exception {
        prepare();
        for (String source : List.of("<x:echo/>", "<x:echo label='a' count='bad'/>", "<x:echo label='a' unknown='b'/>",
                "<x:echo label='a'><% out.print(1); %></x:echo>", "<x:missing/>")) {
            assertThrows(PageParseException.class, () -> generator().generate("/tags.jsp", prefix()+source,
                    "fixture.GeneratedPage", options()));
        }
    }
    private String prefix() { return "<%@ page session='false' %><%@ taglib prefix='x' uri='urn:test' %>"; }
    private GenerationOptions options() {
        String tld = """
                <taglib><uri>urn:test</uri>
                <tag><name>echo</name><tag-class>fixture.Tags$Echo</tag-class><body-content>scriptless</body-content>
                  <attribute><name>label</name><required>true</required></attribute><attribute><name>count</name><type>int</type></attribute></tag>
                <tag><name>repeat</name><tag-class>fixture.Tags$Repeat</tag-class><body-content>JSP</body-content>
                  <attribute><name>count</name><type>int</type><required>true</required></attribute></tag>
                <tag><name>sync</name><tag-class>fixture.Tags$SyncVariables</tag-class><tei-class>fixture.Tags$Variables</tei-class><body-content>JSP</body-content></tag>
                <tag><name>simpleSync</name><tag-class>fixture.Tags$SimpleSyncVariables</tag-class><tei-class>fixture.Tags$Variables</tei-class><body-content>empty</body-content></tag>
                <tag><name>buffer</name><tag-class>fixture.Tags$Buffer</tag-class><body-content>scriptless</body-content></tag>
                <tag><name>guard</name><tag-class>fixture.Tags$Guard</tag-class><body-content>scriptless</body-content></tag>
                <tag><name>setterFailure</name><tag-class>fixture.Tags$SetterFailure</tag-class><body-content>scriptless</body-content></tag>
                <tag><name>fail</name><tag-class>fixture.Tags$Fail</tag-class><body-content>empty</body-content></tag>
                <tag><name>capture</name><tag-class>fixture.Tags$Capture</tag-class><body-content>scriptless</body-content></tag>
                <tag><name>parent</name><tag-class>fixture.Tags$Parent</tag-class><body-content>empty</body-content></tag>
                <tag><name>skip</name><tag-class>fixture.Tags$Skip</tag-class><body-content>empty</body-content></tag>
                <tag><name>simpleSkip</name><tag-class>fixture.Tags$SimpleSkip</tag-class><body-content>empty</body-content></tag>
                <tag><name>ownSkip</name><tag-class>fixture.Tags$OwnSkip</tag-class><body-content>empty</body-content></tag>
                <tag><name>countOnly</name><tag-class>fixture.Tags$CountOnly</tag-class><body-content>empty</body-content>
                  <attribute><name>count</name><type>java.lang.String</type></attribute></tag>
                <tag><name>objectValue</name><tag-class>fixture.Tags$ObjectValue</tag-class><body-content>empty</body-content>
                  <attribute><name>value</name><rtexprvalue>true</rtexprvalue></attribute></tag>
                <tag><name>overload</name><tag-class>fixture.Tags$Overload</tag-class><body-content>empty</body-content>
                  <attribute><name>value</name><rtexprvalue>true</rtexprvalue></attribute></tag>
                <tag><name>xmlnsLabel</name><tag-class>fixture.Tags$Echo</tag-class><body-content>scriptless</body-content>
                  <attribute><name>xmlnsLabel</name><required>true</required></attribute></tag>
                <tag><name>dynamic</name><tag-class>fixture.Tags$Dynamic</tag-class><body-content>empty</body-content><dynamic-attributes>true</dynamic-attributes></tag>
                <tag><name>bufferedRepeat</name><tag-class>fixture.Tags$BufferedRepeat</tag-class><body-content>scriptless</body-content></tag>
                <tag><name>runtimeText</name><tag-class>fixture.Tags$RuntimeText</tag-class><body-content>scriptless</body-content>
                  <attribute><name>label</name><type>java.lang.String</type><rtexprvalue>true</rtexprvalue><required>true</required></attribute></tag>
                <tag><name>runtimeCount</name><tag-class>fixture.Tags$CountOnly</tag-class><body-content>empty</body-content>
                  <attribute><name>count</name><type>int</type><rtexprvalue>true</rtexprvalue></attribute></tag>
                <tag><name>spaced</name><tag-class>fixture.Tags$Spaced</tag-class><body-content>empty</body-content></tag>
                <tag><name>fragment</name><tag-class>fixture.Tags$Fragment</tag-class><body-content>scriptless</body-content>
                  <attribute><name>piece</name><fragment>true</fragment><required>true</required></attribute></tag>
                <tag><name>classicFragment</name><tag-class>fixture.Tags$ClassicFragment</tag-class><body-content>empty</body-content>
                  <attribute><name>piece</name><fragment>true</fragment><required>true</required></attribute></tag>
                <tag><name>fragmentParent</name><tag-class>fixture.Tags$FragmentParent</tag-class><body-content>empty</body-content></tag>
                <tag><name>character</name><tag-class>fixture.Tags$CharacterValue</tag-class><body-content>empty</body-content>
                  <attribute><name>value</name><type>char</type><rtexprvalue>true</rtexprvalue></attribute></tag>
                <tag><name>namedGuard</name><tag-class>fixture.Tags$NamedGuard</tag-class><body-content>empty</body-content>
                  <attribute><name>count</name><type>int</type><rtexprvalue>true</rtexprvalue></attribute></tag>
                </taglib>
                """;
        var library = new TldParser().parse("/WEB-INF/tags.tld", tld.getBytes(StandardCharsets.UTF_8));
        return GenerationOptions.DEFAULT.withTagLibraries(Map.of("urn:test", library));
    }
    private PageGenerator generator() {
        var paths = new ArrayList<Path>(); paths.add(directory);
        for (String path : modulePath().split(java.util.regex.Pattern.quote(File.pathSeparator))) paths.add(Path.of(path));
        return new PageGenerator(paths);
    }
    private String render(String source) throws Exception {
        return render(source, false);
    }
    private String render(String source, boolean expectFailure) throws Exception {
        return render(source, expectFailure, options());
    }
    private String render(String source, boolean expectFailure, GenerationOptions options) throws Exception {
        return render(source, expectFailure, options, 1);
    }
    private String render(String source, boolean expectFailure, GenerationOptions options, int requests) throws Exception {
        prepare();
        var generated = generator().generate("/tags.jsp", source.startsWith("<jsp:root") ? source : prefix()+source, "fixture.GeneratedPage", options);
        String main = """
                package io.vidocq.ibarra.core.internal;
                import jakarta.servlet.*; import jakarta.servlet.jsp.*;
                public class TagMain {
                    public static void main(String[] args) throws Throwable {
                        if (ModuleLayer.boot().findModule("java.compiler").isPresent()) throw new AssertionError("Runtime compiler present");
                        JspFactory.setDefaultFactory(new IbarraJspFactory());
                        var context = new ServletDoubles.Context(); var response = new ServletDoubles.Response();
                        var page = new fixture.GeneratedPage();
                        page.init(new ServletConfig() {
                            public String getServletName() { return "tags"; }
                            public ServletContext getServletContext() { return context; }
                            public String getInitParameter(String name) { return null; }
                            public java.util.Enumeration<String> getInitParameterNames() { return java.util.Collections.emptyEnumeration(); }
                        });
                        String failureTrace="";
                        try { for (int i=0;i<REQUEST_COUNT;i++) page.service(new ServletDoubles.Request(), response); }
                        catch (Throwable expected) { if (!EXPECT_FAILURE) throw expected; failureTrace=String.valueOf(context.getAttribute("trace")); }
                        if (EXPECT_FAILURE && failureTrace.isEmpty()) throw new AssertionError("Expected servlet failure");
                        System.out.print("RESULT=" + java.util.Base64.getEncoder().encodeToString(response.output.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    }
                }
                """.replace("EXPECT_FAILURE", String.valueOf(expectFailure)).replace("REQUEST_COUNT", String.valueOf(requests)).replace("response.output.toString().getBytes", "(failureTrace+response.output).getBytes");
        Path root=Path.of(System.getProperty("user.dir")).toAbsolutePath(); if(!Files.isDirectory(root.resolve("ibarra-core"))) root=root.getParent();
        String doubles=Files.readString(root.resolve("ibarra-core/src/test/java/io/vidocq/ibarra/core/internal/ServletDoubles.java"));
        compile(List.of(generated,new GeneratedPage("io.vidocq.ibarra.core.internal.TagMain",main,Map.of()),
                new GeneratedPage("io.vidocq.ibarra.core.internal.ServletDoubles",doubles,Map.of())));
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),
                "--limit-modules","io.vidocq.ibarra.core,io.vidocq.peano.core","--add-modules","io.vidocq.peano.core",
                "--add-reads","io.vidocq.ibarra.core=io.vidocq.peano.api","--module-path",modulePath(),
                "--patch-module","io.vidocq.ibarra.core="+directory,"-m","io.vidocq.ibarra.core/io.vidocq.ibarra.core.internal.TagMain")
                .redirectErrorStream(true).redirectOutput(directory.resolve("tags.log").toFile()).start();
        assertTrue(process.waitFor(30,TimeUnit.SECONDS));
        String output=Files.readString(directory.resolve("tags.log")); assertEquals(0,process.exitValue(),output);
        assertTrue(output.startsWith("RESULT="),output);
        return new String(Base64.getDecoder().decode(output.substring(7)),StandardCharsets.UTF_8);
    }
    private void compile(List<GeneratedPage> sources) throws Exception {
        new PageCompiler().compile(sources,directory,List.of("--module-path",modulePath(),"--add-modules","io.vidocq.ibarra.core,io.vidocq.peano.api",
                "--add-reads","io.vidocq.ibarra.core=io.vidocq.peano.api",
                "--patch-module","io.vidocq.ibarra.core="+directory));
    }
    private String modulePath() {
        var names=Set.of("io.vidocq.ibarra.api","io.vidocq.ibarra.core","io.vidocq.peano.api","io.vidocq.peano.core","jakarta.servlet","jakarta.servlet.jsp","jakarta.el");
        String available=System.getProperty("jdk.module.path","")+File.pathSeparator+System.getProperty("surefire.test.class.path","");
        var paths=Arrays.stream(available.split(java.util.regex.Pattern.quote(File.pathSeparator))).filter(s->!s.isEmpty()).map(Path::of)
                .filter(p->p.toString().endsWith(".jar")||Files.isRegularFile(p.resolve("module-info.class"))).distinct().toArray(Path[]::new);
        return ModuleFinder.of(paths).findAll().stream().filter(r->names.contains(r.descriptor().name()))
                .map(r->Path.of(r.location().orElseThrow()).toString()).collect(java.util.stream.Collectors.joining(File.pathSeparator));
    }
    private void prepare() throws Exception {
        compile(List.of(new GeneratedPage("fixture.Tags", """
                package fixture;
                import jakarta.servlet.jsp.*; import jakarta.servlet.jsp.tagext.*;
                public class Tags {
                    public static class Echo extends SimpleTagSupport {
                        private String label; private int count=1;
                        public void setLabel(String value) { label=value; } public void setCount(int value) { count=value; }
                        public void setXmlnsLabel(String value) { label=value; }
                        public void doTag() throws JspException,java.io.IOException {
                            var out=getJspContext().getOut(); out.write("["+label+":");
                            if(getJspBody()!=null) for(int i=0;i<count;i++) getJspBody().invoke(null);
                            out.write("]");
                        }
                    }
                    public static class RuntimeText extends Echo {
                        public void doTag() throws JspException,java.io.IOException {setCount(2);super.doTag();}
                    }
                    public static class Spaced extends SimpleTagSupport {
                        public void doTag() throws java.io.IOException {getJspContext().getOut().write(" space ");}
                    }
                    public static class Fragment extends SimpleTagSupport {
                        private JspFragment piece; public void setPiece(JspFragment value){piece=value;}
                        public void doTag() throws JspException,java.io.IOException {
                            var target=new java.io.StringWriter();piece.invoke(target);getJspContext().getOut().write("<"+target+">");
                            piece.invoke(null);if(getJspBody()!=null)getJspBody().invoke(null);
                        }
                    }
                    public static class ClassicFragment extends TagSupport {
                        private JspFragment piece; public void setPiece(JspFragment value){piece=value;}
                        public int doStartTag() throws JspException {
                            try{var target=new java.io.StringWriter();piece.invoke(target);pageContext.getOut().write("<"+target+">");piece.invoke(null);}
                            catch(java.io.IOException e){throw new JspException(e);}return SKIP_BODY;
                        }
                    }
                    public static class FragmentParent extends SimpleTagSupport {
                        public void doTag() throws JspException,java.io.IOException {
                            if(!(getParent() instanceof Fragment))throw new JspException("wrong fragment parent");
                            getJspContext().getOut().write("P");
                        }
                    }
                    public static class Repeat extends TagSupport {
                        private int count; public void setCount(int value){count=value;}
                        public int doStartTag(){return count>0?EVAL_BODY_INCLUDE:SKIP_BODY;}
                        public int doAfterBody(){return --count>0?EVAL_BODY_AGAIN:SKIP_BODY;}
                        public void release(){try{pageContext.getOut().write("R");}catch(java.io.IOException e){throw new IllegalStateException(e);}super.release();}
                    }
                    public static class Buffer extends BodyTagSupport {
                        public int doAfterBody() throws JspException {
                            try{bodyContent.getEnclosingWriter().write("["+bodyContent.getString().toUpperCase(java.util.Locale.ROOT)+"]");}
                            catch(java.io.IOException e){throw new JspException(e);} return SKIP_BODY;
                        }
                    }
                    public static class Guard extends BodyTagSupport implements TryCatchFinally {
                        private void write(String value){Object trace=pageContext.getAttribute("trace",jakarta.servlet.jsp.PageContext.APPLICATION_SCOPE);pageContext.setAttribute("trace",(trace==null?"":trace.toString())+value,jakarta.servlet.jsp.PageContext.APPLICATION_SCOPE);try{pageContext.getOut().write(value);}catch(java.io.IOException e){throw new IllegalStateException(e);}}
                        public void doCatch(Throwable failure){write("C");} public void doFinally(){write("F");} public void release(){write("R");super.release();}
                    }
                    public static class SetterFailure extends Guard { public void setBodyContent(BodyContent body){throw new IllegalStateException("setter failure");} }
                    public static class NamedGuard extends Guard { public void setCount(int value){} }
                    public static class Fail extends SimpleTagSupport {public void doTag() throws JspException {throw new JspException("failure");}}
                    public static class Capture extends SimpleTagSupport {
                        public void doTag() throws JspException,java.io.IOException {var writer=new java.io.StringWriter();getJspBody().invoke(writer);getJspContext().getOut().write("<"+writer+">");}
                    }
                    public static class Parent extends TagSupport {
                        public int doStartTag() throws JspException {if(!(getParent() instanceof TagAdapter adapter)||!(adapter.getAdaptee() instanceof Capture))throw new JspException("wrong parent");
                            try{pageContext.getOut().write("P");}catch(java.io.IOException e){throw new JspException(e);}return SKIP_BODY;}
                    }
                    public static class Skip extends Repeat {public int doStartTag(){return SKIP_BODY;}public int doEndTag(){return SKIP_PAGE;}}
                    public static class SimpleSkip extends SimpleTagSupport {public void doTag() throws SkipPageException {throw new SkipPageException();}}
                    public static class OwnSkip extends TagSupport implements TryCatchFinally {
                        public int doEndTag(){return SKIP_PAGE;} public void doCatch(Throwable failure){throw new AssertionError("Own catch called after normal skip return");}
                        public void doFinally(){try{pageContext.getOut().write("F");}catch(java.io.IOException e){throw new IllegalStateException(e);}}
                        public void release(){try{pageContext.getOut().write("R");}catch(java.io.IOException e){throw new IllegalStateException(e);}super.release();}
                    }
                    public static class CountOnly extends SimpleTagSupport {
                        private int count; public void setCount(int value){count=value;} public void doTag() throws java.io.IOException {getJspContext().getOut().print(count);}
                    }
                    public static class CharacterValue extends SimpleTagSupport {
                        private char value; public void setValue(char character){value=character;}
                        public void doTag() throws java.io.IOException {getJspContext().getOut().print(value);}
                    }
                    public static class ObjectValue extends SimpleTagSupport {
                        private Object value; public void setValue(Object object){value=object;} public void doTag() throws java.io.IOException {getJspContext().getOut().print(value);}
                    }
                    public static class Overload extends SimpleTagSupport {
                        private String value; public void setValue(Object object){value="O"+object;} public void setValue(String text){value="S"+text;}
                        public void doTag() throws java.io.IOException {getJspContext().getOut().write(value);}
                    }
                    public static class Second extends Echo {public void doTag() throws java.io.IOException {getJspContext().getOut().write("SECOND");}}
                    public static class Dynamic extends SimpleTagSupport implements DynamicAttributes {
                        private final StringBuilder attributes=new StringBuilder();
                        public void setDynamicAttribute(String uri,String name,Object value){if(uri!=null)throw new AssertionError("Unexpected namespace");attributes.append(name).append("=").append(value).append(";");}
                        public void doTag() throws java.io.IOException {getJspContext().getOut().write(attributes.toString());}
                    }
                    public static class BufferedRepeat extends BodyTagSupport {
                        private int left=2; public void doInitBody() throws JspException {try{bodyContent.write("i");}catch(java.io.IOException e){throw new JspException(e);}}
                        public int doAfterBody() throws JspException {try{bodyContent.getEnclosingWriter().write("["+bodyContent.getString()+"]");}catch(java.io.IOException e){throw new JspException(e);}return --left>0?EVAL_BODY_AGAIN:SKIP_BODY;}
                    }
                    public static class Variables extends TagExtraInfo {
                        public VariableInfo[] getVariableInfo(TagData data) { return new VariableInfo[] {
                            new VariableInfo("begin", "java.lang.Integer", true, VariableInfo.AT_BEGIN),
                            new VariableInfo("nested", "java.lang.Integer", true, VariableInfo.NESTED),
                            new VariableInfo("end", "java.lang.Integer", true, VariableInfo.AT_END)
                        }; }
                    }
                    public static class SimpleSyncVariables extends SimpleTagSupport {
                        public void doTag() {
                            getJspContext().setAttribute("begin", Integer.valueOf(1));
                            getJspContext().setAttribute("end", Integer.valueOf(2));
                        }
                    }
                    public static class SyncVariables extends BodyTagSupport {
                        private int iterations;
                        public int doStartTag() { pageContext.setAttribute("begin", 1); return EVAL_BODY_BUFFERED; }
                        public void doInitBody() { pageContext.setAttribute("begin", 2); pageContext.setAttribute("nested", 2); }
                        public int doAfterBody() {
                            int value = ++iterations + 2;
                            pageContext.setAttribute("begin", value); pageContext.setAttribute("nested", value);
                            return iterations == 1 ? EVAL_BODY_AGAIN : SKIP_BODY;
                        }
                        public int doEndTag() throws JspException {
                            try { pageContext.getOut().write(bodyContent.getString()); }
                            catch (java.io.IOException failure) { throw new JspException(failure); }
                            pageContext.setAttribute("begin", 5); pageContext.setAttribute("end", 5); return EVAL_PAGE;
                        }
                    }
                }
                """, Map.of())));
    }
}
