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
import java.io.File;
import java.lang.classfile.ClassFile;
import java.lang.module.ModuleFinder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class PageGeneratorTest {
    @TempDir Path directory;
    private static final String PAGE_CLASS = "fixture.GeneratedPage";

    @Test
    void rendersTemplateAndScriptingWithRequestImplicitObjects() throws Exception {
        String jsp = "<%@ page session='false' contentType='text/plain' %><%! int count; %>"
                + "<% count++; %>Hello <%= request.getAttribute(\"name\") %> #<%= count %>!";
        assertEquals("Hello Ada #1!", render(jsp, "request.setAttribute(\"name\", \"Ada\");", ""));
    }

    @Test
    void userLifecycleHooksAreCalledWithoutSkippingGeneratedInitialization() throws Exception {
        String jsp = "<%@ page session='false' %><%! String marker;"
                + " public void jspInit() { marker = \"ready\"; }"
                + " public void jspDestroy() { getServletContext().setAttribute(\"destroyed\", true); } %>"
                + "<%= marker %> ${1 + 2}";
        assertEquals("ready 3", render(jsp, "", "page.destroy(); if (!Boolean.TRUE.equals(context.getAttribute(\"destroyed\"))) throw new AssertionError();"));
    }

    @Test
    void rendersCachedPeanoExpressionsAgainstEachRequestContext() throws Exception {
        String jsp = "<%@ page session='false' %>${requestScope.user.name}: ${param.q}: ${2 + 3}";
        assertEquals("Ada: query: 5", render(jsp,
                "request.setAttribute(\"user\", java.util.Map.of(\"name\", \"Ada\")); request.parameters.put(\"q\", new String[]{\"query\"});", ""));
    }

    @Test
    void xmlTemplatesAndJspTextRenderWithoutTheJspRootWrapper() throws Exception {
        String xml = "<jsp:root xmlns:jsp='" + PageParser.JSP_NAMESPACE + "' version='4.0'>"
                + "<jsp:directive.page session='false'/><p class='x'>Hello &amp; ${requestScope.name}</p>"
                + "<jsp:text> ! </jsp:text></jsp:root>";
        assertEquals("<p class=\"x\">Hello & Ada</p> ! ", render(xml, "request.setAttribute(\"name\", \"Ada\");", ""));
    }

    @Test
    void jspRootAcceptsAnExplicitBodyEnvelope() throws Exception {
        String xml = "<jsp:root xmlns:jsp='" + PageParser.JSP_NAMESPACE + "' version='2.0'>"
                + "<jsp:body><jsp:directive.page session='false'/><status>Test PASSED</status></jsp:body></jsp:root>";
        var page = generator().generate("/root.jspx", xml, PAGE_CLASS, GenerationOptions.DEFAULT);
        assertEquals("<status>Test PASSED</status>", run(page, "", ""));
    }

    @Test
    void jspRootRejectsAnUnsupportedVersion() {
        String xml = "<jsp:root xmlns:jsp='" + PageParser.JSP_NAMESPACE + "' version='999.2'><status>Test FAILED</status></jsp:root>";
        assertThrows(PageParseException.class,
                generation("/invalid-version.jspx", xml, GenerationOptions.DEFAULT));
    }

    @Test
    void xmlDocumentWithoutJspRootAddsDefaultDeclarationBeforeTemplate() throws Exception {
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?><hello/>",
                run(generator().generate("/document.jspx", "\n<hello/>\n", PAGE_CLASS, GenerationOptions.DEFAULT), "", ""));
    }

    @Test
    void explicitXmlDeclarationIsStaticAndPrecedesUnexecutedOutputAction() throws Exception {
        String source = "<jsp:root xmlns:jsp='" + PageParser.JSP_NAMESPACE + "' version='4.0'>"
                + "<jsp:directive.page session='false'/><jsp:text> leading </jsp:text><hello/>"
                + "<jsp:scriptlet>if (false) {</jsp:scriptlet>"
                + "<jsp:output omit-xml-declaration='no'/><jsp:scriptlet>}</jsp:scriptlet></jsp:root>";
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?> leading <hello/>",
                run(generator().generate("/document.jspx", source, PAGE_CLASS, GenerationOptions.DEFAULT), "", ""));
    }

    @Test
    void emptyOutputActionKeepsJspRootOmissionDefault() throws Exception {
        String source = "<jsp:root xmlns:jsp='" + PageParser.JSP_NAMESPACE + "' version='4.0'>"
                + "<hello/><jsp:output/></jsp:root>";
        assertEquals("<hello/>", run(generator().generate("/document.jspx", source, PAGE_CLASS, GenerationOptions.DEFAULT), "", ""));
    }

    @Test
    void xmlDeclarationUsesEffectiveResponseEncodingInsteadOfPageEncoding() throws Exception {
        String source = "<jsp:root xmlns:jsp='" + PageParser.JSP_NAMESPACE + "' version='4.0'>"
                + "<jsp:directive.page session='false' pageEncoding='UTF-8' contentType='text/xml;charset=ISO-8859-1'/>"
                + "<hello/><jsp:output omit-xml-declaration='false'/></jsp:root>";
        assertEquals("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><hello/>",
                run(generator().generate("/document.jspx", source, PAGE_CLASS, GenerationOptions.DEFAULT),
                        "response.setCharacterEncoding(\"ISO-8859-1\");", ""));
    }

    @Test
    void repeatedXmlOmissionValuesEmitOnlyOnePrologueAcrossIncludes() throws Exception {
        String source = "<hello xmlns:jsp='" + PageParser.JSP_NAMESPACE + "'>"
                + "<jsp:directive.include file='/included.jspx'/><jsp:output omit-xml-declaration='false'/></hello>";
        var options = new GenerationOptions(List.of(), Map.of("/included.jspx",
                "<jsp:root xmlns:jsp='" + PageParser.JSP_NAMESPACE + "' version='4.0'>"
                        + "<jsp:output omit-xml-declaration='false'/><part/></jsp:root>"));
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?><hello><part/></hello>",
                run(generator().generate("/document.jspx", source, PAGE_CLASS, options), "", ""));
    }

    @Test
    void responseEncodingDeclarationPrecedesStaticPublicDoctypeAndDocumentBody() throws Exception {
        String source = "<jsp:root xmlns:jsp='" + PageParser.JSP_NAMESPACE + "' version='4.0'>"
                + "<jsp:directive.page session='false' pageEncoding='UTF-8' contentType='text/xml;charset=ISO-8859-1'/>"
                + "<hello/><jsp:scriptlet>if (false) {</jsp:scriptlet>"
                + "<jsp:output omit-xml-declaration='no' doctype-root-element='hello'"
                + " doctype-system='https://example.invalid/never-fetched.dtd' doctype-public='-//Example//DTD Page//EN'/>"
                + "<jsp:scriptlet>}</jsp:scriptlet></jsp:root>";
        assertEquals("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>"
                        + "<!DOCTYPE hello PUBLIC \"-//Example//DTD Page//EN\" \"https://example.invalid/never-fetched.dtd\"><hello/>",
                run(generator().generate("/document.jspx", source, PAGE_CLASS, GenerationOptions.DEFAULT),
                        "response.setCharacterEncoding(\"ISO-8859-1\");", ""));
    }

    @Test
    void conflictingXmlOmissionAcrossIncludesReportsSecondSource() {
        String source = "<jsp:root xmlns:jsp='" + PageParser.JSP_NAMESPACE + "' version='4.0'>"
                + "<jsp:output omit-xml-declaration='yes'/><jsp:directive.include file='/included.jspx'/></jsp:root>";
        var options = new GenerationOptions(List.of(), Map.of("/included.jspx",
                "<jsp:output xmlns:jsp='" + PageParser.JSP_NAMESPACE + "' omit-xml-declaration='false'/>"));
        var failure = assertThrows(PageParseException.class,
                generation("/document.jspx", source, options));
        assertTrue(failure.getMessage().contains("/included.jspx"), failure.getMessage());
        assertTrue(failure.getMessage().contains("Conflicting omit-xml-declaration"), failure.getMessage());
    }

    @Test
    void repeatedXmlOmissionMustUseTheSameLiteralValue() {
        for (String[] values : List.of(new String[]{"yes", "true"}, new String[]{"no", "false"})) {
            String source = "<jsp:root xmlns:jsp='" + PageParser.JSP_NAMESPACE + "' version='4.0'>"
                    + "<jsp:output omit-xml-declaration='" + values[0] + "'/>"
                    + "<jsp:output omit-xml-declaration='" + values[1] + "'/></jsp:root>";
            assertThrows(PageParseException.class,
                    generation("/document.jspx", source, GenerationOptions.DEFAULT));
        }
    }

    @Test
    void includedJspRootDoesNotChangeTopDocumentDeclarationDefault() throws Exception {
        String source = "<hello xmlns:jsp='" + PageParser.JSP_NAMESPACE + "'>"
                + "<jsp:directive.include file='/included.jspx'/></hello>";
        var options = new GenerationOptions(List.of(), Map.of("/included.jspx",
                "<jsp:root xmlns:jsp='" + PageParser.JSP_NAMESPACE + "' version='4.0'><part/></jsp:root>"));
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?><hello><part/></hello>",
                run(generator().generate("/document.jspx", source, PAGE_CLASS, options), "", ""));
    }

    @Test
    void aliasedAndDefaultNamespaceJspRootsKeepTheirOmissionDefaultWithIncludes() throws Exception {
        var options = new GenerationOptions(List.of(), Map.of("/included.jspx", "<part/>"));
        for (String prefix : List.of("p:", "")) {
            String declaration = prefix.isEmpty() ? "xmlns" : "xmlns:p";
            String source = "<" + prefix + "root " + declaration + "='" + PageParser.JSP_NAMESPACE + "' version='4.0'>"
                    + "<" + prefix + "directive.include file='/included.jspx'/></" + prefix + "root>";
            assertEquals("<part/>", run(generator().generate("/document.jspx", source, PAGE_CLASS, options), "", ""));
        }
    }

    @Test
    void everyXmlOmissionAliasAppliesToWholeDocumentBeforeMarkup() throws Exception {
        for (String value : List.of("yes", "true", "no", "false")) {
            String source = "<hello xmlns:jsp='" + PageParser.JSP_NAMESPACE + "'>"
                    + "<part/><jsp:output omit-xml-declaration='" + value + "'/></hello>";
            String expected = (Set.of("yes", "true").contains(value) ? ""
                    : "<?xml version=\"1.0\" encoding=\"UTF-8\"?>") + "<hello><part/></hello>";
            assertEquals(expected, run(generator().generate("/document.jspx", source, PAGE_CLASS,
                    GenerationOptions.DEFAULT), "", ""));
        }
    }

    @Test
    void includedXmlOutputContributesToAStandardTranslationUnit() throws Exception {
        var options = new GenerationOptions(List.of(), Map.of("/included.jspx",
                "<jsp:root xmlns:jsp='" + PageParser.JSP_NAMESPACE + "' version='4.0'>"
                        + "<jsp:output omit-xml-declaration='false'/><part/></jsp:root>"));
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?>before<part/>after",
                run(generator().generate("/document.jsp", "before<%@ include file='/included.jspx' %>after",
                        PAGE_CLASS, options), "", ""));
    }

    @Test
    void includesRespectOrderAndEncodeParameters() throws Exception {
        String jsp = "<%@ page session='false' %>before<jsp:include page='/part.jsp' flush='false'>"
                + "<jsp:param name='q' value='a &amp; b'/></jsp:include>after";
        assertEquals("beforeINCLUDEDafter", render(jsp, dispatchSetup(),
                "if (!context.lastDispatchPath.equals(\"/part.jsp?q=a+%26amp%3B+b\")) throw new AssertionError(context.lastDispatchPath);"));
    }

    @Test
    void includeParametersAcceptNamedJspAttributes() throws Exception {
        String jsp = "<%@ page session='false' %><jsp:include page='/part.jsp'>"
                + "<jsp:param value='one'><jsp:attribute name='name'>first</jsp:attribute></jsp:param>"
                + "<jsp:param name='second'><jsp:attribute name='value' trim='true'> ${param.v} </jsp:attribute></jsp:param>"
                + "</jsp:include>";
        assertEquals("INCLUDED", render(jsp, dispatchSetup() + "request.parameters.put(\"v\", new String[]{\"two\"});",
                "if (!context.lastDispatchPath.equals(\"/part.jsp?first=one&second=two\")) throw new AssertionError(context.lastDispatchPath);"));
    }

    @Test
    void scriptingInvalidStillAllowsRequestTimeExpressionsInActionAttributes() throws Exception {
        var options = new GenerationOptions(List.of(new JspPropertyGroup(List.of("*.jsp"),
                Map.of("scripting-invalid", "true"), List.of(), List.of())), Map.of());
        String jsp = "<%@ page session='false' %><jsp:include page='<%= \"/part.jsp\" %>' flush='false'/>";
        var generated = generator().generate("/fixture.jsp", jsp, PAGE_CLASS, options);
        assertEquals("INCLUDED", run(generated, dispatchSetup(), ""));
    }

    @Test
    void forwardDropsPendingOutputAndStopsThePage() throws Exception {
        String jsp = "<%@ page session='false' %>discard<jsp:forward page='/part.jsp'/>unreachable";
        assertEquals("FORWARDED", render(jsp, dispatchSetup(), ""));
    }

    @Test
    void anIncludedForwardStopsGeneratedParentOutputAndSideEffects() throws Exception {
        String source = "<%@ page session='false' %>discard<jsp:include page='/included.jsp' flush='false'/>"
                + "after<% application.setAttribute(\"after-include\", true); %>";
        String setup = """
                context.dispatchers.put("/included.jsp", new RequestDispatcher() {
                    public void include(ServletRequest req, ServletResponse res) throws ServletException, java.io.IOException {
                        var servlet = new jakarta.servlet.http.HttpServlet() {};
                        servlet.init(new ServletConfig() {
                            public String getServletName() { return "included"; }
                            public ServletContext getServletContext() { return context; }
                            public String getInitParameter(String name) { return null; }
                            public java.util.Enumeration<String> getInitParameterNames() { return java.util.Collections.emptyEnumeration(); }
                        });
                        var factory = JspFactory.getDefaultFactory();
                        var included = factory.getPageContext(servlet, req, res, null, false, 64, true);
                        try { included.forward("/target.jsp"); }
                        finally { factory.releasePageContext(included); }
                    }
                    public void forward(ServletRequest req, ServletResponse res) { throw new UnsupportedOperationException(); }
                });
                context.dispatchers.put("/target.jsp", new RequestDispatcher() {
                    public void include(ServletRequest req, ServletResponse res) { throw new UnsupportedOperationException(); }
                    public void forward(ServletRequest req, ServletResponse res) throws java.io.IOException { res.resetBuffer(); res.getWriter().write("target"); res.getWriter().flush(); }
                });
                """;
        assertEquals("target", render(source, setup,
                "if (context.getAttribute(\"after-include\") != null) throw new AssertionError(\"Parent continued after included forward\");"));
    }

    @Test
    void errorPageReceivesTheExceptionAndReplacesPendingOutput() throws Exception {
        String jsp = "<%@ page session='false' errorPage='/part.jsp' %>discard<% throw new IllegalArgumentException(\"bad\"); %>";
        assertEquals("FORWARDED", render(jsp, dispatchSetup(), ""));
    }

    @Test
    void generatesDirectBeanConstructionAndAccessorCalls() throws Exception {
        prepareBean();
        String jsp = "<%@ page session='false' %><jsp:useBean id='bean' class='fixture.Bean' scope='request'/>"
                + "<jsp:setProperty name='bean' property='count' value='7'/>"
                + "<jsp:setProperty name='bean' property='name' param='name'/>"
                + "<jsp:getProperty name='bean' property='name'/>:<jsp:getProperty name='bean' property='count'/>";
        var generated = generator().generate("/bean.jsp", jsp, PAGE_CLASS, GenerationOptions.DEFAULT);
        assertTrue(generated.source().contains("new fixture.Bean()"));
        assertTrue(generated.source().contains(".setCount("));
        assertTrue(generated.source().contains(".getName()"));
        assertFalse(generated.source().contains("java.lang.reflect"));
        assertEquals("Ada:7", run(generated, "request.parameters.put(\"name\", new String[]{\"Ada\"});", ""));
    }

    @Test
    void useBeanBeanNameInstantiatesClassAndCastsToDeclaredType() throws Exception {
        prepareBean();
        String jsp = "<%@ page session='false' %><jsp:useBean id='bean' type='fixture.Bean' beanName='fixture.Bean'>"
                + "<jsp:setProperty name='bean' property='name' value='Ada'/></jsp:useBean>"
                + "<jsp:getProperty name='bean' property='name'/>";
        var generated = generator().generate("/bean.jsp", jsp, PAGE_CLASS, GenerationOptions.DEFAULT);
        assertTrue(generated.source().contains("new fixture.Bean()"));
        assertFalse(generated.source().contains("_jspxInstantiateBean"));
        assertEquals("Ada", run(generated, "", ""));
    }

    @Test
    void useBeanBeanNameEvaluatesRequestTimeExpression() throws Exception {
        prepareBean();
        String jsp = "<%@ page session='false' %><% String className = request.getParameter(\"className\"); %>"
                + "<jsp:useBean id='bean' type='fixture.Bean' beanName='<%= className %>'>"
                + "<jsp:setProperty name='bean' property='name' value='Grace'/></jsp:useBean>"
                + "<jsp:getProperty name='bean' property='name'/>";
        assertEquals("Grace", render(jsp,
                "request.parameters.put(\"className\", new String[]{\"fixture.Bean\"});", ""));
    }

    @Test
    void useBeanBeanNameLoadsSerializedResourceAndCastsToDeclaredType() throws Exception {
        try (var output = new java.io.ObjectOutputStream(Files.newOutputStream(directory.resolve("date.ser")))) {
            output.writeObject(new java.util.Date(42));
        }
        String jsp = "<%@ page session='false' %><jsp:useBean id='bean' type='java.util.Date' beanName='date'/>"
                + "<%= bean.getTime() %>";
        assertEquals("42", render(jsp, "", ""));
    }

    @Test
    void propertyGroupsSupplyDefaultsAndPreludeCoda() throws Exception {
        var group = new JspPropertyGroup(List.of("/pages/*"),
                Map.of("el-ignored", "true", "default-content-type", "text/plain", "trim-directive-whitespaces", "true"),
                List.of("/before.jspf"), List.of("/after.jspf"));
        var options = new GenerationOptions(List.of(group),
                Map.of("/before.jspf", "PRE", "/after.jspf", "POST"));
        var generated = generator().generate("/pages/test.jsp", "<%@ page session='false' %>\n${name}\n",
                PAGE_CLASS, options);
        assertEquals("PRE\n${name}\nPOST", run(generated, "", "if (!response.contentType.startsWith(\"text/plain\")) throw new AssertionError();"));
    }

    @Test
    void staticIncludeDeclarationsAndSettingsBelongToTheTranslationUnit() throws Exception {
        var options = new GenerationOptions(List.of(), Map.of("/part.jspf", "<%! String word = \"included\"; %><%= word %>"));
        var generated = generator().generate("/main.jsp", "<%@ page session='false' %>A<%@ include file='part.jspf' %>B", PAGE_CLASS, options);
        assertEquals("AincludedB", run(generated, "", ""));
        assertThrows(PageParseException.class, generation("/main.jsp", "<%@ include file='part.jspf' %>",
                new GenerationOptions(List.of(), Map.of("/part.jspf", "<%@ include file='main.jsp' %>", "/main.jsp", "<%@ include file='part.jspf' %>"))));
    }

    @Test
    void rejectsUnsupportedActionsInvalidNamesAndDisallowedScriptingWithSourceLocations() {
        for (String source : List.of("<jsp:plugin/>", "<jsp:unknown/>", "<c:tag/>", "<%@ page language='groovy' %>")) {
            String page = source.startsWith("<c:") ? "<%@ taglib prefix='c' uri='urn:custom' %>" + source : source;
            assertThrows(PageParseException.class, generation("/bad.jsp", page, GenerationOptions.DEFAULT));
        }
        var pageGenerator = generator();
        assertThrows(IllegalArgumentException.class, () -> pageGenerator.generate("/x.jsp", "text", "not-a-name", GenerationOptions.DEFAULT));
        var options = new GenerationOptions(List.of(new JspPropertyGroup(List.of("*.jsp"),
                Map.of("scripting-invalid", "true"), List.of(), List.of())), Map.of());
        var error = assertThrows(PageParseException.class, generation("/bad.jsp", "\n<%= 1 %>", options));
        assertEquals(2, error.location().line());
    }

    @Test
    void compilerErrorsMapBackToJspSourceAndGeneratedClassesAreJava25() throws Exception {
        var bad = generator().generate("/broken.jsp", "<%@ page session='false' %>\n<% nonexistent(); %>", PAGE_CLASS, GenerationOptions.DEFAULT);
        var badPages = List.of(bad);
        var failure = assertThrows(PageCompilationException.class, () -> compile(badPages));
        assertTrue(failure.getMessage().contains("/broken.jsp:2"));
        var good = generator().generate("/ok.jsp", "<%@ page session='false' %>ok", PAGE_CLASS, GenerationOptions.DEFAULT);
        compile(List.of(good));
        var model = ClassFile.of().parse(directory.resolve("fixture/GeneratedPage.class"));
        assertEquals(ClassFile.JAVA_25_VERSION, model.majorVersion());
    }

    @Test
    void longAndQuotedTemplateTextCompilesWithoutOversizedConstants() throws Exception {
        String text = "é😀\"\\\n".repeat(12_000);
        assertEquals(text, render("<%@ page session='false' %>" + text, "", ""));
    }

    @Test
    void pureElBeanValuesKeepTheirObjectTypeAndArrayParametersUseAllValues() throws Exception {
        prepareBean();
        String jsp = "<%@ page session='false' %><jsp:useBean id='bean' class='fixture.Bean'/>"
                + "<jsp:setProperty name='bean' property='data' value='${requestScope.data}'/>"
                + "<jsp:setProperty name='bean' property='values' param='q'/>"
                + "<%= bean.getData() instanceof java.util.Map %>:<%= String.join(\",\", bean.getValues()) %>";
        assertEquals("true:a,b", render(jsp, "request.setAttribute(\"data\", java.util.Map.of(\"x\", 1)); request.parameters.put(\"q\", new String[]{\"a\",\"b\"});", ""));
    }

    @Test
    void propertyGroupElSettingsAlsoApplyToAttributes() throws Exception {
        var options = new GenerationOptions(List.of(new JspPropertyGroup(List.of("*.jspx"),
                Map.of("el-ignored", "true"), List.of(), List.of())), Map.of());
        var generated = generator().generate("/attributes.jspx", "<p title='${literal}'/>", PAGE_CLASS, options);
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?><p title=\"${literal}\"/>", run(generated, "", ""));
    }

    @Test
    void elStringsThatResembleFunctionsAreNotRejected() throws Exception {
        assertEquals("x:y()", render("<%@ page session='false' %>${'x:y()'}", "", ""));
    }

    @Test
    void alternateXmlRootPrefixesReuseParserSyntaxDetection() throws Exception {
        assertEquals("alias", render("<x:root xmlns:x='" + PageParser.JSP_NAMESPACE
                + "' version='4.0'><x:directive.page session='false'/><x:text>alias</x:text></x:root>", "", ""));
    }

    @Test
    void defaultJakartaImportsAreAvailableToScripting() throws Exception {
        assertEquals("true:true", render("<%@ page session='false' %><% PageContext pc = pageContext;"
                + " HttpServletRequest req = request; %><%= pc != null %>:<%= req == request %>", "", ""));
    }

    @Test
    void strictMissingElPolicyIsSupportedAndSessionlessSessionBeansAreRejected() {
        assertDoesNotThrow(() -> generator().generate("/missing.jsp",
                "<%@ page errorOnELNotFound='true' %>literal", PAGE_CLASS, GenerationOptions.DEFAULT));
        assertThrows(PageParseException.class, generation("/session.jsp",
                "<%@ page session='false' %><jsp:useBean id='bean' type='fixture.Bean' scope='session'/>", GenerationOptions.DEFAULT));
    }

    @Test
    void declarationsCannotOverrideTheGeneratedLifecycleEntryPoints() {
        for (String declaration : List.of("public void init(jakarta.servlet.ServletConfig config) {}",
                "public void service(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {}")) {
            var generated = generator().generate("/lifecycle.jsp", "<%@ page session='false' %><%! " + declaration + " %>",
                    PAGE_CLASS, GenerationOptions.DEFAULT);
            var pages = List.of(generated);
            assertThrows(PageCompilationException.class, () -> compile(pages));
        }
    }

    @Test
    void pageDirectiveCanExtendASuperclassWithFinalServletLifecycleMethods() throws Exception {
        GeneratedPage parent = finalLifecycleSuperPage();
        compile(List.of(parent));
        var page = generator().generate("/extends.jsp", "<%@ page extends='fixture.SuperPage' session='false' %>body",
                PAGE_CLASS, GenerationOptions.DEFAULT);
        compile(List.of(page));
    }

    @Test
    void repeatedPageDirectivesMayDeclareTheSameSuperclass() throws Exception {
        GeneratedPage parent = finalLifecycleSuperPage();
        compile(List.of(parent));
        var page = generator().generate("/duplicate-extends.jsp",
                "<%@ page extends='fixture.SuperPage' session='false' %><%@ page extends='fixture.SuperPage' %>body",
                PAGE_CLASS, GenerationOptions.DEFAULT);
        compile(List.of(page));
    }

    @Test
    void wildcardPropertiesGenerateStaticParameterToSetterCalls() throws Exception {
        prepareBean();
        assertEquals("Ada:7", render("<%@ page session='false' %><jsp:useBean id='bean' class='fixture.Bean'/>"
                + "<jsp:setProperty name='bean' property='*'/><jsp:getProperty name='bean' property='name'/>"
                + ":<jsp:getProperty name='bean' property='count'/>",
                "request.parameters.put(\"name\", new String[]{\"Ada\"}); request.parameters.put(\"count\", new String[]{\"7\"});", ""));
    }

    @Test
    void dynamicElementsCaptureAttributesAndRenderTheirBody() throws Exception {
        String jsp = "<%@ page session='false' %><jsp:element name='${param.tag}'>"
                + "<jsp:attribute name='class'>a & b</jsp:attribute><jsp:body>Hello ${param.q}</jsp:body></jsp:element>";
        assertEquals("<h1 class=\"a &amp; b\">Hello Ada</h1>", render(jsp,
                "request.parameters.put(\"tag\", new String[]{\"h1\"}); request.parameters.put(\"q\", new String[]{\"Ada\"});", ""));
    }

    @Test
    void dynamicElementEvaluatesXmlSyntaxScriptletExpressionsInItsName() throws Exception {
        String xml = "<root xmlns:jsp='" + PageParser.JSP_NAMESPACE + "'>"
                + "<jsp:directive.page session='false'/><jsp:element name='%= \"h2\" %'>body</jsp:element></root>";
        var page = generator().generate("/fixture.jspx", xml, PAGE_CLASS, GenerationOptions.DEFAULT);
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?><root><h2>body</h2></root>", run(page, "", ""));
    }

    @Test
    void cachedExpressionsDoNotCaptureTheFirstRequest() throws Exception {
        assertEquals("Ada|Grace", render("<%@ page session='false' %>${requestScope.name}",
                "request.setAttribute(\"name\", \"Ada\");", """
                var secondRequest = new ServletDoubles.Request();
                secondRequest.setAttribute("name", "Grace");
                var secondResponse = new ServletDoubles.Response();
                page._jspService(secondRequest, secondResponse);
                response.output.append("|").append(secondResponse.output.toString());
                """));
    }

    @Test
    void inheritedAccessorsAndBooleanGettersAreSelectedFromClassFiles() throws Exception {
        compile(List.of(new GeneratedPage("fixture.Parent", """
                package fixture;
                public class Parent {
                    public boolean isEnabled() { return true; }
                    public boolean getEnabled() { return false; }
                }
                """, Map.of()), new GeneratedPage("fixture.Child", "package fixture; public class Child extends Parent {}", Map.of())));
        assertEquals("true", render("<%@ page session='false' %><jsp:useBean id='bean' class='fixture.Child'/>"
                + "<jsp:getProperty name='bean' property='enabled'/>", "", ""));
    }

    @Test
    void sharedScopeBeansCreateOnceAndSkipBodiesWhenReused() throws Exception {
        prepareBean();
        for (String scope : List.of("session", "application")) {
            String jsp = "<jsp:useBean id='bean' class='fixture.Bean' scope='" + scope + "'>"
                    + "<jsp:setProperty name='bean' property='count' value='7'/>created:"
                    + "</jsp:useBean><jsp:getProperty name='bean' property='count'/>";
            var generated = generator().generate("/shared.jsp", jsp, PAGE_CLASS, GenerationOptions.DEFAULT);
            assertTrue(generated.source().contains("new fixture.Bean()"));
            assertEquals("created:7", run(generated, "", """
                    var repeated = new ServletDoubles.Response();
                    page.service(request, repeated);
                    if (!repeated.output.toString().equals("7")) throw new AssertionError(repeated.output);
                    """));
        }
    }

    @Test
    void concurrentSharedScopeServicesConstructAndRunOneBody() throws Exception {
        prepareSharedBean();
        for (String scope : List.of("session", "application")) {
            int scopeNumber = scope.equals("session") ? 3 : 4;
            String jsp = "<jsp:useBean id='bean' class='fixture.SharedBean' scope='" + scope + "'>"
                    + "<% if (((java.util.concurrent.locks.ReentrantLock) ((io.vidocq.ibarra.spi.BeanScopeContext) pageContext).beanScopeLock(" + scopeNumber + ")).isHeldByCurrentThread()) throw new AssertionError(\"Body holds scope lock\");"
                    + "fixture.SharedBean.bodies.incrementAndGet(); %>created</jsp:useBean>";
            assertEquals("created", render(jsp, "", """
                    var sharedSession = request.session;
                    if (%d == 3) sharedSession.removeAttribute("bean"); else context.removeAttribute("bean");
                    fixture.SharedBean.constructions.set(0); fixture.SharedBean.bodies.set(0);
                    var ready = new java.util.concurrent.CountDownLatch(16);
                    var start = new java.util.concurrent.CountDownLatch(1);
                    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                        var results = new java.util.ArrayList<java.util.concurrent.Future<String>>();
                        for (int i = 0; i < 16; i++) results.add(executor.submit(() -> {
                            var concurrentRequest = new ServletDoubles.Request(); concurrentRequest.session = sharedSession;
                            var concurrentResponse = new ServletDoubles.Response();
                            ready.countDown(); start.await();
                            page.service(concurrentRequest, concurrentResponse);
                            return concurrentResponse.output.toString();
                        }));
                        if (!ready.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Workers not ready");
                        start.countDown();
                        int bodies = 0;
                        for (var result : results) if (result.get(10, java.util.concurrent.TimeUnit.SECONDS).equals("created")) bodies++;
                        if (bodies != 1 || fixture.SharedBean.constructions.get() != 1 || fixture.SharedBean.bodies.get() != 1)
                            throw new AssertionError("Duplicate shared creation/body");
                    }
                    """.formatted(scopeNumber)));
        }
    }

    @Test
    void sharedScopeFailuresReleaseLocksAndPreserveCreationSemantics() throws Exception {
        prepareSharedBean();
        for (String scope : List.of("session", "application")) {
            int scopeNumber = scope.equals("session") ? 3 : 4;
            String jsp = "<jsp:useBean id='bean' class='fixture.SharedBean' scope='" + scope + "'>"
                    + "<% fixture.SharedBean.bodies.incrementAndGet(); if (request.getAttribute(\"failBody\") != null) throw new IllegalArgumentException(\"body\"); %>created</jsp:useBean>";
            assertEquals("created", render(jsp, "", """
                    java.util.function.Consumer<Object> replace = value -> {
                        if (%d == 3) { if (value == null) request.session.removeAttribute("bean"); else request.session.setAttribute("bean", value); }
                        else { if (value == null) context.removeAttribute("bean"); else context.setAttribute("bean", value); }
                    };
                    replace.accept(null); fixture.SharedBean.fail = true; fixture.SharedBean.bodies.set(0);
                    try { page.service(request, new ServletDoubles.Response()); throw new AssertionError("Constructor succeeded"); }
                    catch (java.io.IOException expected) { if (!expected.getMessage().equals("constructor")) throw expected; }
                    if (fixture.SharedBean.bodies.get() != 0) throw new AssertionError("Failed constructor ran body");
                    if (%d == 3 ? request.session.getAttribute("bean") != null : context.getAttribute("bean") != null)
                        throw new AssertionError("Failed constructor published bean");
                    fixture.SharedBean.fail = false;
                    replace.accept("wrong type");
                    try { page.service(request, new ServletDoubles.Response()); throw new AssertionError("Wrong type accepted"); }
                    catch (ClassCastException expected) { }
                    replace.accept(null);
                    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                        executor.submit(() -> { page.service(request, new ServletDoubles.Response()); return null; })
                            .get(5, java.util.concurrent.TimeUnit.SECONDS);
                    }
                    if (fixture.SharedBean.bodies.get() != 1) throw new AssertionError("Retry did not run body");
                    replace.accept(null); request.setAttribute("failBody", true); fixture.SharedBean.bodies.set(0);
                    try { page.service(request, new ServletDoubles.Response()); throw new AssertionError("Body succeeded"); }
                    catch (IllegalArgumentException expected) { if (!expected.getMessage().equals("body")) throw expected; }
                    request.removeAttribute("failBody");
                    page.service(request, new ServletDoubles.Response());
                    if (fixture.SharedBean.bodies.get() != 1) throw new AssertionError("Published bean body was retried");
                    """.formatted(scopeNumber, scopeNumber)));
        }
    }

    private void prepareSharedBean() throws Exception {
        compile(List.of(new GeneratedPage("fixture.SharedBean", """
                package fixture;
                public class SharedBean {
                    public static final java.util.concurrent.atomic.AtomicInteger constructions = new java.util.concurrent.atomic.AtomicInteger();
                    public static final java.util.concurrent.atomic.AtomicInteger bodies = new java.util.concurrent.atomic.AtomicInteger();
                    public static volatile boolean fail;
                    public SharedBean() throws java.io.IOException {
                        constructions.incrementAndGet();
                        if (fail) throw new java.io.IOException("constructor");
                    }
                }
                """, Map.of())));
    }

    @Test
    void missingTypeOnlyBeansPreserveInstantiationExceptionInEveryScope() throws Exception {
        prepareBean();
        for (String scope : List.of("page", "request", "session", "application")) {
            int number = List.of("page", "request", "session", "application").indexOf(scope) + 1;
            String jsp = "<% if (request.getAttribute(\"skipSeed\") == null) pageContext.setAttribute(\"bean\", new fixture.Bean(), " + number + "); %>"
                    + "<jsp:useBean id='bean' type='fixture.Bean' scope='" + scope + "'/><jsp:getProperty name='bean' property='count'/>";
            assertEquals("0", render(jsp, "", """
                    request.setAttribute("skipSeed", true);
                    request.removeAttribute("bean"); request.session.removeAttribute("bean"); context.removeAttribute("bean");
                    try { page.service(request, new ServletDoubles.Response()); throw new AssertionError("Missing bean accepted"); }
                    catch (ServletException expected) { if (!(expected.getCause() instanceof InstantiationException)) throw expected; }
                    request.removeAttribute("skipSeed");
                    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                        executor.submit(() -> { page.service(request, new ServletDoubles.Response()); return null; })
                            .get(5, java.util.concurrent.TimeUnit.SECONDS);
                    }
                    """));
        }
    }

    @Test
    void xmlDirectiveIncludesAreExpandedBeforeNormalization() throws Exception {
        String namespace = PageParser.JSP_NAMESPACE;
        var options = new GenerationOptions(List.of(), Map.of("/part.jspx", "<jsp:text xmlns:jsp='" + namespace + "'>included</jsp:text>"));
        var generated = generator().generate("/main.jspx", "<jsp:root xmlns:jsp='" + namespace
                + "' version='4.0'><jsp:directive.page session='false'/><jsp:directive.include file='part.jspx'/></jsp:root>",
                PAGE_CLASS, options);
        assertEquals("included", run(generated, "", ""));
    }

    @Test
    void cyclicIncludeIsDetectedWithoutRequiringTheRootInTheSourceMap() {
        var options = new GenerationOptions(List.of(), Map.of("/part.jspf", "<%@ include file='main.jsp' %>"));
        var failure = assertThrows(PageParseException.class, generation("/main.jsp",
                "<%@ include file='part.jspf' %>", options));
        assertTrue(failure.getMessage().contains("Recursive"));
    }

    @Test
    void strictGroupPolicyRejectsMissingIdentifiersAtEvaluation() throws Exception {
        var options = new GenerationOptions(List.of(new JspPropertyGroup(List.of("*.jsp"),
                Map.of("error-on-el-not-found", "true"), List.of(), List.of())), Map.of()).withStartupEl(true);
        var generated = generator().generate("/strict.jsp", "<%@ page session='false' errorPage='/part.jsp' %>${missing}", PAGE_CLASS, options);
        assertEquals("FORWARDED", run(generated, dispatchSetup(),
                "if (!(request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) instanceof jakarta.el.PropertyNotFoundException)) throw new AssertionError();"));
    }

    @Test
    void pageDirectiveOverridesTheConfiguredMissingIdentifierDefault() throws Exception {
        var options = new GenerationOptions(List.of(new JspPropertyGroup(List.of("*.jsp"),
                Map.of("error-on-el-not-found", "true"), List.of(), List.of())), Map.of()).withStartupEl(true);
        var generated = generator().generate("/override.jsp", "<%@ page session='false' errorOnELNotFound='false' %>A${missing}B", PAGE_CLASS, options);
        assertEquals("AB", run(generated, "", ""));
        assertEquals("FORWARDED", render("<%@ page session='false' errorOnELNotFound='true' errorPage='/part.jsp' %>${missing}",
                dispatchSetup(), "if (!(request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) instanceof jakarta.el.PropertyNotFoundException)) throw new AssertionError();"));
    }

    @Test
    void undeclaredNamespacesFailOnlyWhenConfiguredAndKeepTheirSourceLocation() {
        var strict = new GenerationOptions(List.of(new JspPropertyGroup(List.of("*.jsp"),
                Map.of("error-on-undeclared-namespace", "true"), List.of(), List.of())), Map.of());
        var error = assertThrows(PageParseException.class, generation("/names.jsp", "first\n<x:item/>", strict));
        assertTrue(error.getMessage().contains("Undeclared namespace prefix: x"));
        assertEquals(2, error.location().line());
        assertEquals(1, error.location().column());
        assertDoesNotThrow(() -> generator().generate("/names.jsp", "<x:item/>", PAGE_CLASS, GenerationOptions.DEFAULT));
        assertDoesNotThrow(() -> generator().generate("/names.jsp", "<%-- <x:item/> --%><p>plain</p>", PAGE_CLASS, strict));
        assertDoesNotThrow(() -> generator().generate("/names.jsp", "${'<x:item/>'}", PAGE_CLASS, strict.withStartupEl(true)));
    }

    @Test
    void taglibDeclarationsInIncludesBindLaterTagsAcrossTheTranslationUnit() {
        var strict = new GenerationOptions(List.of(new JspPropertyGroup(List.of("*.jsp"),
                Map.of("error-on-undeclared-namespace", "true"), List.of(), List.of())),
                Map.of("/tags.jspf", "<%@ taglib prefix='c' uri='urn:custom' %>"));
        var error = assertThrows(PageParseException.class, generation("/main.jsp",
                "<%@ include file='tags.jspf' %><c:item/>", strict));
        assertTrue(error.getMessage().contains("No build-time tag library"));
        assertEquals("/main.jsp", error.location().source());
        var later = assertThrows(PageParseException.class, generation("/main.jsp",
                "<c:item/><%@ include file='tags.jspf' %>", strict));
        assertTrue(later.getMessage().contains("Taglib directive must precede"));
    }

    @Test
    void invalidBooleanConfigurationFailsWithPageDiagnosticsEvenWithoutAffectedNodes() {
        for (String name : List.of("el-ignored", "is-xml", "scripting-invalid",
                "deferred-syntax-allowed-as-literal", "trim-directive-whitespaces",
                "error-on-el-not-found", "error-on-undeclared-namespace")) {
            var options = new GenerationOptions(List.of(new JspPropertyGroup(List.of("*.jsp"),
                    Map.of(name, "yes"), List.of(), List.of())), Map.of());
            var error = assertThrows(PageParseException.class,
                    generation("/policy.jsp", "plain", options), name);
            assertEquals(new SourceLocation("/policy.jsp", 1, 1), error.location());
            assertTrue(error.getMessage().contains(name));
        }
        for (String name : List.of("session", "autoFlush", "isErrorPage", "isELIgnored",
                "deferredSyntaxAllowedAsLiteral", "trimDirectiveWhitespaces", "errorOnELNotFound")) {
            var error = assertThrows(PageParseException.class, generation("/policy.jsp",
                    "first\n<%@ page " + name + "='yes' %>", GenerationOptions.DEFAULT), name);
            assertEquals(new SourceLocation("/policy.jsp", 2, 1), error.location());
            assertTrue(error.getMessage().contains(name));
        }
    }

    @Test
    void strictNamespaceErrorsRetainIncludeOriginsAndProcessHtmlComments() {
        var strict = new GenerationOptions(List.of(new JspPropertyGroup(List.of("*.jsp"),
                Map.of("error-on-undeclared-namespace", "true"), List.of(), List.of())),
                Map.of("/fragment.jspf", "first\n<x:item/>"));
        var error = assertThrows(PageParseException.class, generation("/main.jsp",
                "<%@ include file='fragment.jspf' %>", strict));
        assertEquals(new SourceLocation("/fragment.jspf", 2, 1), error.location());
        assertThrows(PageParseException.class, generation("/main.jsp",
                "<!-- <x:item/> -->", strict));
        assertDoesNotThrow(() -> generator().generate("/main.jsp", "<http://example>", PAGE_CLASS, strict));
    }

    @Test
    void pageEncodingIsFileLocalAndOnlyTheRequestedPageSetsResponseEncoding() throws Exception {
        var options = new GenerationOptions(List.of(new JspPropertyGroup(List.of("*.jspf"),
                Map.of("page-encoding", "ISO-8859-1"), List.of(), List.of())),
                Map.of("/part.jspf", "<%@ page pageEncoding='latin1' %>é"));
        var generated = generator().generate("/main.jsp",
                "<%@ page pageEncoding='UTF-8' %>A<%@ include file='part.jspf' %>", PAGE_CLASS, options);
        assertEquals("Aé", run(generated, "", "if (!response.contentType.equals(\"text/html;charset=UTF-8\")) throw new AssertionError(response.contentType);"));
        var withoutRootEncoding = generator().generate("/main.jsp", "<%@ include file='part.jspf' %>", PAGE_CLASS, options);
        assertEquals("é", run(withoutRootEncoding, "", "if (response.contentType.contains(\"charset\")) throw new AssertionError(response.contentType);"));
    }

    @Test
    void duplicateAndUnknownEncodingsFailAtTheOwningDirective() {
        var duplicate = assertThrows(PageParseException.class, generation("/main.jsp",
                "<%@ page pageEncoding='UTF-8' %>\n<%@ page pageEncoding='UTF-8' %>", GenerationOptions.DEFAULT));
        assertEquals(new SourceLocation("/main.jsp", 2, 1), duplicate.location());
        assertTrue(duplicate.getMessage().contains("once per file"));
        var options = new GenerationOptions(List.of(), Map.of("/part.jspf", "first\n<%@ page pageEncoding='not-an-encoding' %>"));
        var unknown = assertThrows(PageParseException.class, generation("/main.jsp",
                "<%@ include file='part.jspf' %>", options));
        assertEquals(new SourceLocation("/part.jspf", 2, 1), unknown.location());
    }

    @Test
    void xmlResponseDefaultsIgnorePageSourceEncoding() throws Exception {
        var generated = generator().generate("/main.jspx", "<jsp:root xmlns:jsp='" + PageParser.JSP_NAMESPACE
                + "' version='4.0'><jsp:directive.page pageEncoding='ISO-8859-1'/><jsp:text>é</jsp:text></jsp:root>",
                PAGE_CLASS, GenerationOptions.DEFAULT);
        assertEquals("é", run(generated, "", "if (!response.contentType.equals(\"text/xml;charset=UTF-8\")) throw new AssertionError(response.contentType);"));
    }

    @Test
    void mixedSyntaxIncludesUseEachFilesWhitespaceEscapesAndExpressions() throws Exception {
        String xml = "<jsp:root xmlns:jsp='" + PageParser.JSP_NAMESPACE + "' version='4.0'>\n"
                + "<jsp:text> X&amp;${2} </jsp:text><jsp:directive.include file='tail.jspf'/></jsp:root>";
        var options = new GenerationOptions(List.of(), Map.of("/part.jspx", xml,
                "/tail.jspf", " T<\\% ${3} ")).withStartupEl(true);
        var generated = generator().generate("/main.jsp", "A<%@ include file='part.jspx' %>B", PAGE_CLASS, options);
        assertEquals("A X&2  T<% 3 B", run(generated, "", ""));
        var reverse = generator().generate("/main.jspx", "<jsp:root xmlns:jsp='" + PageParser.JSP_NAMESPACE
                + "' version='4.0'>\n<jsp:directive.include file='tail.jspf'/></jsp:root>", PAGE_CLASS, options);
        assertEquals(" T<% 3 ", run(reverse, "", ""));
    }

    @Test
    void mixedSyntaxPreludesAndCodasSharePageDirectives() throws Exception {
        var group = new JspPropertyGroup(List.of("*.jsp"), Map.of(), List.of("/prelude.jspx"), List.of("/coda.jspx"));
        String root = "<jsp:root xmlns:jsp='" + PageParser.JSP_NAMESPACE + "' version='4.0'>";
        var options = new GenerationOptions(List.of(group), Map.of(
                "/prelude.jspx", root + "<jsp:directive.page isELIgnored='true'/><jsp:text>P</jsp:text></jsp:root>",
                "/coda.jspx", root + "<jsp:text>C</jsp:text></jsp:root>"));
        assertEquals("P${1}C", run(generator().generate("/main.jsp", "${1}", PAGE_CLASS, options), "", ""));
    }

    @Test
    void malformedIncludedXmlKeepsTheIncludedResourcesLocation() {
        var options = new GenerationOptions(List.of(), Map.of("/part.jspx", "<root>\n<bad></root>"));
        var error = assertThrows(PageParseException.class, generation("/main.jsp",
                "<%@ include file='part.jspx' %>", options));
        assertEquals("/part.jspx", error.location().source());
        assertEquals(2, error.location().line());
    }

    @Test
    void invalidBufferAndUnknownGroupKeysHaveTranslationDiagnostics() {
        var options = new GenerationOptions(List.of(), Map.of("/part.jspf", "first\n<%@ page buffer='huge' %>"));
        var buffer = assertThrows(PageParseException.class, generation("/main.jsp",
                "<%@ include file='part.jspf' %>", options));
        assertEquals(new SourceLocation("/part.jspf", 2, 1), buffer.location());
        var typo = new GenerationOptions(List.of(new JspPropertyGroup(List.of("*.jsp"),
                Map.of("error-on-el-not-fond", "true"), List.of(), List.of())), Map.of());
        var error = assertThrows(PageParseException.class, generation("/main.jsp", "plain", typo));
        assertTrue(error.getMessage().contains("error-on-el-not-fond"));
    }

    @Test
    void standardTaglibCannotRedefineAnEnclosingXmlNamespace() {
        var options = new GenerationOptions(List.of(), Map.of("/tags.jspf", "<%@ taglib prefix='c' uri='urn:other' %>"));
        var error = assertThrows(PageParseException.class, generation("/main.jspx",
                "<jsp:root xmlns:jsp='" + PageParser.JSP_NAMESPACE + "' xmlns:c='urn:existing' version='4.0'>"
                + "<jsp:directive.include file='tags.jspf'/></jsp:root>", options));
        assertEquals("/tags.jspf", error.location().source());
        assertTrue(error.getMessage().contains("Conflicting taglib prefix"));
        var same = new GenerationOptions(List.of(), Map.of("/tags.jspf", "<%@ taglib prefix='c' uri='urn:existing' %>"));
        assertDoesNotThrow(() -> generator().generate("/main.jspx", "<jsp:root xmlns:jsp='" + PageParser.JSP_NAMESPACE
                + "' xmlns:c='urn:existing' version='4.0'><jsp:directive.include file='tags.jspf'/></jsp:root>", PAGE_CLASS, same));
    }

    @Test
    void mixedIncludesDistinguishXmlMarkupFromStandardCustomTags() throws Exception {
        var options = new GenerationOptions(List.of(), Map.of("/part.jspx", "<p>text</p>",
                "/tags.jspf", "<%@ taglib prefix='c' uri='urn:custom' %><c:item/>"));
        assertEquals("<p>text</p>", run(generator().generate("/main.jsp", "<%@ include file='part.jspx' %>", PAGE_CLASS, options), "", ""));
        var error = assertThrows(PageParseException.class, generation("/main.jspx",
                "<j:root xmlns:j='" + PageParser.JSP_NAMESPACE + "' version='4.0'><j:directive.include file='tags.jspf'/></j:root>", options));
        assertTrue(error.getMessage().contains("No build-time tag library"));
    }

    @Test
    void xmlIncludeMayDeclareItsJspNamespaceLocally() throws Exception {
        var options = new GenerationOptions(List.of(), Map.of("/part.jspf", "included"));
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?><p>included</p>", run(generator().generate("/main.jspx", "<p><j:directive.include xmlns:j='"
                + PageParser.JSP_NAMESPACE + "' file='part.jspf'/></p>", PAGE_CLASS, options), "", ""));
    }

    @Test
    void defaultJspNamespacesStillValidateFileLocalEncodings() {
        var error = assertThrows(PageParseException.class, generation("/main.jspx",
                "<root xmlns='" + PageParser.JSP_NAMESPACE + "' version='4.0'>\n"
                + "<directive.page pageEncoding='not-an-encoding'/></root>", GenerationOptions.DEFAULT));
        assertEquals(new SourceLocation("/main.jspx", 2, 1), error.location());
        String prefix = "<root xmlns='" + PageParser.JSP_NAMESPACE + "' version='4.0'>\n";
        for (String directives : List.of("<directive.page pageEncoding='not-an-encoding'/>",
                "<directive.page pageEncoding='UTF-8'/><directive.page pageEncoding='UTF-8'/>")) {
            var options = new GenerationOptions(List.of(), Map.of("/part.jspx", prefix + directives + "</root>"));
            var included = assertThrows(PageParseException.class, generation("/main.jsp",
                    "<%@ include file='part.jspx' %>", options));
            assertEquals("/part.jspx", included.location().source());
            assertEquals(2, included.location().line());
        }
    }

    @Test
    void xmlAttributeEscapesAreNotInterpretedAsStandardSyntaxEscapes() throws Exception {
        var options = new GenerationOptions(List.of(), Map.of("/part.jspx", "<p title='&lt;\\%'/>") );
        assertEquals("<p title=\"&lt;\\%\"/>", run(generator().generate("/main.jsp",
                "<%@ include file='part.jspx' %>", PAGE_CLASS, options), "", ""));
    }

    @Test
    void ordinaryXmlAttributeValuesKeepWhitespace() throws Exception {
        var generated = generator().generate("/main.jspx", "<p title='  '/>", PAGE_CLASS, GenerationOptions.DEFAULT);
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?><p title=\"  \"/>", run(generated, "", ""));
    }

    @Test
    void byteSourcesDecodeEachIncludedResourceIndependently() throws Exception {
        var options = GenerationOptions.DEFAULT.withByteSources(Map.of("/part.jspf",
                "<%@ page pageEncoding='ISO-8859-1' %>é".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)));
        byte[] root = "<%@ page pageEncoding='UTF-8' %>你好<%@ include file='part.jspf' %>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("你好é", run(generator().generate("/main.jsp", root, PAGE_CLASS, options), "", ""));
    }

    @Test
    void standardBomIsRemovedAndSelectsTheResponseEncoding() throws Exception {
        byte[] text = "é".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] bytes = new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, text[0], text[1]};
        assertEquals("é", run(generator().generate("/main.jsp", bytes, PAGE_CLASS, GenerationOptions.DEFAULT), "",
                "if (!response.contentType.equals(\"text/html;charset=UTF-8\")) throw new AssertionError(response.contentType);"));
    }

    @Test
    void xmlPrologDecodesBytesWithoutChangingXmlResponseDefaults() throws Exception {
        byte[] bytes = "<?xml version='1.0' encoding='ISO-8859-1'?><p>é</p>".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?><p>é</p>", run(generator().generate("/main.jspx", bytes, PAGE_CLASS, GenerationOptions.DEFAULT), "",
                "if (!response.contentType.equals(\"text/xml;charset=UTF-8\")) throw new AssertionError(response.contentType);"));
    }

    @Test
    void byteSourceSnapshotsCannotBeChangedByCallers() {
        byte[] bytes = "text".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var options = GenerationOptions.DEFAULT.withByteSources(Map.of("/part.jspf", bytes));
        bytes[0] = 0;
        options.byteSources().get("/part.jspf")[0] = 0;
        assertEquals('t', options.byteSources().get("/part.jspf")[0]);
        var reconstructed = new GenerationOptions(options.propertyGroups(), options.sources(), options.allowStartupEl(), options.byteSources());
        assertEquals(options, reconstructed);
        assertEquals(options.hashCode(), reconstructed.hashCode());
        assertEquals(options, options.withStartupEl(false));
        var textual = new GenerationOptions(List.of(), Map.of("/part.jspf", "text"));
        var conflicting = Map.of("/part.jspf", bytes);
        assertThrows(IllegalArgumentException.class, () -> textual.withByteSources(conflicting));
    }

    @Test
    void xmlNamedAttributeWhitespaceIsPreservedWhenTrimIsDisabled() throws Exception {
        String source = "<jsp:root xmlns:jsp='" + PageParser.JSP_NAMESPACE + "' version='4.0'>"
                + "<jsp:directive.page session='false'/><jsp:element name='p'>"
                + "<jsp:attribute name='title' trim='false'>  </jsp:attribute></jsp:element></jsp:root>";
        assertEquals("<p title=\"  \"/>", render(source, "", ""));
    }

    @Test
    void namedAttributeTrimmingOnlyRemovesXmlWhitespace() throws Exception {
        assertEquals("<p title=\"\u2003\"/>", render("<%@ page session='false' %><jsp:element name='p'>"
                + "<jsp:attribute name='title'> \u2003 </jsp:attribute></jsp:element>", "", ""));
    }

    @Test
    void propertyActionsResolveTheCurrentScopedBeanAfterReplacement() throws Exception {
        prepareBean();
        String source = "<%@ page session='false' %><jsp:useBean id='bean' class='fixture.Bean'/>"
                + "<% bean.setName(\"old\"); var replacement = new fixture.Bean(); replacement.setName(\"new\");"
                + " pageContext.setAttribute(\"bean\", replacement); %><jsp:getProperty name='bean' property='name'/>:"
                + "<jsp:setProperty name='bean' property='count' value='9'/>"
                + "<%= ((fixture.Bean) pageContext.findAttribute(\"bean\")).getCount() %>";
        assertEquals("new:9", render(source, "", ""));
    }

    @Test
    void defaultElUsesBuildTimeClassesWhileStartupInterpretationRemainsAnExplicitPreview() {
        var compiled = generator().generate("/el.jsp", "${name}", PAGE_CLASS, GenerationOptions.DEFAULT);
        assertFalse(compiled.source().contains("createValueExpression"));
        var preview = generator().generate("/el.jsp", "${name}", PAGE_CLASS, GenerationOptions.DEFAULT.withStartupEl(true));
        assertTrue(preview.source().contains("createValueExpression"));
    }
    @Test void invalidCompiledElKeepsTheOriginalPageLocation() {
        var failure = assertThrows(PageParseException.class, generation("/bad-el.jsp", "before\n${1 + }", GenerationOptions.DEFAULT));
        assertTrue(failure.getMessage().contains("/bad-el.jsp:2:"));
    }
    @Test void defaultExplicitWildcardAndNestedImportsAreVisibleToEl() throws Exception {
        assertEquals("200|true|true", render("<%@ page session='false' import='java.util.*,java.util.Map.Entry' %>${HttpServletResponse.SC_OK}|${List != null}|${Entry != null}", "", ""));
    }
    @Test void implicitServletApiTypesAreVisibleToElWithoutExplicitPageImports() throws Exception {
        assertEquals("ASYNC|CONTEXT_ROOT", render("<%@ page session='false' %>${DispatcherType.ASYNC}|${MappingMatch.CONTEXT_ROOT}", "", ""));
    }
    @Test void typedDeferredTagAttributesPreserveValueAndMethodExpressions() throws Exception {
        compile(List.of(new GeneratedPage("fixture.AllowedDeferredValuesTag", """
                package fixture;
                public final class AllowedDeferredValuesTag extends jakarta.servlet.jsp.tagext.TagSupport {
                    private Object valueExpr;
                    private jakarta.el.MethodExpression methodExpr;
                    public void setLitExpr(String value) { }
                    public void setScriptExpr(String value) { }
                    public void setDollarExpr(String value) { }
                    public void setValueExpr(Object value) { valueExpr = value; }
                    public void setMethodExpr(jakarta.el.MethodExpression value) { methodExpr = value; }
                    @Override public int doStartTag() throws jakarta.servlet.jsp.JspException {
                        try {
                            var context = pageContext;
                            context.getOut().print((valueExpr instanceof jakarta.el.ValueExpression) + ":"
                                    + ((jakarta.el.ValueExpression) valueExpr).getValue(context.getELContext()) + ":"
                                    + (methodExpr instanceof jakarta.el.MethodExpression));
                        } catch (java.io.IOException failure) { throw new jakarta.servlet.jsp.JspException(failure); }
                        return SKIP_BODY;
                    }
                }
                """, Map.of())));
        var attributes = new LinkedHashMap<String, TagAttribute>();
        attributes.put("litExpr", new TagAttribute("litExpr", "java.lang.String", true, true, false));
        attributes.put("scriptExpr", new TagAttribute("scriptExpr", "java.lang.String", true, true, false));
        attributes.put("dollarExpr", new TagAttribute("dollarExpr", "java.lang.String", true, true, false));
        attributes.put("valueExpr", new TagAttribute("valueExpr", "java.lang.Object", true, true, false, "java.lang.Object", null));
        attributes.put("methodExpr", new TagAttribute("methodExpr", "java.lang.Object", true, true, false, null,
                new DeferredMethodSignature("boolean", List.of("java.lang.Object"))));
        var tag = new TagDescriptor("AllowedDeferredValues", "fixture.AllowedDeferredValuesTag", "JSP", attributes, false, false);
        var library = new TagLibraryDescriptor("/WEB-INF/el_jsp.tld", "urn:ibarra:test:el", List.of(),
                Map.of(tag.name(), tag), null, Map.of());
        var options = GenerationOptions.DEFAULT.withTagLibraries(Map.of(library.uri(), library));
        String source = "<%@ page session='false' %><%@ taglib prefix='test' uri='urn:ibarra:test:el' %>"
                + "<% pageContext.setAttribute(\"vexp\", \"value\", PageContext.PAGE_SCOPE); "
                + "pageContext.setAttribute(\"vect\", new java.util.Vector<>(), PageContext.PAGE_SCOPE); %>"
                + "<test:AllowedDeferredValues litExpr='literal' scriptExpr='<%= \"script\" %>' "
                + "dollarExpr='${vexp}' valueExpr='#{vexp}' methodExpr='#{vect.add}'/>";
        var generated = generatorWithRuntimeModulePath().generate("/allowed-deferred.jsp", source, PAGE_CLASS, options);
        assertEquals("true:value:true", run(generated, "", ""));
    }
    @Test void deferredExpressionsOnUndeclaredDynamicAttributesRemainValueExpressions() throws Exception {
        compile(List.of(new GeneratedPage("fixture.DynamicExpressionTag", """
                package fixture;
                public final class DynamicExpressionTag extends jakarta.servlet.jsp.tagext.SimpleTagSupport
                        implements jakarta.servlet.jsp.tagext.DynamicAttributes {
                    private Object deferred;
                    @Override public void setDynamicAttribute(String uri, String name, Object value) {
                        if (name.equals("deferred")) deferred = value;
                    }
                    @Override public void doTag() throws jakarta.servlet.jsp.JspException, java.io.IOException {
                        var expression = (jakarta.el.ValueExpression) deferred;
                        Object value = expression.getValue(getJspContext().getELContext());
                        getJspContext().getOut().print(value);
                    }
                }
                """, Map.of())));
        var tag = new TagDescriptor("dynamic", "fixture.DynamicExpressionTag", "empty", Map.of(), true, false);
        var library = new TagLibraryDescriptor("/WEB-INF/dynamic.tld", "urn:ibarra:test:dynamic", List.of(),
                Map.of(tag.name(), tag), null, Map.of());
        var options = GenerationOptions.DEFAULT.withTagLibraries(Map.of(library.uri(), library));
        String prefix = "<%@ page session='false' %><%@ taglib prefix='test' uri='urn:ibarra:test:dynamic' %>"
                + "<% pageContext.setAttribute(\"vexp\", \"value\", PageContext.PAGE_SCOPE); %>";
        for (String invocation : List.of("<test:dynamic deferred='#{vexp}'/>",
                "<test:dynamic><jsp:attribute name='deferred'>#{vexp}</jsp:attribute></test:dynamic>")) {
            var generated = generatorWithRuntimeModulePath().generate("/dynamic-el.jsp", prefix + invocation, PAGE_CLASS, options);
            assertEquals("value", run(generated, "", ""));
        }
    }
    @Test void tagFileDynamicAttributesWithAUriAreOmittedFromTheDynamicMap() throws Exception {
        compile(List.of(new GeneratedPage("fixture.DynamicMapTag", """
                package fixture;
                public final class DynamicMapTag extends jakarta.servlet.jsp.tagext.SimpleTagSupport
                        implements jakarta.servlet.jsp.tagext.DynamicAttributes {
                    private final java.util.Map<String, Object> attributes = new java.util.LinkedHashMap<>();
                    @Override public void setDynamicAttribute(String uri, String name, Object value) {
                        attributes.put(name, value);
                    }
                    @Override public void doTag() throws jakarta.servlet.jsp.JspException, java.io.IOException {
                        getJspContext().getOut().print(attributes.get("d1") + ":" + attributes.containsKey("my:d2"));
                    }
                }
                """, Map.of())));
        var tag = new TagDescriptor("dynamicAttributesNoUri", "fixture.DynamicMapTag", "scriptless", Map.of(), true, false, true);
        var library = new TagLibraryDescriptor("/WEB-INF/tags", "urn:ibarra:test:tagdir", List.of(),
                Map.of(tag.name(), tag), null, Map.of());
        var options = GenerationOptions.DEFAULT.withTagLibraries(Map.of(library.uri(), library));
        var generated = generator().generate("/dynamic-attributes.jsp",
                "<%@ page session='false' %><%@ taglib prefix='my' uri='urn:ibarra:test:tagdir' %>"
                        + "<my:dynamicAttributesNoUri d1='Test PASSED' my:d2='Test FAILED'/>", PAGE_CLASS, options);
        assertEquals("Test PASSED:false", run(generated, "", ""));
    }
    @Test void scopedValuesShadowImportedClasses() throws Exception {
        assertEquals("scope", render("<%@ page session='false' import='java.util.Map.Entry' %>${Entry}",
                "request.setAttribute(\"Entry\", \"scope\");", ""));
    }
    @Test void wildcardAmbiguityIsLazyAndExplicitTypesTakePrecedence() throws Exception {
        compile(List.of(new GeneratedPage("fixture.one.Dup", "package fixture.one; public class Dup {}", Map.of()),
                new GeneratedPage("fixture.two.Dup", "package fixture.two; public class Dup {}", Map.of()),
                new GeneratedPage("fixture.one.Unused", "package fixture.one; public class Unused { static { if(true) throw new AssertionError(\"Unused class initialized\"); } }", Map.of())));
        String imports = "<%@ page session='false' import='fixture.one.*,fixture.two.*' %>";
        assertEquals("safe", render(imports + "safe", "", ""));
        assertEquals("true|true", render(imports + "<%@ page import='fixture.one.Dup' %>${Dup != null}|${Unused != null}", "", ""));
        var failure = assertThrows(AssertionError.class, () -> render(imports + "${Dup != null}", "", ""));
        assertTrue(failure.getMessage().contains("Ambiguous EL import"));
    }
    @Test void staticMemberPageImportsFailTranslation() {
        assertThrows(PageParseException.class, generation("/bad.jsp", "<%@ page import='static java.lang.Math.PI' %>", GenerationOptions.DEFAULT));
    }
    @Test void unavailableImportedTypesKeepDirectiveOrigins() {
        var failure = assertThrows(PageParseException.class, generation("/missing-import.jsp", "before\n<%@ page import='java.util.DoesNotExist' %>", GenerationOptions.DEFAULT));
        assertTrue(failure.getMessage().contains("/missing-import.jsp:2:"));
    }

    @Test
    void tagFileVariablesSynchronizeAtBeginEndAndNestedScopesDuringBodyInvocation() throws Exception {
        String prefix = "<% pageContext.setAttribute(\"x\", \"1\"); %>${x}|";
        String suffix = "|${x}";
        assertEquals("1|[2]|4", renderTagFileVariablePage(TagVariableDescriptor.AT_BEGIN, "x", true,
                tagFileVariablePage(prefix + "<t:variable variable='x'>[${x}]</t:variable>" + suffix)));
        assertEquals("1|[1]|4", renderTagFileVariablePage(TagVariableDescriptor.AT_END, "x", true,
                tagFileVariablePage(prefix + "<t:variable variable='x'>[${x}]</t:variable>" + suffix)));
        assertEquals("1|[2]|1", renderTagFileVariablePage(TagVariableDescriptor.NESTED, "x", true,
                tagFileVariablePage(prefix + "<t:variable variable='x'>[${x}]</t:variable>" + suffix)));
    }

    @Test
    void tagFileVariableRemovalsAreVisibleToEachBodyInvocation() throws Exception {
        String page = tagFileVariablePage("<% pageContext.setAttribute(\"x\", \"2\"); %>${x}|"
                + "<t:variable variable='x' removeAfterBody='true'>[${x}]</t:variable>|${x}");
        assertEquals("2|[1][]|", renderTagFileVariablePage(TagVariableDescriptor.AT_BEGIN, "x", true, page));
        assertEquals("2|[2][2]|", renderTagFileVariablePage(TagVariableDescriptor.AT_END, "x", true, page));
        assertEquals("2|[1][]|2", renderTagFileVariablePage(TagVariableDescriptor.NESTED, "x", true, page));
    }

    @Test
    void tagFileVariablesSynchronizeWithDeclaredAndExistingScriptletVariables() throws Exception {
        for (boolean declare : List.of(true, false)) {
            String declaration = declare ? "" : "<%! String declared; %>";
            String page = tagFileVariablePage(declaration
                    + "<t:variable variable='declared'/><%= declared %>");
            assertEquals("4", renderTagFileVariablePage(TagVariableDescriptor.AT_END, "declared", declare, page));
        }
    }

    private PageGenerator generator() { return new PageGenerator(List.of(directory)); }
    /** Defers one generation, so that an expected failure can only come from {@code generate}. */
    private Executable generation(String path, String source, GenerationOptions options) {
        var pageGenerator = generator();
        return () -> pageGenerator.generate(path, source, PAGE_CLASS, options);
    }

    private PageGenerator generatorWithRuntimeModulePath() {
        var beanModulePath = new ArrayList<Path>();
        beanModulePath.add(directory);
        Arrays.stream(modulePath().split(java.util.regex.Pattern.quote(File.pathSeparator)))
                .filter(path -> !path.isBlank()).map(Path::of).forEach(beanModulePath::add);
        return new PageGenerator(beanModulePath);
    }

    @Test
    void beanInfoEditorsConvertLiteralsAndParametersButNotElValues() throws Exception {
        preparePropertyEditor(false);
        String source = "<%@ page session='false' %><jsp:useBean id='bean' class='fixture.EditedBean'/>"
                + "<jsp:setProperty name='bean' property='name' value='literal'/><jsp:getProperty name='bean' property='name'/>:"
                + "<jsp:setProperty name='bean' property='name' param='q'/><jsp:getProperty name='bean' property='name'/>:"
                + "<jsp:setProperty name='bean' property='name' value='${param.q}'/><jsp:getProperty name='bean' property='name'/>:"
                + "<jsp:setProperty name='bean' property='name' value='<%= \"scriptlet\" %>'/><jsp:getProperty name='bean' property='name'/>";
        var generated = generator().generate("/editor.jsp", source, PAGE_CLASS, GenerationOptions.DEFAULT.withStartupEl(true));
        assertTrue(generated.source().contains("new fixture.NameEditor()"));
        assertFalse(generated.source().contains("Introspector"));
        assertFalse(generated.source().contains("PropertyEditorManager"));
        assertNull(System.getProperty("ibarra.editor.beaninfo.initialized"));
        assertEquals("edited:literal:edited:query:query:scriptlet", run(generated,
                "request.parameters.put(\"q\", new String[]{\"query\"});",
                "if (System.getProperty(\"ibarra.editor.beaninfo.initialized\") != null) throw new AssertionError(\"BeanInfo was initialized\");"));
    }

    @Test
    void missingOrEmptyRequestParametersDoNotInvokePropertyEditors() throws Exception {
        preparePropertyEditor(false);
        assertEquals("unset", render("<%@ page session='false' %><jsp:useBean id='bean' class='fixture.EditedBean'/>"
                + "<jsp:setProperty name='bean' property='name' param='q'/><jsp:getProperty name='bean' property='name'/>",
                "request.parameters.put(\"q\", new String[]{\"\"});", ""));
    }

    @Test
    void dynamicBeanInfoIsRejectedAtThePropertyActionInsteadOfLoadingApplicationCode() throws Exception {
        preparePropertyEditor(true);
        var failure = assertThrows(PageParseException.class, generation("/dynamic.jsp",
                "<jsp:useBean id='bean' class='fixture.EditedBean'/>\n<jsp:setProperty name='bean' property='name' value='x'/>", GenerationOptions.DEFAULT));
        assertEquals(2, failure.location().line());
        assertTrue(failure.getMessage().contains("build-time BeanInfo"));
        assertNull(System.getProperty("ibarra.editor.beaninfo.initialized"));
    }

    @Test
    void editorFailuresUseNullForEmptyLiteralsAndPageErrorHandlingForNonemptyValues() throws Exception {
        preparePropertyEditor(false);
        String bean = "<jsp:useBean id='bean' class='fixture.EditedBean'/>";
        assertEquals("null", render("<%@ page session='false' %>" + bean
                + "<jsp:setProperty name='bean' property='name' value=''/><jsp:getProperty name='bean' property='name'/>", "", ""));
        assertEquals("FORWARDED", render("<%@ page session='false' errorPage='/part.jsp' %>" + bean
                + "<jsp:setProperty name='bean' property='name' value='reject'/>", dispatchSetup(), ""));
    }

    @Test
    void beanInfoNeedsAnAccessibleConstructorAndValidUnusedDescriptors() throws Exception {
        for (String shape : List.of("private-constructor", "invalid-descriptor")) {
            preparePropertyEditor(shape);
            var failure = assertThrows(PageParseException.class, generation("/metadata.jsp",
                    "<jsp:useBean id='bean' class='fixture.EditedBean'/><jsp:setProperty name='bean' property='name' value='x'/>", GenerationOptions.DEFAULT));
            assertTrue(failure.getMessage().contains(shape.equals("private-constructor")
                    ? "public no-argument constructor" : "missing"), failure.getMessage());
        }
    }

    @Test
    void inheritedExplicitBeanInfoIsDiagnosedInsteadOfSilentlyUsingElCoercion() throws Exception {
        preparePropertyEditor(false);
        compile(List.of(new GeneratedPage("fixture.EditedChild", """
                package fixture;
                public class EditedChild extends EditedBean { }
                """, Map.of()), new GeneratedPage("fixture.EditedGrandchild", """
                package fixture;
                public class EditedGrandchild extends EditedChild { }
                """, Map.of())));
        for (String type : List.of("fixture.EditedChild", "fixture.EditedGrandchild")) {
            var failure = assertThrows(PageParseException.class, generation("/inherited.jsp",
                    "<jsp:useBean id='bean' class='" + type + "'/>\n<jsp:setProperty name='bean' property='name' value='x'/>", GenerationOptions.DEFAULT));
            assertEquals(2, failure.location().line());
            assertTrue(failure.getMessage().contains("inherited build-time BeanInfo"), failure.getMessage());
            assertTrue(failure.getMessage().contains("fixture.EditedBeanBeanInfo"), failure.getMessage());
        }
        assertNull(System.getProperty("ibarra.editor.beaninfo.initialized"));
    }

    private void preparePropertyEditor(boolean dynamic) throws Exception {
        preparePropertyEditor(dynamic ? "dynamic" : "literal");
    }

    private void preparePropertyEditor(String shape) throws Exception {
        compile(List.of(new GeneratedPage("fixture.EditedBean", """
                package fixture;
                public class EditedBean {
                    private String name = "unset";
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                }
                """, Map.of()), new GeneratedPage("fixture.NameEditor", """
                package fixture;
                public class NameEditor extends java.beans.PropertyEditorSupport {
                    public void setAsText(String text) {
                        if (text.isEmpty()) throw new IllegalStateException("empty editor value");
                        if (text.equals("reject")) throw new IllegalArgumentException("rejected editor value");
                        setValue("edited:" + text);
                    }
                }
                """, Map.of()), new GeneratedPage("fixture.EditedBeanBeanInfo", """
                package fixture;
                public class EditedBeanBeanInfo extends java.beans.SimpleBeanInfo {
                    %s
                    static { System.setProperty("ibarra.editor.beaninfo.initialized", "yes"); }
                    private java.beans.PropertyDescriptor[] descriptors;
                    public java.beans.PropertyDescriptor[] getPropertyDescriptors() {
                        if (descriptors == null) {
                            try {
                                descriptors = new java.beans.PropertyDescriptor[] {
                                    new java.beans.PropertyDescriptor(%s, EditedBean.class)%s
                                };
                                descriptors[0].setPropertyEditorClass(NameEditor.class);
                            } catch (java.beans.IntrospectionException failure) { throw new IllegalStateException(failure); }
                        }
                        return java.util.Arrays.copyOf(descriptors, descriptors.length);
                    }
                }
                """.formatted(shape.equals("private-constructor") ? "private EditedBeanBeanInfo() {}" : "",
                        shape.equals("dynamic") ? "System.getProperty(\"dynamic.property\")" : "\"name\"",
                        shape.equals("invalid-descriptor") ? ", new java.beans.PropertyDescriptor(\"missing\", EditedBean.class)" : ""), Map.of())));
    }

    private GeneratedPage finalLifecycleSuperPage() {
        return new GeneratedPage("fixture.SuperPage", """
                package fixture;
                public abstract class SuperPage extends jakarta.servlet.http.HttpServlet implements jakarta.servlet.jsp.HttpJspPage {
                    @Override public final void init(jakarta.servlet.ServletConfig config) throws jakarta.servlet.ServletException {
                        super.init(config);
                        jspInit();
                    }
                    @Override public final void service(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response)
                            throws jakarta.servlet.ServletException, java.io.IOException {
                        _jspService((jakarta.servlet.http.HttpServletRequest) request,
                                (jakarta.servlet.http.HttpServletResponse) response);
                    }
                    @Override public final void destroy() {
                        jspDestroy();
                        super.destroy();
                    }
                    @Override public void jspInit() { }
                    @Override public void jspDestroy() { }
                }
                """, Map.of());
    }

    private String render(String source, String setup, String after) throws Exception {
        return run(generator().generate("/fixture.jsp", source, PAGE_CLASS, GenerationOptions.DEFAULT.withStartupEl(true)), setup, after);
    }

    private String tagFileVariablePage(String content) {
        return "<%@ page session='false' %><%@ taglib prefix='t' uri='urn:test:tagfile-variables' %>" + content;
    }

    private String renderTagFileVariablePage(int scope, String variable, boolean declare, String source) throws Exception {
        var attributes = Map.of(
                "variable", new TagAttribute("variable", "java.lang.String", false, false, false),
                "removeAfterBody", new TagAttribute("removeAfterBody", "boolean", false, false, false));
        var tag = new TagDescriptor("variable", "fixture.VariableTag", "scriptless", attributes,
                false, false, true, null, false, null,
                List.of(new TagVariableDescriptor(variable, "java.lang.String", declare, scope)));
        var library = new TagLibraryDescriptor("/WEB-INF/tagfile-variables.tld", "urn:test:tagfile-variables",
                List.of(), Map.of("variable", tag), null, Map.of());
        var options = GenerationOptions.DEFAULT.withStartupEl(true)
                .withTagLibraries(Map.of("urn:test:tagfile-variables", library));
        var generated = generator().generate("/tag-file-variables.jsp", source, PAGE_CLASS, options);
        return run(generated, List.of(variableTagHandler()), "", "");
    }

    private GeneratedPage variableTagHandler() {
        return new GeneratedPage("fixture.VariableTag", """
                package fixture;
                import java.io.IOException;
                import jakarta.servlet.jsp.JspContext;
                import jakarta.servlet.jsp.JspException;
                import jakarta.servlet.jsp.PageContext;
                import jakarta.servlet.jsp.tagext.SimpleTagSupport;
                import io.vidocq.ibarra.spi.TagFileJspContext;
                public final class VariableTag extends SimpleTagSupport {
                    private String variable;
                    private boolean removeAfterBody;
                    @Override public void setJspContext(JspContext context) {
                        super.setJspContext(context instanceof PageContext page ? new TagFileJspContext(page) : context);
                    }
                    public TagFileJspContext _jspxTagFileContext() { return (TagFileJspContext) getJspContext(); }
                    public void setVariable(String variable) { this.variable = variable; }
                    public void setRemoveAfterBody(boolean removeAfterBody) { this.removeAfterBody = removeAfterBody; }
                    @Override public void doTag() throws JspException, IOException {
                        var context = getJspContext();
                        if (context.getAttribute(variable, PageContext.PAGE_SCOPE) != null)
                            throw new JspException("Tag-file page scope did not start empty: " + variable);
                        context.setAttribute(variable, removeAfterBody ? "1" : "2", PageContext.PAGE_SCOPE);
                        if (getJspBody() != null) getJspBody().invoke(null);
                        if (removeAfterBody) {
                            context.removeAttribute(variable, PageContext.PAGE_SCOPE);
                            if (getJspBody() != null) getJspBody().invoke(null);
                        } else {
                            context.setAttribute(variable, "4", PageContext.PAGE_SCOPE);
                        }
                    }
                }
                """, Map.of());
    }

    private String run(GeneratedPage generated, String setup, String after) throws Exception {
        return run(generated, List.of(), setup, after);
    }

    private String run(GeneratedPage generated, List<GeneratedPage> supportingSources, String setup, String after) throws Exception {
        String fixture = """
                package io.vidocq.ibarra.core.internal;
                import jakarta.servlet.*;
                import jakarta.servlet.jsp.*;
                public class RenderMain {
                    public static void main(String[] args) throws Exception {
                        JspFactory.setDefaultFactory(new IbarraJspFactory());
                        var context = new ServletDoubles.Context();
                        var request = new ServletDoubles.Request();
                        var response = new ServletDoubles.Response();
                        %s
                        ServletConfig config = new ServletConfig() {
                            public String getServletName() { return "generated"; }
                            public ServletContext getServletContext() { return context; }
                            public String getInitParameter(String name) { return null; }
                            public java.util.Enumeration<String> getInitParameterNames() { return java.util.Collections.emptyEnumeration(); }
                        };
                        var page = new fixture.GeneratedPage();
                        page.init(config);
                        page.service(request, response);
                        %s
                        System.out.print("IBARRA_RESULT=" + java.util.Base64.getEncoder().encodeToString(
                            response.output.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    }
                }
                """.formatted(setup, after);
        var root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (!Files.isDirectory(root.resolve("ibarra-core"))) root = root.getParent();
        String doubles = Files.readString(root.resolve("ibarra-core/src/test/java/io/vidocq/ibarra/core/internal/ServletDoubles.java"));
        var sources = new ArrayList<GeneratedPage>(supportingSources);
        sources.add(generated);
        sources.add(new GeneratedPage("io.vidocq.ibarra.core.internal.RenderMain", fixture, Map.of()));
        sources.add(new GeneratedPage("io.vidocq.ibarra.core.internal.ServletDoubles", doubles, Map.of()));
        compile(sources);
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "--module-path", modulePath(), "--add-modules", "io.vidocq.peano.core,io.vidocq.peano.api,java.desktop",
                "--add-reads", "io.vidocq.ibarra.core=java.desktop,io.vidocq.peano.api",
                "--patch-module", "io.vidocq.ibarra.core=" + directory,
                "-m", "io.vidocq.ibarra.core/io.vidocq.ibarra.core.internal.RenderMain")
                .redirectErrorStream(true).redirectOutput(directory.resolve("result.log").toFile()).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "fixture JVM timed out");
        String output = Files.readString(directory.resolve("result.log"));
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.startsWith("IBARRA_RESULT="), output);
        return new String(Base64.getDecoder().decode(output.substring("IBARRA_RESULT=".length())), StandardCharsets.UTF_8);
    }

    private void compile(List<GeneratedPage> sources) throws Exception {
        new PageCompiler().compile(sources, directory, List.of("--module-path", modulePath(),
                "--add-modules", "io.vidocq.ibarra.core,io.vidocq.peano.api,java.desktop",
                "--add-reads", "io.vidocq.ibarra.core=java.desktop,io.vidocq.peano.api",
                "--patch-module", "io.vidocq.ibarra.core=" + directory));
    }

    private String modulePath() {
        var names = Set.of("io.vidocq.ibarra.api", "io.vidocq.ibarra.core", "jakarta.servlet", "jakarta.servlet.jsp", "jakarta.el", "io.vidocq.peano.core", "io.vidocq.peano.api");
        String available = System.getProperty("jdk.module.path", "") + File.pathSeparator
                + System.getProperty("surefire.test.class.path", "");
        var paths = Arrays.stream(available.split(java.util.regex.Pattern.quote(File.pathSeparator)))
                .filter(path -> !path.isEmpty()).map(Path::of)
                .filter(path -> path.toString().endsWith(".jar") || Files.isRegularFile(path.resolve("module-info.class")))
                .distinct().toArray(Path[]::new);
        return ModuleFinder.of(paths).findAll().stream()
                .filter(module -> names.contains(module.descriptor().name())).map(module -> module.location().orElseThrow())
                .map(uri -> Path.of(uri).toString()).collect(java.util.stream.Collectors.joining(File.pathSeparator));
    }

    private void prepareBean() throws Exception {
        compile(List.of(new GeneratedPage("fixture.Bean", """
                package fixture;
                public class Bean {
                    private String name;
                    private int count;
                    private Object data;
                    private String[] values;
                    public Object getData() { return data; }
                    public void setData(Object data) { this.data = data; }
                    public String[] getValues() { return values; }
                    public void setValues(String[] values) { this.values = values; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                    public int getCount() { return count; }
                    public void setCount(int count) { this.count = count; }
                }
                """, Map.of())));
    }

    private String dispatchSetup() {
        return """
                context.dispatchers.put("/part.jsp", new RequestDispatcher() {
                    public void include(ServletRequest req, ServletResponse res) throws java.io.IOException { res.getWriter().write("INCLUDED"); }
                    public void forward(ServletRequest req, ServletResponse res) throws java.io.IOException { res.getWriter().write("FORWARDED"); }
                });
                """;
    }
}

