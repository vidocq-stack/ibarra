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
import java.util.stream.Stream;
import io.vidocq.ibarra.compiler.internal.TagFileCompiler;
import io.vidocq.ibarra.compiler.internal.TldReferences;
import io.vidocq.ibarra.compiler.internal.TranslationUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
        var failure = assertThrows(PageParseException.class, generation("/raw.jsp",
                prefix()+"\n<x:raw>missing", options));
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
    void synchronizesTldDeclaredVariablesAfterSimpleTagExecution() throws Exception {
        assertEquals("1:2", render("<x:simpleTldSync/><% out.print(begin + \":\" + end); %>"));
    }

    @Test
    void validatesTagLibraryXmlViewOncePerLibraryDuringPageInitialization() throws Exception {
        var library = new TldParser().parse("/WEB-INF/pagedata.tld", """
                <taglib><uri>urn:test</uri>
                  <validator><validator-class>fixture.Tags$PageDataValidator</validator-class>
                    <init-param><param-name>expected</param-name><param-value>configured</param-value></init-param>
                  </validator>
                  <tag><name>check</name><tag-class>fixture.Tags$PageDataTag</tag-class><body-content>empty</body-content>
                    <attribute><name>test</name><required>true</required></attribute>
                    <attribute><name>runtime</name><type>java.lang.String</type><rtexprvalue>true</rtexprvalue></attribute>
                  </tag>
                </taglib>
                """.getBytes(StandardCharsets.UTF_8));
        var configured = options().withTagLibraries(Map.of("urn:test", library));
        String page = "<%@ page contentType=\"text/plain\" %>"
                + "<%! public String declared() { return \"declared\"; } %>"
                + "<% int scriptlet = 1; %>"
                + "<x:check test=\"PageData\" runtime='<%= \"dynamic\" %>'/>"
                + "<x:check test=\"PageData\"/><%= \"Expression Text\" %>template";
        assertEquals("1:PageData1:PageDataExpression Texttemplate1:PageData1:PageDataExpression Texttemplate",
                render(page, false, configured, 2));
    }

    @Test
    void doesNotGenerateAssignmentsForUndeclaredTldVariables() throws Exception {
        assertEquals("done", render("<x:simpleTldUndeclared/>done"));
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

    private GenerationOptions rawOptions() {
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
                library.validator(), library.functions())));
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
            var failure = assertThrows(PageParseException.class, generation("/bad.jsp",
                    prefix() + "\n<x:raw>" + content + "</x:raw>", options));
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
        var failure = assertThrows(PageParseException.class, generation("/scope.jsp", prefix()
                + "<x:raw><jsp:body><jsp:useBean id='bean' class='missing.Bean'/></jsp:body></x:raw>"
                + "<jsp:getProperty name='bean' property='value'/>", options));
        assertTrue(failure.getMessage().contains("bean"));
    }

    @Test
    void xmlOutputOmissionAcceptsItsSpecifiedStringAliasesWithoutChangingPageBooleans() throws Exception {
        String head = "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' version='4.0'><jsp:directive.page session='false'/>";
        for (String value : List.of("true", "yes", "false", "no"))
            assertEquals(value.equals("true") || value.equals("yes") ? "body" : "<?xml version=\"1.0\" encoding=\"UTF-8\"?>body",
                    render(head + "<jsp:output omit-xml-declaration='" + value + "'/><jsp:text>body</jsp:text></jsp:root>", false, options()));
        for (String value : List.of("YES", "False", "1", ""))
            assertThrows(PageParseException.class, generation("/output.jspx", head
                    + "<jsp:output omit-xml-declaration='" + value + "'/></jsp:root>", options()));
        assertThrows(PageParseException.class, generation("/boolean.jspx",
                "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' version='4.0'><jsp:directive.page session='yes'/></jsp:root>", options()));
    }

    @Test
    void xmlOutputRejectsNonemptyBodiesBeforeWritingADeclaration() {
        String head = "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' version='4.0'>";
        for (String body : List.of("Test FAILED", "${1+2}", "<jsp:text>payload</jsp:text>", "<b/>", " ", "\n")) {
            var failure = assertThrows(PageParseException.class, generation("/output-body.jspx",
                    head + "\n<jsp:output omit-xml-declaration='yes'>" + body + "</jsp:output></jsp:root>", options()));
            assertEquals(2, failure.location().line());
            assertTrue(failure.getMessage().contains("empty body"));
        }
        assertThrows(PageParseException.class, generation("/standard.jsp",
                "<jsp:output omit-xml-declaration='yes'/>", options()));
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
        var failure = assertThrows(PageParseException.class, generation("/qualified.jspx", head
                + "<jsp:output xmlns:foreign='urn:foreign' foreign:doctype-system='test.dtd'/><p/></jsp:root>", options()));
        assertTrue(failure.getMessage().contains("Unsupported action attribute"));
    }

    @Test
    void xmlDoctypeAttributesRequireRootAndSystemTogetherAndPublicRequiresSystem() {
        String head = "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' version='4.0'>\n";
        for (String attributes : List.of("doctype-root-element='p'", "doctype-system='test.dtd'",
                "doctype-public='public'", "doctype-root-element='p' doctype-public='public'")) {
            var failure = assertThrows(PageParseException.class, generation("/doctype.jspx", head
                    + "<jsp:output " + attributes + "/></jsp:root>", options()));
            assertEquals(2, failure.location().line());
            assertTrue(failure.getMessage().contains("requires"), failure.getMessage());
        }
        var split = assertThrows(PageParseException.class, generation("/split.jspx", head
                + "<jsp:output doctype-root-element='p'/><jsp:output doctype-system='test.dtd'/></jsp:root>", options()));
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
            var failure = assertThrows(PageParseException.class, generation("/doctype.jspx", head + original
                    + "<jsp:directive.include file='piece.jspx'/><p/></jsp:root>", conflicting));
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
        return new TagLibraryDescriptor(ids.resourcePath(), ids.uri(), ids.listenerClasses(), tags, ids.validator(), ids.functions());
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
            var failure = assertThrows(PageParseException.class, generation("/tags.jsp", prefix()+source, options));
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
        assertThrows(PageParseException.class, generation("/tags.jsp", prefix()+"<x:ambiguous value='x'/>",
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
            assertThrows(PageParseException.class, generation("/tags.jsp", prefix()+"<x:"+name+" value='text'/>", options));
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
            assertThrows(PageParseException.class, generation("/tags.jsp", prefix()+source, options()), source);
        }
    }
    @Test void jspStringConversionsAcceptEmptyNumbersAndCharacterPrefixes() throws Exception {
        assertEquals("0|0|A|A|0", render("<x:countOnly count=''/>|<x:runtimeCount><jsp:attribute name='count'/></x:runtimeCount>|<x:character value='ABC'/>|<x:character><jsp:attribute name='value'>ABC</jsp:attribute></x:character>|<x:character value=''/>").replace("\u0000", "0"));
    }
    @Test void staticXmlNamedAttributesRejectActionsAndActiveExpressions() throws Exception {
        prepare();
        for (String content : List.of("<x:spaced/>", "<b title='${1}'/>", "${1}")) {
            String xml = "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' xmlns:x='urn:test' version='4.0'><x:echo><jsp:attribute name='label'>" + content + "</jsp:attribute></x:echo></jsp:root>";
            assertThrows(PageParseException.class, generation("/tags.jspx", xml, options()), content);
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
        var second=new TagLibraryDescriptor("/two/lib.tld",null,List.of(),tags,null,Map.of());
        var libraries=new HashMap<>(defaults.tagLibraries()); libraries.put("/one/lib.tld",first); libraries.put("/two/lib.tld",second);
        var options=new GenerationOptions(List.of(),Map.of(
                "/one/bind.jspf","<%@ taglib prefix='q' uri='lib.tld' %><q:echo label='a'>body</q:echo>",
                "/two/bind.jspf","<%@ taglib prefix='q' uri='lib.tld' %><q:echo label='b'/>"),false,Map.of(),libraries);
        assertEquals("[a:body]SECOND",render("<%@ include file='one/bind.jspf' %><%@ include file='two/bind.jspf' %>",false,options));
    }
    @Test void dynamicAttributesUseDirectInterfaceCalls() throws Exception {
        assertEquals("a=one;b=42;",render("<x:dynamic a='one' b='<%=42%>'/>"));
    }
    @Test void exposesTldFunctionMetadataToTagExtraInfoValidation() throws Exception {
        assertEquals("1:upperCase:fixture.Functions:java.lang.String upperCase(java.lang.String):literal"
                + "1:upperCase:fixture.Functions:java.lang.String upperCase(java.lang.String):literal",
                render("<x:validate value='literal'/>", false, options(), 2));
    }
    @Test void exposesOrdinaryTldTagMetadataToTagExtraInfoValidation() throws Exception {
        String tld = """
                <taglib><uri>urn:test</uri>
                  <tag><description>Catalog tag information</description><display-name>Catalog tag</display-name>
                    <icon><small-icon>/16/catalog.jpg</small-icon><large-icon>/32/catalog.jpg</large-icon></icon>
                    <name>inspect</name><tag-class>fixture.Tags$TagFileMetadata</tag-class>
                    <tei-class>fixture.Tags$TagCatalogValidation</tei-class><body-content>empty</body-content></tag>
                  <tag><description>First tag information</description><display-name>First tag</display-name>
                    <icon><small-icon>/16/first.jpg</small-icon><large-icon>/32/first.jpg</large-icon></icon>
                    <name>first</name><tag-class>fixture.Tags$Echo</tag-class><tei-class>fixture.Tags$TagCatalogValidation</tei-class>
                    <body-content>scriptless</body-content>
                    <attribute><name>label</name><required>true</required></attribute>
                    <variable><name-given>result</name-given><variable-class>java.lang.Integer</variable-class>
                      <declare>false</declare><scope>AT_BEGIN</scope></variable><dynamic-attributes>true</dynamic-attributes></tag>
                  <tag><description>Second tag information</description><name>second</name>
                    <tag-class>fixture.Tags$Echo</tag-class><body-content>tagdependent</body-content></tag>
                </taglib>
                """;
        var library = new TldParser().parse("/WEB-INF/catalog.tld", tld.getBytes(StandardCharsets.UTF_8));
        var configured = new GenerationOptions(List.of(), Map.of(), false, Map.of(), Map.of("urn:test", library));

        assertEquals("ok", render("<x:inspect/>", false, configured));
    }
    @Test void exposesTagLibraryHeaderAndImportedLibrariesToTagExtraInfoValidation() throws Exception {
        String primaryTld = """
                <taglib version="3.0"><description>Primary library</description><tlib-version>1.0</tlib-version>
                  <short-name>primary</short-name><uri>urn:primary</uri>
                  <tag><name>inspect</name><tag-class>fixture.Tags$TagFileMetadata</tag-class>
                    <tei-class>fixture.Tags$TagLibraryMetadataValidation</tei-class><body-content>empty</body-content></tag>
                </taglib>
                """;
        String secondaryTld = """
                <taglib version="3.0"><description>Secondary library</description><tlib-version>1.0</tlib-version>
                  <short-name>secondary</short-name><uri>urn:secondary</uri>
                  <tag><name>other</name><tag-class>fixture.Tags$TagFileMetadata</tag-class><body-content>empty</body-content></tag>
                </taglib>
                """;
        var parser = new TldParser();
        var primary = parser.parse("/WEB-INF/primary.tld", primaryTld.getBytes(StandardCharsets.UTF_8));
        var secondary = parser.parse("/WEB-INF/secondary.tld", secondaryTld.getBytes(StandardCharsets.UTF_8));
        var configured = new GenerationOptions(List.of(), Map.of(), false, Map.of(),
                Map.of("urn:primary", primary, "urn:secondary", secondary));
        String page = "<%@ page session='false' %><%@ taglib prefix='a' uri='urn:primary' %>"
                + "<%@ taglib prefix='b' uri='urn:secondary' %><a:inspect/>";

        assertEquals("ok", render(page, false, configured));
    }
    @Test void tagFileLibraryValidationRunsOnceWithoutSynchronizedAndReportsItsFailureToEveryCaller() throws Exception {
        assertTagFileValidationRunsOnce("/WEB-INF/tags/sample.tag",
                "<%@ taglib prefix='v' uri='urn:validated' %><v:check/>body");
    }

    @Test void xmlTagFileLibrariesDeclaredOnlyThroughXmlnsAreValidated() throws Exception {
        assertTagFileValidationRunsOnce("/WEB-INF/tags/sample.tagx",
                "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' xmlns:v='urn:validated' version='2.0'>body</jsp:root>");
    }

    @Test void xmlPageLibrariesDeclaredOnlyThroughXmlnsAreValidatedAtInitialization() throws Exception {
        var recorded = new TldParser().parse("/WEB-INF/recorded.tld", """
                <taglib><uri>urn:recorded</uri>
                  <validator><validator-class>fixture.Tags$RecordingValidator</validator-class></validator>
                  <tag><name>check</name><tag-class>jakarta.servlet.jsp.tagext.SimpleTagSupport</tag-class><body-content>empty</body-content></tag>
                </taglib>
                """.getBytes(StandardCharsets.UTF_8));
        var options = options().withTagLibraries(Map.of("urn:recorded", recorded));
        assertEquals("r=urn:recorded;", render("<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' xmlns:r='urn:recorded'"
                + " version='4.0'><jsp:directive.page session='false'/>"
                + "<jsp:scriptlet>out.print(fixture.Tags.RecordingValidator.seen);</jsp:scriptlet></jsp:root>", false, options));
    }

    @Test void uncompiledTldTagFilesFailTranslationWithTheirPath() {
        var library = new TldParser().parse("/WEB-INF/missing.tld", """
                <taglib><uri>urn:missing</uri>
                  <tag-file><name>absent</name><path>/WEB-INF/tags/absent.tag</path></tag-file>
                </taglib>
                """.getBytes(StandardCharsets.UTF_8));
        var options = new GenerationOptions(List.of(), Map.of(), false, Map.of(), Map.of("urn:missing", library));
        String source = "<%@ taglib prefix='m' uri='urn:missing' %><m:absent/>";
        var page = new TranslationUnit(options).read("/missing.jsp", source);
        var compiled = TagFileCompiler.compile(List.of(page), options, "fixture.tagfiles", List.of());
        var libraries = new LinkedHashMap<>(options.tagLibraries()); libraries.putAll(compiled.libraries());
        var configured = options.withTagLibraries(libraries);
        var generator = generator();
        var failure = assertThrows(PageParseException.class,
                () -> generator.generate("/missing.jsp", source, "fixture.GeneratedPage", configured));
        assertTrue(failure.getMessage().contains("TLD tag file /WEB-INF/tags/absent.tag has no build-time compiled handler"),
                failure.getMessage());
    }

    @Test void tagFileValidationWrapsEachNonErrorFailureInAFreshJspExceptionWithTheSameCause() throws Exception {
        assertEquals("jakarta.servlet.jsp.JspException|true|true|java.lang.IllegalStateException:constructor failure|1",
                runTagFileValidationScenario("/WEB-INF/tags/sample.tag", "<%@ taglib prefix='v' uri='urn:validated' %>body",
                        "fixture.Tags$ConstructorFailingValidator", """
                        var first = invoke(servlet); var second = invoke(servlet);
                        result = first.getClass().getName() + "|" + (first != second) + "|" + (first.getCause() == second.getCause())
                                + "|" + first.getCause().getClass().getName() + ":" + first.getMessage()
                                + "|" + fixture.Tags.ConstructorFailingValidator.constructions.get();
                        """));
    }

    @Test void tagFileValidationErrorsAreNotCachedSoTheNextCallValidatesAgain() throws Exception {
        assertEquals("java.lang.StackOverflowError:first run|null|2",
                runTagFileValidationScenario("/WEB-INF/tags/sample.tag", "<%@ taglib prefix='v' uri='urn:validated' %>body",
                        "fixture.Tags$ErrorOnceValidator", """
                        var first = invoke(servlet); var second = invoke(servlet);
                        result = first.getClass().getName() + ":" + first.getMessage() + "|" + second
                                + "|" + fixture.Tags.ErrorOnceValidator.calls.get();
                        """));
    }

    @Test void interruptedTagFileValidationWaitersRestoreTheirFlagAndCacheNothing() throws Exception {
        assertEquals("jakarta.servlet.jsp.JspException:java.lang.InterruptedException|true|null|null|1",
                runTagFileValidationScenario("/WEB-INF/tags/sample.tag", "<%@ taglib prefix='v' uri='urn:validated' %>body",
                        "fixture.Tags$BlockingValidator", """
                        var runnerResult = new Throwable[1]; var waiterResult = new Throwable[1]; var waiterFlag = new boolean[1];
                        var runner = Thread.ofVirtual().start(() -> {
                            try { runnerResult[0] = invoke(servlet); } catch (Exception failure) { runnerResult[0] = failure; } });
                        fixture.Tags.BlockingValidator.entered.await();
                        var waiter = Thread.ofVirtual().start(() -> {
                            try { waiterResult[0] = invoke(servlet); } catch (Exception failure) { waiterResult[0] = failure; }
                            waiterFlag[0] = Thread.currentThread().isInterrupted(); });
                        long deadline = System.nanoTime() + 10_000_000_000L;
                        while (waiter.getState() != Thread.State.WAITING && System.nanoTime() < deadline) Thread.onSpinWait();
                        waiter.interrupt(); waiter.join();
                        fixture.Tags.BlockingValidator.release.countDown(); runner.join();
                        var later = invoke(servlet);
                        result = waiterResult[0].getClass().getName() + ":" + waiterResult[0].getCause().getClass().getName()
                                + "|" + waiterFlag[0] + "|" + runnerResult[0] + "|" + later
                                + "|" + fixture.Tags.BlockingValidator.calls.get();
                        """));
    }

    private void assertTagFileValidationRunsOnce(String tagPath, String tagSource) throws Exception {
        assertEquals("1|1|9|[jakarta.servlet.jsp.JspException]"
                + "|[TagLibraryValidator validation failed for urn:validated: rejected]|1"
                + "|[jakarta.servlet.jsp.JspException:TagLibraryValidator validation failed for urn:validated: rejected]",
                runTagFileValidationScenario(tagPath, tagSource, "fixture.Tags$RejectingValidator", """
                        var failures = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
                        var start = new java.util.concurrent.CountDownLatch(1);
                        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                            for (int i = 0; i < 8; i++) executor.submit(() -> { start.await(); failures.add(invoke(servlet)); return null; });
                            start.countDown();
                        }
                        int concurrentCalls = fixture.Tags.RejectingValidator.calls.get();
                        failures.add(invoke(servlet));
                        var causes = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
                        var types = new java.util.TreeSet<String>(); var messages = new java.util.TreeSet<String>(); var causeTypes = new java.util.TreeSet<String>();
                        for (var failure : failures) {
                            types.add(failure.getClass().getName()); messages.add(String.valueOf(failure.getMessage()));
                            causes.add(failure.getCause());
                            causeTypes.add(failure.getCause() == null ? "none" : failure.getCause().getClass().getName()
                                    + ":" + failure.getCause().getMessage());
                        }
                        result = concurrentCalls + "|" + fixture.Tags.RejectingValidator.calls.get() + "|" + failures.size()
                                + "|" + types + "|" + messages + "|" + causes.size() + "|" + causeTypes;
                        """));
    }

    /** Compiles a tag file whose library has the given validator, then runs {@code scenario} against its handler. */
    private String runTagFileValidationScenario(String tagPath, String tagSource, String validatorClass, String scenario)
            throws Exception {
        var validated = new TldParser().parse("/WEB-INF/validated.tld", """
                <taglib><uri>urn:validated</uri>
                  <validator><validator-class>VALIDATOR</validator-class></validator>
                  <tag><name>check</name><tag-class>jakarta.servlet.jsp.tagext.SimpleTagSupport</tag-class><body-content>empty</body-content></tag>
                </taglib>
                """.replace("VALIDATOR", validatorClass).getBytes(StandardCharsets.UTF_8));
        var options = new GenerationOptions(List.of(), Map.of(), false, Map.of(tagPath,
                tagSource.getBytes(StandardCharsets.UTF_8)), Map.of("urn:validated", validated));
        prepare();
        var page = new TranslationUnit(options).read("/index.jsp",
                "<%@ taglib prefix='t' tagdir='/WEB-INF/tags' %><t:sample/>");
        var paths = new ArrayList<Path>(); paths.add(directory);
        for (String path : modulePath().split(java.util.regex.Pattern.quote(File.pathSeparator))) paths.add(Path.of(path));
        var compiled = TagFileCompiler.compile(List.of(page), options, "fixture.tagfiles", paths);
        var tag = compiled.sources().stream().filter(source -> source.source().contains("_jspxValidateTagLibraries"))
                .findFirst().orElseThrow(() -> new AssertionError("No tag-file library validation generated: "
                        + compiled.sources().stream().map(GeneratedPage::source).toList()));
        assertFalse(tag.source().contains("synchronized"), tag.source());
        int start = tag.source().indexOf("_jspxTagLibraryValidationTask");
        int end = tag.source().indexOf("private static void _jspxRunTagLibraryValidators");
        assertTrue(start >= 0 && end > start, tag.source());
        String guard = tag.source().substring(start, end);
        assertFalse(guard.contains("->") || guard.contains("::"), guard);

        String main = """
                package io.vidocq.ibarra.core.internal;
                import jakarta.servlet.*; import jakarta.servlet.jsp.*;
                public class TagFileValidationMain {
                    public static void main(String[] args) throws Throwable {
                        JspFactory.setDefaultFactory(new IbarraJspFactory());
                        var context = new ServletDoubles.Context();
                        var servlet = new jakarta.servlet.http.HttpServlet() { };
                        servlet.init(new ServletConfig() {
                            public String getServletName() { return "tags"; }
                            public ServletContext getServletContext() { return context; }
                            public String getInitParameter(String name) { return null; }
                            public java.util.Enumeration<String> getInitParameterNames() { return java.util.Collections.emptyEnumeration(); }
                        });
                        String result;
                        SCENARIO
                        System.out.print("RESULT=" + result);
                    }
                    /** Returns the handler's failure, or null when doTag succeeds. */
                    private static Throwable invoke(Servlet servlet) throws Exception {
                        var pageContext = JspFactory.getDefaultFactory().getPageContext(servlet, new ServletDoubles.Request(),
                                new ServletDoubles.Response(), null, false, 8192, true);
                        var handler = new TAG_CLASS();
                        handler.setJspContext(pageContext);
                        try { handler.doTag(); return null; }
                        catch (JspException | RuntimeException | Error failure) { return failure; }
                    }
                }
                """.replace("TAG_CLASS", tag.className()).replace("SCENARIO", scenario);
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath(); if (!Files.isDirectory(root.resolve("ibarra-core"))) root = root.getParent();
        String doubles = Files.readString(root.resolve("ibarra-core/src/test/java/io/vidocq/ibarra/core/internal/ServletDoubles.java"));
        var sources = new ArrayList<>(compiled.sources());
        sources.add(new GeneratedPage("io.vidocq.ibarra.core.internal.TagFileValidationMain", main, Map.of()));
        sources.add(new GeneratedPage("io.vidocq.ibarra.core.internal.ServletDoubles", doubles, Map.of()));
        compile(sources);
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "--limit-modules", "io.vidocq.ibarra.core,io.vidocq.peano.core", "--add-modules", "io.vidocq.peano.core",
                "--add-reads", "io.vidocq.ibarra.core=io.vidocq.peano.api", "--module-path", modulePath(),
                "--patch-module", "io.vidocq.ibarra.core=" + directory,
                "-m", "io.vidocq.ibarra.core/io.vidocq.ibarra.core.internal.TagFileValidationMain")
                .redirectErrorStream(true).redirectOutput(directory.resolve("tag-file-validation.log").toFile()).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        String output = Files.readString(directory.resolve("tag-file-validation.log"));
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.startsWith("RESULT="), output);
        return output.substring(7);
    }

    @Test void exposesTldTagFileMetadataToTagExtraInfoValidation() throws Exception {
        String tld = """
                <taglib><uri>urn:test</uri>
                  <tag><name>inspect</name><tag-class>fixture.Tags$TagFileMetadata</tag-class>
                    <tei-class>fixture.Tags$TagFileValidation</tei-class><body-content>empty</body-content></tag>
                  <tag-file><name>TagFile1</name><path>/WEB-INF/tags/TagFile1.tag</path></tag-file>
                </taglib>
                """;
        String tagFile = """
                <%@ tag display-name="TagFile1" dynamic-attributes="true" small-icon="/16/icon.jpg"
                    large-icon="/32/icon.jpg" description="Simple Tag File" %>
                """;
        var library = new TldParser().parse("/WEB-INF/tags.tld", tld.getBytes(StandardCharsets.UTF_8));
        var resources = Map.of("/WEB-INF/tags/TagFile1.tag", tagFile.getBytes(StandardCharsets.UTF_8));
        var configured = new GenerationOptions(List.of(), Map.of(), false, resources, Map.of("urn:test", library));

        assertEquals("ok", render("<x:inspect/>", false, configured));
    }

    @Test void compilesDeferredTagFileAttributesWithTheirDeferredContracts() {
        String tagFile = """
                <%@ attribute name="typedValue" deferredValueType="java.lang.String" %>
                <%@ attribute name="defaultValue" deferredValue="true" %>
                <%@ attribute name="typedMethod" deferredMethodSignature="boolean add(java.lang.Object)" %>
                <%@ attribute name="defaultMethod" deferredMethod="true" %>
                """;

        var compiled = compileTagFile(tagFile);
        var tag = compiled.libraries().get("urn:jsptagdir:/WEB-INF/tags").tags().get("sample");
        assertFalse(tag.unsupportedMetadata(), tag.unsupportedReason());
        assertEquals("jakarta.el.ValueExpression", tag.attributes().get("typedValue").type());
        assertEquals("java.lang.String", tag.attributes().get("typedValue").deferredValueType());
        assertEquals("jakarta.el.ValueExpression", tag.attributes().get("defaultValue").type());
        assertEquals("java.lang.Object", tag.attributes().get("defaultValue").deferredValueType());
        assertEquals("jakarta.el.MethodExpression", tag.attributes().get("typedMethod").type());
        assertEquals("boolean", tag.attributes().get("typedMethod").deferredMethod().returnType());
        assertEquals(List.of("java.lang.Object"), tag.attributes().get("typedMethod").deferredMethod().parameters());
        assertEquals("jakarta.el.MethodExpression", tag.attributes().get("defaultMethod").type());
        assertEquals("void", tag.attributes().get("defaultMethod").deferredMethod().returnType());
        assertTrue(compiled.sources().getFirst().source().contains("setTypedValue(jakarta.el.ValueExpression value)"));
        assertTrue(compiled.sources().getFirst().source().contains("setTypedMethod(jakarta.el.MethodExpression value)"));
    }

    @Test void standardTagFileEvaluatesImmediateElInTemplateText() {
        var compiled = compileTagFile("<%@ attribute name='x' %>value=${x}");
        var tag = compiled.libraries().get("urn:jsptagdir:/WEB-INF/tags").tags().get("sample");

        assertFalse(tag.unsupportedMetadata(), tag.unsupportedReason());
        String generated = compiled.sources().getFirst().source();
        assertTrue(generated.contains("createValueExpression(pageContext.getELContext(), \"${x}\", Object.class)"), generated);
        assertTrue(generated.contains("if (_jspxTagFileEl0 != null) out.write(String.valueOf(_jspxTagFileEl0));"), generated);
    }

    @Test void passesErrorOnElNotFoundToTheTagFileContext() {
        var compiled = compileTagFile("<%@ tag errorOnELNotFound='true' %>${missing}");
        var tag = compiled.libraries().get("urn:jsptagdir:/WEB-INF/tags").tags().get("sample");

        assertFalse(tag.unsupportedMetadata(), tag.unsupportedReason());
        assertTrue(compiled.sources().getFirst().source().contains("new TagFileJspContext(page, true)"),
                compiled.sources().getFirst().source());
    }

    @Test void tagDirectiveAllowsDeferredSyntaxAsLiteralInNestedTagAttributes() {
        var options = new GenerationOptions(List.of(), Map.of(), false, Map.of(
                "/WEB-INF/tags/outer.tag", ("<%@ tag deferredSyntaxAllowedAsLiteral='true' %>"
                        + "<%@ taglib prefix='nested' tagdir='/WEB-INF/tags/nested' %>"
                        + "<nested:echo literal=\"#{expr}\"/>\n").getBytes(StandardCharsets.UTF_8),
                "/WEB-INF/tags/nested/echo.tag", "<%@ attribute name='literal' %><% out.print(literal); %>"
                        .getBytes(StandardCharsets.UTF_8),
                "/WEB-INF/tags/implicit.tld", "<taglib><jsp-version>3.0</jsp-version></taglib>"
                        .getBytes(StandardCharsets.UTF_8)), Map.of());
        var page = new TranslationUnit(options).read("/index.jsp",
                "<%@ taglib prefix='t' tagdir='/WEB-INF/tags' %><t:outer/>");

        var compiled = TagFileCompiler.compile(List.of(page), options, "fixture.tagfiles", List.of());
        var outer = compiled.libraries().get("urn:jsptagdir:/WEB-INF/tags").tags().get("outer");

        assertFalse(outer.unsupportedMetadata(), outer.unsupportedReason());
        assertTrue(compiled.sources().stream().anyMatch(source -> source.source()
                .contains("setLiteral((java.lang.String) (\"#{expr}\"));")), compiled.sources().toString());
    }

    @Test void deferredValueTagFileAttributesAreExposedThroughTheVariableMapper() {
        var compiled = compileTagFile("<%@ attribute name='x' deferredValue='true' %>");
        String generated = compiled.sources().getFirst().source();

        assertTrue(generated.contains("mapper.setVariable(\"x\", value)"), generated);
        assertTrue(generated.contains("_jspxTagFileELContext.getVariableMapper().setVariable(\"x\", _jspxPreviousVariable_x)"), generated);
    }

    @Test void acceptsRepeatedImportsAndIdenticalAttributesWithinOneTagDirective() {
        var compiled = compileTagFile("<%@ tag import='java.util.List' import='java.util.Map' display-name='Sample' "
                + "display-name='Sample' %>");
        var tag = compiled.libraries().get("urn:jsptagdir:/WEB-INF/tags").tags().get("sample");

        assertFalse(tag.unsupportedMetadata(), tag.unsupportedReason());
        String generated = compiled.sources().getFirst().source();
        assertTrue(generated.contains("import java.util.List;"), generated);
        assertTrue(generated.contains("import java.util.Map;"), generated);
    }

    @ParameterizedTest
    @MethodSource("conflictingTagFileDirectives")
    void rejectsConflictingOrInvalidTagFileDirectives(String tagFile, String reason) {
        var tag = compileTagFile(tagFile).libraries().get("urn:jsptagdir:/WEB-INF/tags").tags().get("sample");

        assertTrue(tag.unsupportedMetadata());
        assertTrue(tag.unsupportedReason().contains(reason), tag.unsupportedReason());
    }

    static Stream<Arguments> conflictingTagFileDirectives() {
        return Stream.of(
                Arguments.of("<%@ tag example='a test, too' %><%@ tag example='a test' %>", "Conflicting"),
                Arguments.of("<%@ attribute name=\"value\" type=\"double\" %>", "Invalid tag-file attribute type"),
                Arguments.of("<%@ variable name-given=\"value\" alias=\"other\" %>", "alias"),
                Arguments.of("<%@ attribute name=\"value\" %><%@ variable name-given=\"value\" %>", "conflict"));
    }

    @Test void ignoresNamespacedDynamicAttributesInTagFileDynamicAttributeMap() {
        var compiled = compileTagFile("<%@ tag dynamic-attributes='attrs' %>");
        String generated = compiled.sources().getFirst().source();

        assertTrue(generated.contains("if (uri == null || uri.isEmpty()) attrs.put(localName, value);"), generated);
    }

    @Test void makesVariablesExportedByNestedTagFilesAvailableToTheContainingTagFile() {
        var options = new GenerationOptions(List.of(), Map.of(), false, Map.of(
                "/WEB-INF/tags/outer.tag", ("<%@ taglib prefix='nested' tagdir='/WEB-INF/tags/nested' %>"
                        + "<nested:inner/><% out.print(declared); %>").getBytes(StandardCharsets.UTF_8),
                "/WEB-INF/tags/nested/inner.tag", ("<%@ variable name-given='declared' scope='AT_END' %>"
                        + "<% jspContext.setAttribute(\"declared\", \"ready\"); %>").getBytes(StandardCharsets.UTF_8)), Map.of());
        var page = new TranslationUnit(options).read("/index.jsp",
                "<%@ taglib prefix='outer' tagdir='/WEB-INF/tags' %><outer:outer/>");

        var compiled = TagFileCompiler.compile(List.of(page), options, "fixture.tagfiles", List.of());
        var outer = compiled.sources().stream().filter(source -> source.source().contains("out.print(declared)")).findFirst().orElseThrow();

        assertTrue(outer.source().contains("java.lang.String declared"), outer.source());
        assertTrue(outer.source().contains("declared = (java.lang.String) jspContext.getAttribute(\"declared\")"), outer.source());
    }

    @Test void nestedTagFileVariablesAreCopiedFromTheChildContext() throws Exception {
        var options = new GenerationOptions(List.of(), Map.of(), false, Map.of(
                "/WEB-INF/tags/outer.tag", ("<%@ taglib prefix='nested' tagdir='/WEB-INF/tags/nested' %>"
                        + "<nested:inner/><% out.print(\"declared2:\" + declared2); %>").getBytes(StandardCharsets.UTF_8),
                "/WEB-INF/tags/nested/inner.tag", ("<%@ variable name-given='declared2' scope='AT_END' %>"
                        + "<% jspContext.setAttribute(\"declared2\", \"Test PASSED\"); %>").getBytes(StandardCharsets.UTF_8)), Map.of());

        assertEquals("declared2:Test PASSED", render(
                "<%@ taglib prefix='t' tagdir='/WEB-INF/tags' %><t:outer/>", false, options));
    }

    @Test void nestedTagFileUndeclaredVariablesUpdateTheExistingParentVariable() throws Exception {
        var options = new GenerationOptions(List.of(), Map.of(), false, Map.of(
                "/WEB-INF/tags/outer.tag", ("<%@ taglib prefix='nested' tagdir='/WEB-INF/tags/nested' %>"
                        + "<%! String declared2; %><nested:inner/><% out.print(declared2); %>")
                                .getBytes(StandardCharsets.UTF_8),
                "/WEB-INF/tags/nested/inner.tag", ("<%@ variable name-given='declared2' declare='false' scope='AT_END' %>"
                        + "<% jspContext.setAttribute(\"declared2\", \"was declared2\"); %>")
                                .getBytes(StandardCharsets.UTF_8)), Map.of());

        assertEquals("was declared2", render(
                "<%@ taglib prefix='t' tagdir='/WEB-INF/tags' %><t:outer/>", false, options));
    }

    @Test void compilesTagDirectoriesReachedTransitivelyFromTldDeclaredTagFiles() {
        String tld = "<taglib><uri>urn:outer</uri><tag-file><name>outer</name>"
                + "<path>/WEB-INF/tags/outer.tag</path></tag-file></taglib>";
        var library = new TldParser().parse("/WEB-INF/outer.tld", tld.getBytes(StandardCharsets.UTF_8));
        var options = new GenerationOptions(List.of(), Map.of(), false, Map.of(
                "/WEB-INF/tags/outer.tag", ("<%@ taglib prefix='nested' tagdir='/WEB-INF/tags/nested' %>"
                        + "<nested:inner x='ok'/>").getBytes(StandardCharsets.UTF_8),
                "/WEB-INF/tags/nested/inner.tag", "<%@ attribute name='x' required='true' %>${x}"
                        .getBytes(StandardCharsets.UTF_8)), Map.of("urn:outer", library));
        var page = new TranslationUnit(options).read("/index.jsp",
                "<%@ taglib prefix='o' uri='urn:outer' %><o:outer/>");

        var compiled = TagFileCompiler.compile(List.of(page), options, "fixture.tagfiles", List.of());

        assertEquals(2, compiled.sources().size(), compiled.sources().toString());
        assertTrue(compiled.sources().stream().anyMatch(source -> source.source().contains("setX(")));
    }

    @Test void invokesTheCallingBodyFromAStandardTagFile() {
        var compiled = compileTagFile("<jsp:doBody/>");
        var tag = compiled.libraries().get("urn:jsptagdir:/WEB-INF/tags").tags().get("sample");

        assertFalse(tag.unsupportedMetadata(), tag.unsupportedReason());
        assertTrue(compiled.sources().getFirst().source().contains("getJspBody().invoke(null);"));
    }

    @Test void capturesDoBodyOutputIntoConfiguredVariableAndReaderScopes() {
        var compiled = compileTagFile("<jsp:doBody var='content' scope='request'/><jsp:doBody varReader='reader'/>");
        String generated = compiled.sources().getFirst().source();

        assertTrue(generated.contains("pageContext.setAttribute(\"content\", _jspxDoBodyContent0, PageContext.REQUEST_SCOPE)"), generated);
        assertTrue(generated.contains("new java.io.StringReader(_jspxDoBodyContent1)"), generated);
    }

    @Test void invokesFragmentAttributesAndSupportsVarReaderCapture() {
        var compiled = compileTagFile("<%@ attribute name='content' fragment='true' required='true' %>"
                + "<jsp:invoke fragment='content' varReader='reader' scope='request'/>");
        var tag = compiled.libraries().get("urn:jsptagdir:/WEB-INF/tags").tags().get("sample");
        assertFalse(tag.unsupportedMetadata(), tag.unsupportedReason());
        String generated = compiled.sources().getFirst().source();
        assertEquals("jakarta.servlet.jsp.tagext.JspFragment", tag.attributes().get("content").type());
        assertTrue(generated.contains("content.invoke(_jspxInvokeWriter0)"), generated);
        assertTrue(generated.contains("new java.io.StringReader(_jspxInvokeContent0)"), generated);
    }

    @Test void supportsDynamicJspElementsAndTheirAttributeAndBodyEnvelopesInTagFiles() {
        var compiled = compileTagFile("<jsp:element name=\"${'element1'}\"><jsp:attribute name='id'>value</jsp:attribute>"
                + "<jsp:body>body1</jsp:body></jsp:element>");
        var tag = compiled.libraries().get("urn:jsptagdir:/WEB-INF/tags").tags().get("sample");
        String generated = compiled.sources().getFirst().source();

        assertFalse(tag.unsupportedMetadata(), tag.unsupportedReason());
        assertTrue(generated.contains("createValueExpression(pageContext.getELContext(), \"${'element1'}\", Object.class)"), generated);
        assertTrue(generated.contains("out.write(\" id=\\\"\")"), generated);
        assertTrue(generated.contains("out.write(\"body1\")"), generated);
    }

    @Test void dynamicJspElementWithOnlyNamedAttributesHasNoBody() {
        var compiled = compileTagFile("<jsp:element name='element1'><jsp:attribute name='attr1'>value1</jsp:attribute></jsp:element>");
        var tag = compiled.libraries().get("urn:jsptagdir:/WEB-INF/tags").tags().get("sample");
        assertFalse(tag.unsupportedMetadata(), tag.unsupportedReason());
        String generated = compiled.sources().getFirst().source();

        assertTrue(generated.contains("out.write(\" attr1=\\\"\")"), generated);
        assertTrue(generated.contains("out.write(\">\")"), generated);
        assertFalse(generated.contains("jsp:attribute"), generated);
    }

    @Test void supportsJspTextInStandardTagFiles() {
        var compiled = compileTagFile("<jsp:text>inside text</jsp:text>");
        var tag = compiled.libraries().get("urn:jsptagdir:/WEB-INF/tags").tags().get("sample");

        assertFalse(tag.unsupportedMetadata(), tag.unsupportedReason());
        assertTrue(compiled.sources().getFirst().source().contains("out.write(\"inside text\");"));
    }

    @Test void unwrapsJspRootAndBodyInStandardTagFiles() {
        var compiled = compileTagFile("<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' version='2.0'>"
                + "<jsp:body>inside root</jsp:body></jsp:root>");
        var tag = compiled.libraries().get("urn:jsptagdir:/WEB-INF/tags").tags().get("sample");

        assertFalse(tag.unsupportedMetadata(), tag.unsupportedReason());
        String generated = compiled.sources().getFirst().source();
        assertTrue(generated.contains("out.write(\"inside root\");"), generated);
        assertFalse(generated.contains("jsp:root"), generated);
    }

    @Test void rejectsInvalidJspRootVersionInStandardTagFiles() {
        var compiled = compileTagFile("<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' version='2.00'>invalid</jsp:root>");
        var tag = compiled.libraries().get("urn:jsptagdir:/WEB-INF/tags").tags().get("sample");

        assertTrue(tag.unsupportedMetadata(), tag.unsupportedReason());
        assertTrue(tag.unsupportedReason().contains("Invalid jsp:root version"), tag.unsupportedReason());
    }

    @Test void compilesXmlSyntaxInStandardTagFiles() throws Exception {
        String tagFile = "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' version='2.0'>"
                + "<jsp:body><status>Test PASSED</status></jsp:body></jsp:root>";

        var output = render("<%@ taglib prefix='t' tagdir='/WEB-INF/tags' %><t:sample/>", false,
                tagFileOptions(tagFile));

        assertTrue(output.contains("<status>Test PASSED</status>"), output);
    }

    @Test void compilesScriptingAndDoBodyInXmlTagFiles() throws Exception {
        String xml = """
                <?xml version="1.0"?>
                <jsp:root xmlns:jsp="http://java.sun.com/JSP/Page" version="2.0">
                  <jsp:directive.tag import="java.io.IOException" />
                  <jsp:declaration><![CDATA[public void status(JspWriter out) throws IOException { out.print("Test PASSED"); }]]></jsp:declaration>
                  <jsp:scriptlet><![CDATA[status(out);]]></jsp:scriptlet>
                  <jsp:expression>" expression"</jsp:expression>
                </jsp:root>
                """;
        var output = render("<%@ taglib prefix='t' tagdir='/WEB-INF/tags' %><t:sample/>", false,
                tagxOptions(xml));

        assertTrue(output.contains("Test PASSED expression"), output);
    }

    @Test void compilesScriptingActionElementsInStandardPages() throws Exception {
        String page = "<%@ page contentType='text/plain' import='java.io.IOException' %>"
                + "<jsp:declaration>public void status(JspWriter out) throws IOException { out.print(\"Test PASSED|\"); }</jsp:declaration>"
                + "<jsp:scriptlet>status(out);</jsp:scriptlet>"
                + "<jsp:expression>\"Test PASSED|\"</jsp:expression>"
                + "<% out.print(\"Test PASSED\"); %>";

        assertEquals("Test PASSED|Test PASSED|Test PASSED", render(page));
    }

    @Test void compilesScriptingActionElementsInStandardTagFiles() throws Exception {
        String tagFile = "<%@ tag import='java.io.IOException' %>"
                + "<jsp:declaration>public void status(JspWriter out) throws IOException { out.print(\"Test PASSED\"); }</jsp:declaration>"
                + "<jsp:scriptlet>status(out);</jsp:scriptlet>";

        assertEquals("Test PASSED", render("<%@ taglib prefix='t' tagdir='/WEB-INF/tags' %><t:sample/>", false,
                tagFileOptions(tagFile)));
    }

    @Test void invokesTheCallerBodyFromXmlTagFiles() throws Exception {
        String xml = "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' version='2.0'><jsp:doBody/></jsp:root>";
        var output = render("<%@ taglib prefix='t' tagdir='/WEB-INF/tags' %><t:sample>caller body</t:sample>", false,
                tagxOptions(xml));

        assertTrue(output.contains("caller body"), output);
    }

    @Test void unwrapsJspRootAndJspBodyWhenCompilingXmlTagFiles() {
        String xml = "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' version='2.0'>"
                + "<jsp:body><status>Test PASSED</status></jsp:body></jsp:root>";
        var options = new GenerationOptions(List.of(), Map.of(), false,
                Map.of("/WEB-INF/tags/sample.tagx", xml.getBytes(StandardCharsets.UTF_8)), Map.of());
        var page = new TranslationUnit(options).read("/index.jsp",
                "<%@ taglib prefix='t' tagdir='/WEB-INF/tags' %><t:sample/>");

        var compiled = TagFileCompiler.compile(List.of(page), options, "fixture.tagfiles", List.of());

        assertEquals(1, compiled.sources().size());
        String generated = compiled.sources().getFirst().source();
        assertTrue(generated.contains("out.write(\"<status>\");"), generated);
        assertTrue(generated.contains("out.write(\"Test PASSED\");"), generated);
        assertFalse(generated.contains("jsp:root"), generated);
    }

    @Test void preservesNonJspDocumentRootWhenCompilingXmlTagFiles() throws Exception {
        String xml = "<subroot xmlns:jsp='http://java.sun.com/JSP/Page'>"
                + "<jsp:expression>\"Test PASSED\"</jsp:expression></subroot>";
        var options = new GenerationOptions(List.of(), Map.of(), false,
                Map.of("/WEB-INF/tags/sample.tagx", xml.getBytes(StandardCharsets.UTF_8)), Map.of());
        assertEquals("<subroot>Test PASSED</subroot>", render(
                "<%@ taglib prefix='t' tagdir='/WEB-INF/tags' %><t:sample/>", false, options));
    }

    @Test void rejectsInvalidJspRootVersionInXmlTagFiles() {
        String xml = "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' version='2.00'><status>invalid</status></jsp:root>";
        var options = new GenerationOptions(List.of(), Map.of(), false,
                Map.of("/WEB-INF/tags/sample.tagx", xml.getBytes(StandardCharsets.UTF_8)), Map.of());
        var page = new TranslationUnit(options).read("/index.jsp",
                "<%@ taglib prefix='t' tagdir='/WEB-INF/tags' %><t:sample/>");

        var compiled = TagFileCompiler.compile(List.of(page), options, "fixture.tagfiles", List.of());
        var tag = compiled.libraries().get("urn:jsptagdir:/WEB-INF/tags").tags().get("sample");

        assertTrue(tag.unsupportedMetadata(), tag.unsupportedReason());
        assertTrue(tag.unsupportedReason().contains("Invalid jsp:root version"), tag.unsupportedReason());
    }

    @Test void acceptsRepeatedTagFileImportsLanguageAndIdenticalMetadata() {
        String tagFile = """
                <%@ tag import="java.util.List" language="java" body-content="scriptless"
                    description="Sample tag" display-name="Sample" example="example" %>
                <%@ tag import="java.util.Map" language="java" body-content="scriptless"
                    description="Sample tag" display-name="Sample" %>
                """;

        var compiled = compileTagFile(tagFile);
        var tag = compiled.libraries().get("urn:jsptagdir:/WEB-INF/tags").tags().get("sample");
        assertFalse(tag.unsupportedMetadata(), tag.unsupportedReason());
        assertEquals("scriptless", tag.bodyContent());
        String generated = compiled.sources().getFirst().source();
        assertTrue(generated.contains("import java.util.List;"));
        assertTrue(generated.contains("import java.util.Map;"));
        var options = tagFileOptions(tagFile);
        var metadata = TagFileCompiler.tagInfoMetadata(new TranslationUnit(options)
                .read("/WEB-INF/tags/sample.tag", tagFile));
        assertEquals("Sample tag", metadata.infoString());
        assertEquals("Sample", metadata.displayName());
    }

    @Test void tagFilesReceiveTheImplicitServletImports() {
        var compiled = compileTagFile("<%@ tag language=\"java\" %><% Cookie cookie = new Cookie(\"name\", \"value\"); "
                + "ServletException failure = new ServletException(); %>");
        String generated = compiled.sources().getFirst().source();
        assertTrue(generated.contains("import jakarta.servlet.*;"));
        assertTrue(generated.contains("import jakarta.servlet.http.*;"));
    }

    @Test void compilesTagDirectoriesReferencedByAnotherTagFile() {
        String outer = "<%@ taglib prefix=\"nested\" tagdir=\"/WEB-INF/tags/nested\" %><nested:inner/>";
        String inner = "inner";
        var options = new GenerationOptions(List.of(), Map.of(), false, Map.of(
                "/WEB-INF/tags/outer.tag", outer.getBytes(StandardCharsets.UTF_8),
                "/WEB-INF/tags/nested/inner.tag", inner.getBytes(StandardCharsets.UTF_8)), Map.of());
        var page = new TranslationUnit(options).read("/index.jsp",
                "<%@ taglib prefix=\"root\" tagdir=\"/WEB-INF/tags\" %><root:outer/>");

        var compiled = TagFileCompiler.compile(List.of(page), options, "fixture.tagfiles", List.of());

        var rootTag = compiled.libraries().get("urn:jsptagdir:/WEB-INF/tags").tags().get("outer");
        assertFalse(rootTag.unsupportedMetadata(), rootTag.unsupportedReason());
        assertTrue(compiled.libraries().containsKey("urn:jsptagdir:/WEB-INF/tags/nested"));
        assertEquals(2, compiled.sources().size());
    }

    @Test void honorsImplicitTldMetadataForATagDirectory() {
        String implicitTld = """
                <taglib version="2.1"><description>Tag directory</description>
                  <tlib-version>1.0</tlib-version><short-name>implicit</short-name>
                </taglib>
                """;
        var options = new GenerationOptions(List.of(), Map.of(), false, Map.of(
                "/WEB-INF/tags/sample.tag", new byte[0],
                "/WEB-INF/tags/implicit.tld", implicitTld.getBytes(StandardCharsets.UTF_8)), Map.of());
        var page = new TranslationUnit(options).read("/index.jsp",
                "<%@ taglib prefix=\"t\" tagdir=\"/WEB-INF/tags\" %><t:sample/>");

        var library = TagFileCompiler.compile(List.of(page), options, "fixture.tagfiles", List.of())
                .libraries().get("urn:jsptagdir:/WEB-INF/tags");

        assertEquals("2.1", library.requiredVersion());
        assertEquals("1.0", library.tlibVersion());
        assertEquals("implicit", library.shortName());
        assertEquals("Tag directory", library.infoString());
    }

    @Test void rejectsImplicitTldBelowMinimumJspVersion() {
        var options = tagDirectoryOptions("<taglib version='1.2'><tlib-version>1.0</tlib-version></taglib>");
        var page = new TranslationUnit(options).read("/index.jsp",
                "<%@ taglib prefix='t' tagdir='/WEB-INF/tags' %><t:sample/>");

        var pages = List.of(page);
        List<Path> modulePath = List.of();
        var failure = assertThrows(PageParseException.class,
                () -> TagFileCompiler.compile(pages, options, "fixture.tagfiles", modulePath));

        assertTrue(failure.getMessage().contains("implicit.tld"));
    }

    @Test void rejectsTagDeclarationsInImplicitTld() {
        var options = tagDirectoryOptions("""
                <taglib version="2.1"><tlib-version>1.0</tlib-version>
                  <tag><name>extra</name><tag-class>fixture.ExtraTag</tag-class><body-content>empty</body-content></tag>
                </taglib>
                """);
        var page = new TranslationUnit(options).read("/index.jsp",
                "<%@ taglib prefix='t' tagdir='/WEB-INF/tags' %><t:sample/>");

        var pages = List.of(page);
        List<Path> modulePath = List.of();
        var failure = assertThrows(PageParseException.class,
                () -> TagFileCompiler.compile(pages, options, "fixture.tagfiles", modulePath));

        assertTrue(failure.getMessage().contains("implicit.tld"));
    }

    @Test void usesImplicitTldDefaultJspVersionWhenParsingTagFileDeferredSyntax() {
        var options = new GenerationOptions(List.of(), Map.of(), false, Map.of(
                "/WEB-INF/tags/sample.tag", "#{literal}".getBytes(StandardCharsets.UTF_8)), Map.of());
        var page = new TranslationUnit(options).read("/index.jsp",
                "<%@ taglib prefix='t' tagdir='/WEB-INF/tags' %><t:sample/>");

        var compiled = TagFileCompiler.compile(List.of(page), options, "fixture.tagfiles", List.of());

        assertEquals(1, compiled.sources().size(),
                compiled.libraries().get("urn:jsptagdir:/WEB-INF/tags").tags().get("sample").unsupportedReason());
        assertTrue(compiled.sources().getFirst().source().contains("out.write(\"#{literal}\");"));
    }

    @Test void honorsImplicitTldJspVersionWhenParsingTagFileDeferredSyntax() {
        var options = new GenerationOptions(List.of(), Map.of(), false, Map.of(
                "/WEB-INF/tags/sample.tag", "#{expression}".getBytes(StandardCharsets.UTF_8),
                "/WEB-INF/tags/implicit.tld", "<taglib><jsp-version>2.1</jsp-version></taglib>"
                        .getBytes(StandardCharsets.UTF_8)), Map.of());
        var page = new TranslationUnit(options).read("/index.jsp",
                "<%@ taglib prefix='t' tagdir='/WEB-INF/tags' %><t:sample/>");

        var compiled = TagFileCompiler.compile(List.of(page), options, "fixture.tagfiles", List.of());

        var tag = compiled.libraries().get("urn:jsptagdir:/WEB-INF/tags").tags().get("sample");
        assertTrue(tag.unsupportedReason().contains("EL in standard tag files is not supported yet"));
    }

    @Test void allowsDeferredSyntaxAsLiteralInNestedTagFileAttributesForJsp20() {
        var options = new GenerationOptions(List.of(), Map.of(), false, Map.of(
                "/WEB-INF/tags/outer.tag", "<%@ taglib prefix='nested' tagdir='/WEB-INF/tags/nested' %>"
                        .concat("<nested:echo literal=\"#{expr}\"/>\n").getBytes(StandardCharsets.UTF_8),
                "/WEB-INF/tags/nested/echo.tag", "<%@ attribute name='literal' %><% out.print(literal); %>"
                        .getBytes(StandardCharsets.UTF_8),
                "/WEB-INF/tags/implicit.tld", "<taglib version='2.0'></taglib>".getBytes(StandardCharsets.UTF_8)), Map.of());
        var page = new TranslationUnit(options).read("/index.jsp",
                "<%@ taglib prefix='t' tagdir='/WEB-INF/tags' %><t:outer/>");

        var compiled = TagFileCompiler.compile(List.of(page), options, "fixture.tagfiles", List.of());

        assertEquals(2, compiled.sources().size(), compiled.libraries().toString());
        assertTrue(compiled.sources().stream().anyMatch(source -> source.source()
                .contains("setLiteral((java.lang.String) (\"#{expr}\"));")));
    }

    @Test void compilesTagFilesDeclaredByATld() {
        String tld = """
                <taglib version="3.0"><uri>urn:packaged-tags</uri>
                  <tag-file><name>sample</name><path>/WEB-INF/tags/sample.tag</path></tag-file>
                </taglib>
                """;
        var library = new TldParser().parse("/WEB-INF/packaged.tld", tld.getBytes(StandardCharsets.UTF_8));
        var options = new GenerationOptions(List.of(), Map.of(), false,
                Map.of("/WEB-INF/tags/sample.tag", "packaged".getBytes(StandardCharsets.UTF_8)),
                Map.of("urn:packaged-tags", library));
        var page = new TranslationUnit(options).read("/index.jsp",
                "<%@ taglib prefix=\"p\" uri=\"urn:packaged-tags\" %><p:sample/>");

        var compiled = TagFileCompiler.compile(List.of(page), options, "fixture.tagfiles", List.of());

        var compiledLibrary = compiled.libraries().get("urn:packaged-tags");
        assertNotNull(compiledLibrary);
        var tag = compiledLibrary.tags().get("sample");
        assertFalse(tag.unsupportedMetadata(), tag.unsupportedReason());
        assertNotNull(tag.handlerClass());
        assertEquals(1, compiled.sources().size());
    }

    @Test void compilesTldTagFilesReferencedByRelativeResourceUri() {
        String tld = """
                <taglib version="3.0"><uri>urn:packaged-tags</uri>
                  <tag-file><name>sample</name><path>/WEB-INF/tags/sample.tag</path></tag-file>
                </taglib>
                """;
        var library = new TldParser().parse("/WEB-INF/tag.tld", tld.getBytes(StandardCharsets.UTF_8));
        var options = new GenerationOptions(List.of(), Map.of(), false,
                Map.of("/WEB-INF/tag.tld", tld.getBytes(StandardCharsets.UTF_8),
                        "/WEB-INF/tags/sample.tag", "packaged".getBytes(StandardCharsets.UTF_8)),
                Map.of("/WEB-INF/tag.tld", library));
        var page = new TranslationUnit(options).read("/index.jsp",
                "<%@ taglib prefix=\"p\" uri=\"WEB-INF/tag.tld\" %><p:sample/>");

        var compiled = TagFileCompiler.compile(List.of(page), options, "fixture.tagfiles", List.of());

        var compiledLibrary = compiled.libraries().get("/WEB-INF/tag.tld");
        assertNotNull(compiledLibrary);
        var tag = compiledLibrary.tags().get("sample");
        assertFalse(tag.unsupportedMetadata(), tag.unsupportedReason());
        assertNotNull(tag.handlerClass());
        assertEquals(1, compiled.sources().size());

        var configured = new GenerationOptions(List.of(), Map.of(), false, options.byteSources(), compiled.libraries());
        var resolved = TldReferences.discoverStrict(List.of(page), configured, options.tagLibraries());
        var resolvedTag = resolved.resolve("WEB-INF/tag.tld", page.tagLibraries().getFirst().location(), true)
                .tags().get("sample");
        assertFalse(resolvedTag.unsupportedMetadata(), resolvedTag.unsupportedReason());
        assertEquals(tag.handlerClass(), resolvedTag.handlerClass());
    }

    @Test void rejectsConflictingRepeatedTagFileMetadata() {
        String tagFile = """
                <%@ tag body-content="empty" description="First" %>
                <%@ tag body-content="scriptless" description="Second" %>
                """;

        var tag = compileTagFile(tagFile).libraries().get("urn:jsptagdir:/WEB-INF/tags").tags().get("sample");
        assertTrue(tag.unsupportedMetadata());
        assertTrue(tag.unsupportedReason().contains("Conflicting"), tag.unsupportedReason());
    }

    @Test void rejectsExplicitFalseRtexprvalueForFragmentTagFileAttributes() {
        var tag = compileTagFile("<%@ attribute name='piece' fragment='true' rtexprvalue='false' %>")
                .libraries().get("urn:jsptagdir:/WEB-INF/tags").tags().get("sample");

        assertTrue(tag.unsupportedMetadata(), tag.unsupportedReason());
        assertTrue(tag.unsupportedReason().contains("rtexprvalue"), tag.unsupportedReason());
    }

    @Test void supportsTagFileVariableNamesDerivedFromRequiredStringAttributes() {
        var compiled = compileTagFile("<%@ attribute name='var' type='java.lang.String' required='true' rtexprvalue='false' %>"
                + "<%@ variable alias='result' name-from-attribute='var' scope='AT_BEGIN' %>");
        var tag = compiled.libraries().get("urn:jsptagdir:/WEB-INF/tags").tags().get("sample");

        assertFalse(tag.unsupportedMetadata(), tag.unsupportedReason());
        assertEquals(1, tag.variables().size());
        var variable = tag.variables().getFirst();
        assertNull(variable.name());
        assertEquals("var", variable.nameFromAttribute());
        assertEquals("result", variable.alias());
        assertEquals("java.lang.String", variable.className());
        assertEquals(TagVariableDescriptor.AT_BEGIN, variable.scope());
    }

    @Test void synchronizesTagFileVariableAliasesWithTheirExternalNames() throws Exception {
        String tagFile = "<%@ attribute name='var' required='true' rtexprvalue='false' %>"
                + "<%@ variable alias='result' name-from-attribute='var' scope='AT_BEGIN' %>"
                + "<% jspContext.setAttribute(\"x\", \"ignoredInCallingPage\");"
                + " jspContext.setAttribute(\"result\", \"tagFileResult\"); %>"
                + "<jsp:doBody/>${x}|${result}|"
                + "<% jspContext.setAttribute(\"x\", \"stillIgnoredInCallingPage\");"
                + " jspContext.setAttribute(\"result\", \"4\"); %>";
        var options = new GenerationOptions(List.of(), Map.of(), false, Map.of(
                "/WEB-INF/tags/aliased.tag", tagFile.getBytes(StandardCharsets.UTF_8)), Map.of());
        String page = "<%@ taglib prefix='t' tagdir='/WEB-INF/tags' %>"
                + "<% pageContext.setAttribute(\"x\", \"1\");"
                + " pageContext.setAttribute(\"result\", \"callingPageResult\"); %>"
                + "<t:aliased var='x'>${x}|${result}|</t:aliased>${x}|${result}";

        assertEquals("tagFileResult|callingPageResult|ignoredInCallingPage|tagFileResult|4|callingPageResult",
                render(page, false, options));
    }

    @Test void rejectsInvalidNameFromAttributeContractsInTagFileVariables() {
        for (String source : List.of(
                "<%@ variable name-from-attribute='var' %>",
                "<%@ attribute name='var' type='java.lang.Integer' required='true' rtexprvalue='false' %>"
                        + "<%@ variable name-from-attribute='var' %>",
                "<%@ attribute name='var' type='java.lang.String' rtexprvalue='false' %>"
                        + "<%@ variable name-from-attribute='var' %>",
                "<%@ attribute name='var' required='true' rtexprvalue='true' %>"
                        + "<%@ variable name-from-attribute='var' %>")) {
            var tag = compileTagFile(source).libraries().get("urn:jsptagdir:/WEB-INF/tags").tags().get("sample");
            assertTrue(tag.unsupportedMetadata(), tag.unsupportedReason());
        }
    }

    @Test void invokingAnUnsetOptionalTagFileFragmentIsANoop() throws Exception {
        var options = new GenerationOptions(List.of(), Map.of(), false, Map.of(
                "/WEB-INF/tags/sample.tag", "<%@ attribute name='piece' fragment='true' %><jsp:invoke fragment='piece'/>"
                        .getBytes(StandardCharsets.UTF_8)), Map.of());

        assertEquals("", render("<%@ taglib prefix='t' tagdir='/WEB-INF/tags' %><t:sample/>", false, options));
    }

    @Test void dynamicElementElNamesAreCoercedFromObjects() throws Exception {
        var options = new GenerationOptions(List.of(), Map.of(), false, Map.of(
                "/WEB-INF/tags/sample.tag", "<jsp:element name=\"${'element1'}\">body</jsp:element>"
                        .getBytes(StandardCharsets.UTF_8)), Map.of());

        var output = render("<%@ taglib prefix='t' tagdir='/WEB-INF/tags' %><t:sample/>", false, options);

        assertTrue(output.contains("<element1>body</element1>"), output);
    }

    @Test void bufferedIterationsRetainContentUntilTheHandlerClearsIt() throws Exception {
        assertEquals("[ia][iaa]",render("<x:bufferedRepeat>a</x:bufferedRepeat>"));
    }
    @Test void unsupportedOrMissingMetadataFailsDuringTranslation() throws Exception {
        prepare();
        for (String source : List.of("<x:echo/>", "<x:echo label='a' count='bad'/>", "<x:echo label='a' unknown='b'/>",
                "<x:echo label='a'><% out.print(1); %></x:echo>", "<x:missing/>")) {
            assertThrows(PageParseException.class, generation("/tags.jsp", prefix()+source, options()));
        }
    }
    private String prefix() { return "<%@ page session='false' %><%@ taglib prefix='x' uri='urn:test' %>"; }
    private TagFileCompiler.Result compileTagFile(String source) {
        var options = tagFileOptions(source);
        var page = new TranslationUnit(options).read("/index.jsp",
                "<%@ taglib prefix='t' tagdir='/WEB-INF/tags' %><t:sample/>");
        return TagFileCompiler.compile(List.of(page), options, "fixture.tagfiles", List.of());
    }
    private GenerationOptions tagxOptions(String source) {
        return new GenerationOptions(List.of(), Map.of(), false,
                Map.of("/WEB-INF/tags/sample.tagx", source.getBytes(StandardCharsets.UTF_8)), Map.of());
    }
    private GenerationOptions tagFileOptions(String source) {
        return new GenerationOptions(List.of(), Map.of(), false,
                Map.of("/WEB-INF/tags/sample.tag", source.getBytes(StandardCharsets.UTF_8)), Map.of());
    }
    private GenerationOptions tagDirectoryOptions(String implicitTld) {
        return new GenerationOptions(List.of(), Map.of(), false, Map.of(
                "/WEB-INF/tags/sample.tag", "sample".getBytes(StandardCharsets.UTF_8),
                "/WEB-INF/tags/implicit.tld", implicitTld.getBytes(StandardCharsets.UTF_8)), Map.of());
    }
    private GenerationOptions options() {
        String tld = """
                <taglib><uri>urn:test</uri>
                <function><name>upperCase</name><function-class>fixture.Functions</function-class>
                  <function-signature>java.lang.String upperCase(java.lang.String)</function-signature></function>
                <tag><name>echo</name><tag-class>fixture.Tags$Echo</tag-class><body-content>scriptless</body-content>
                  <attribute><name>label</name><required>true</required></attribute><attribute><name>count</name><type>int</type></attribute></tag>
                <tag><name>repeat</name><tag-class>fixture.Tags$Repeat</tag-class><body-content>JSP</body-content>
                  <attribute><name>count</name><type>int</type><required>true</required></attribute></tag>
                <tag><name>sync</name><tag-class>fixture.Tags$SyncVariables</tag-class><tei-class>fixture.Tags$Variables</tei-class><body-content>JSP</body-content></tag>
                <tag><name>simpleSync</name><tag-class>fixture.Tags$SimpleSyncVariables</tag-class><tei-class>fixture.Tags$Variables</tei-class><body-content>empty</body-content></tag>
                <tag><name>simpleTldSync</name><tag-class>fixture.Tags$SimpleSyncVariables</tag-class><body-content>empty</body-content>
                  <variable><name-given>begin</name-given><variable-class>java.lang.Integer</variable-class><scope>AT_BEGIN</scope></variable>
                  <variable><name-given>end</name-given><variable-class>java.lang.Integer</variable-class><scope>AT_END</scope></variable></tag>
                <tag><name>simpleTldUndeclared</name><tag-class>fixture.Tags$SimpleSyncVariables</tag-class><body-content>empty</body-content>
                  <variable><name-given>hidden</name-given><declare>false</declare><scope>AT_BEGIN</scope></variable></tag>
                <tag><name>validate</name><tag-class>fixture.Tags$Validate</tag-class><tei-class>fixture.Tags$Validation</tei-class><body-content>empty</body-content>
                  <attribute><name>value</name><required>true</required></attribute></tag>
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
    /** Defers one generation, so that an expected failure can only come from {@code generate}. */
    private Executable generation(String path, String source, GenerationOptions options) {
        var pageGenerator = generator();
        return () -> pageGenerator.generate(path, source, "fixture.GeneratedPage", options);
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
        String pageSource = source.startsWith("<jsp:root") ? source : prefix()+source;
        var page = new TranslationUnit(options).read("/tags.jsp", pageSource);
        var paths = new ArrayList<Path>();
        paths.add(directory);
        for (String path : modulePath().split(java.util.regex.Pattern.quote(File.pathSeparator))) paths.add(Path.of(path));
        var tagFiles = TagFileCompiler.compile(List.of(page), options, "fixture.tagfiles", paths);
        var tagLibraries = new LinkedHashMap<>(options.tagLibraries());
        tagLibraries.putAll(tagFiles.libraries());
        options = options.withTagLibraries(tagLibraries);
        var generated = generator().generate("/tags.jsp", pageSource, "fixture.GeneratedPage", options);
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
        var sources = new ArrayList<>(tagFiles.sources());
        sources.add(generated);
        sources.add(new GeneratedPage("io.vidocq.ibarra.core.internal.TagMain",main,Map.of()));
        sources.add(new GeneratedPage("io.vidocq.ibarra.core.internal.ServletDoubles",doubles,Map.of()));
        compile(sources);
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
                    public static class Validation extends TagExtraInfo {
                        static int calls;
                        static String functionMetadata;
                        public ValidationMessage[] validate(TagData data) {
                            calls++;
                            TagAttributeInfo[] attributes=getTagInfo().getAttributes();
                            if (!getTagInfo().getTagName().equals("validate") || attributes.length!=1
                                    || !attributes[0].getName().equals("value") || !attributes[0].isRequired()
                                    || !attributes[0].getTypeName().equals("java.lang.String")
                                    || !"literal".equals(data.getAttributeString("value")))
                                return new ValidationMessage[]{new ValidationMessage(data.getId(), "Unexpected tag metadata")};
                            TagLibraryInfo library=getTagInfo().getTagLibrary();
                            FunctionInfo function=library==null ? null : library.getFunction("upperCase");
                            if (library==null || !"x".equals(library.getPrefixString()) || !"urn:test".equals(library.getURI())
                                    || function==null || !"upperCase".equals(function.getName())
                                    || !"fixture.Functions".equals(function.getFunctionClass())
                                    || !"java.lang.String upperCase(java.lang.String)".equals(function.getFunctionSignature()))
                                return new ValidationMessage[]{new ValidationMessage(data.getId(), "Unexpected tag library function metadata")};
                            functionMetadata=function.getName()+":"+function.getFunctionClass()+":"+function.getFunctionSignature();
                            return null;
                        }
                    }
                    public static class TagFileValidation extends TagExtraInfo {
                        public ValidationMessage[] validate(TagData data) {
                            TagLibraryInfo library = getTagInfo().getTagLibrary();
                            TagFileInfo[] files = library == null ? null : library.getTagFiles();
                            if (files == null || files.length != 1)
                                return new ValidationMessage[]{new ValidationMessage(data.getId(), "Expected one tag file")};
                            TagFileInfo file = files[0];
                            TagInfo info = file.getTagInfo();
                            if (!"TagFile1".equals(file.getName())
                                    || !"/WEB-INF/tags/TagFile1.tag".equals(file.getPath())
                                    || info == null || !"TagFile1".equals(info.getDisplayName())
                                    || !"scriptless".equals(info.getBodyContent()) || !info.hasDynamicAttributes()
                                    || !"/16/icon.jpg".equals(info.getSmallIcon())
                                    || !"/32/icon.jpg".equals(info.getLargeIcon())
                                    || !"Simple Tag File".equals(info.getInfoString()))
                                return new ValidationMessage[]{new ValidationMessage(data.getId(), "Unexpected tag file metadata")};
                            return null;
                        }
                    }
                    public static class TagCatalogValidation extends TagExtraInfo {
                        public ValidationMessage[] validate(TagData data) {
                            TagInfo invocation = getTagInfo();
                            if (!"Catalog tag information".equals(invocation.getInfoString())
                                    || !"Catalog tag".equals(invocation.getDisplayName())
                                    || !"/16/catalog.jpg".equals(invocation.getSmallIcon())
                                    || !"/32/catalog.jpg".equals(invocation.getLargeIcon()))
                                return failure(data, "Unexpected invoking tag metadata");
                            TagLibraryInfo library = getTagInfo().getTagLibrary();
                            TagInfo[] tags = library == null ? null : library.getTags();
                            if (tags == null || tags.length != 3)
                                return failure(data, "Expected the three ordinary TLD tags");
                            TagInfo first = null, second = null;
                            for (TagInfo tag : tags) {
                                if ("first".equals(tag.getTagName())) first = tag;
                                if ("second".equals(tag.getTagName())) second = tag;
                            }
                            if (first == null || second == null || first.getTagLibrary() != library
                                    || !"fixture.Tags$Echo".equals(first.getTagClassName())
                                    || !"scriptless".equals(first.getBodyContent())
                                    || !"First tag information".equals(first.getInfoString())
                                    || !"First tag".equals(first.getDisplayName())
                                    || !"/16/first.jpg".equals(first.getSmallIcon())
                                    || !"/32/first.jpg".equals(first.getLargeIcon()) || !first.hasDynamicAttributes())
                                return failure(data, "Unexpected first tag metadata");
                            TagAttributeInfo[] attributes = first.getAttributes();
                            TagVariableInfo[] variables = first.getTagVariableInfos();
                            TagExtraInfo extraInfo = first.getTagExtraInfo();
                            if (attributes == null || attributes.length != 1 || !"label".equals(attributes[0].getName())
                                    || !attributes[0].isRequired())
                                return failure(data, "Unexpected first tag attributes");
                            if (variables == null || variables.length != 1 || !"result".equals(variables[0].getNameGiven())
                                    || !"java.lang.Integer".equals(variables[0].getClassName())
                                    || variables[0].getDeclare() || variables[0].getScope() != VariableInfo.AT_BEGIN)
                                return failure(data, "Unexpected first tag variables");
                            if (extraInfo == null) return failure(data, "First tag extra info was null");
                            if (extraInfo.getTagInfo() != first) return failure(data, "First tag extra info was not linked");
                            if (!"tagdependent".equals(second.getBodyContent())
                                    || !"Second tag information".equals(second.getInfoString())
                                    || second.getAttributes() == null || second.getAttributes().length != 0
                                    || second.getTagVariableInfos() == null || second.getTagVariableInfos().length != 0
                                    || second.getTagExtraInfo() != null || second.hasDynamicAttributes())
                                return failure(data, "Unexpected second tag metadata");
                            return null;
                        }
                        private ValidationMessage[] failure(TagData data, String message) {
                            return new ValidationMessage[]{new ValidationMessage(data.getId(), message)};
                        }
                    }
                    public static class TagLibraryMetadataValidation extends TagExtraInfo {
                        public ValidationMessage[] validate(TagData data) {
                            TagLibraryInfo library = getTagInfo().getTagLibrary();
                            if (library == null || !"urn:primary".equals(library.getURI())
                                    || !"urn:primary".equals(library.getReliableURN())
                                    || !"primary".equals(library.getShortName())
                                    || !"Primary library".equals(library.getInfoString())
                                    || !"3.0".equals(library.getRequiredVersion()))
                                return failure(data, "Unexpected primary tag library metadata");
                            TagLibraryInfo[] imported = library.getTagLibraryInfos();
                            if (imported == null || imported.length != 2)
                                return failure(data, "Expected both imported tag libraries");
                            boolean primary = false, secondary = false;
                            for (TagLibraryInfo item : imported) {
                                if ("urn:primary".equals(item.getReliableURN()) && "primary".equals(item.getShortName())) primary = true;
                                if ("urn:secondary".equals(item.getReliableURN()) && "secondary".equals(item.getShortName())
                                        && "Secondary library".equals(item.getInfoString())
                                        && "3.0".equals(item.getRequiredVersion())) secondary = true;
                            }
                            if (!primary || !secondary) return failure(data, "Unexpected imported tag library metadata");
                            return null;
                        }
                        private ValidationMessage[] failure(TagData data, String message) {
                            return new ValidationMessage[]{new ValidationMessage(data.getId(), message)};
                        }
                    }
                    public static class PageDataValidator extends TagLibraryValidator {
                        static int calls;
                        public ValidationMessage[] validate(String prefix, String uri, PageData page) {
                            calls++;
                            try {
                                String first = new String(page.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                                String second = new String(page.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                                if (!"x".equals(prefix) || !"urn:test".equals(uri)
                                        || !"configured".equals(getInitParameters().get("expected")) || !first.equals(second)
                                        || !first.startsWith("<jsp:root") || !first.contains("xmlns:jsp=" + (char) 34 + "http://java.sun.com/JSP/Page" + (char) 34)
                                        || !first.contains("xmlns:x=" + (char) 34 + "urn:test" + (char) 34)
                                        || !first.contains("version=" + (char) 34 + "2.0" + (char) 34)
                                        || !first.contains("<jsp:declaration") || !first.contains("<jsp:scriptlet")
                                        || !first.contains("<jsp:expression") || !first.contains("<jsp:text")
                                        || !first.contains("<x:check") || !first.contains("runtime=" + (char) 34)
                                        || !first.contains("%= &quot;dynamic&quot; %")
                                        || !first.contains("jsp:id=" + (char) 34))
                                    return new ValidationMessage[]{new ValidationMessage(null, "Unexpected PageData XML view: " + first)};
                                return null;
                            } catch (java.io.IOException failure) {
                                return new ValidationMessage[]{new ValidationMessage(null, failure.toString())};
                            }
                        }
                    }
                    public static class RecordingValidator extends TagLibraryValidator {
                        public static String seen = "";
                        public ValidationMessage[] validate(String prefix, String uri, PageData page) {
                            seen += prefix + "=" + uri + ";";
                            return null;
                        }
                    }
                    public static class ConstructorFailingValidator extends TagLibraryValidator {
                        public static final java.util.concurrent.atomic.AtomicInteger constructions = new java.util.concurrent.atomic.AtomicInteger();
                        public ConstructorFailingValidator() { constructions.incrementAndGet(); throw new IllegalStateException("constructor failure"); }
                    }
                    public static class ErrorOnceValidator extends TagLibraryValidator {
                        public static final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
                        public ValidationMessage[] validate(String prefix, String uri, PageData page) {
                            if (calls.incrementAndGet() == 1) throw new StackOverflowError("first run");
                            return null;
                        }
                    }
                    public static class BlockingValidator extends TagLibraryValidator {
                        public static final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
                        public static final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
                        public static final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
                        public ValidationMessage[] validate(String prefix, String uri, PageData page) {
                            calls.incrementAndGet(); entered.countDown();
                            try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                            return null;
                        }
                    }
                    public static class RejectingValidator extends TagLibraryValidator {
                        public static final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
                        public ValidationMessage[] validate(String prefix, String uri, PageData page) {
                            calls.incrementAndGet();
                            try { Thread.sleep(200); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                            return new ValidationMessage[]{new ValidationMessage(null, "rejected")};
                        }
                    }
                    public static class PageDataTag extends SimpleTagSupport {
                        private String test;
                        public void setTest(String value) { test = value; }
                        public void setRuntime(String value) { }
                        public void doTag() throws java.io.IOException {
                            getJspContext().getOut().write(PageDataValidator.calls + ":" + test);
                        }
                    }
                    public static class Validate extends SimpleTagSupport {
                        private String value;
                        public void setValue(String value) { this.value=value; }
                        public void doTag() throws java.io.IOException {
                            getJspContext().getOut().write(Validation.calls+":"+Validation.functionMetadata+":"+value);
                        }
                    }
                    public static class TagFileMetadata extends SimpleTagSupport {
                        public void doTag() throws java.io.IOException { getJspContext().getOut().write("ok"); }
                    }
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
