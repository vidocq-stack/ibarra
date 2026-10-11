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
    void includesRespectOrderAndEncodeParameters() throws Exception {
        String jsp = "<%@ page session='false' %>before<jsp:include page='/part.jsp' flush='false'>"
                + "<jsp:param name='q' value='a &amp; b'/></jsp:include>after";
        assertEquals("beforeINCLUDEDafter", render(jsp, dispatchSetup(),
                "if (!context.lastDispatchPath.equals(\"/part.jsp?q=a+%26amp%3B+b\")) throw new AssertionError(context.lastDispatchPath);"));
    }

    @Test
    void forwardDropsPendingOutputAndStopsThePage() throws Exception {
        String jsp = "<%@ page session='false' %>discard<jsp:forward page='/part.jsp'/>unreachable";
        assertEquals("FORWARDED", render(jsp, dispatchSetup(), ""));
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
        assertThrows(PageParseException.class, () -> generator().generate("/main.jsp", "<%@ include file='part.jspf' %>", PAGE_CLASS,
                new GenerationOptions(List.of(), Map.of("/part.jspf", "<%@ include file='main.jsp' %>", "/main.jsp", "<%@ include file='part.jspf' %>"))));
    }

    @Test
    void rejectsUnsupportedActionsInvalidNamesAndDisallowedScriptingWithSourceLocations() {
        for (String source : List.of("<jsp:plugin/>", "<jsp:unknown/>", "<c:tag/>", "<%@ page language='groovy' %>")) {
            String page = source.startsWith("<c:") ? "<%@ taglib prefix='c' uri='urn:custom' %>" + source : source;
            assertThrows(PageParseException.class, () -> generator().generate("/bad.jsp", page, PAGE_CLASS, GenerationOptions.DEFAULT));
        }
        assertThrows(IllegalArgumentException.class, () -> generator().generate("/x.jsp", "text", "not-a-name", GenerationOptions.DEFAULT));
        var options = new GenerationOptions(List.of(new JspPropertyGroup(List.of("*.jsp"),
                Map.of("scripting-invalid", "true"), List.of(), List.of())), Map.of());
        var error = assertThrows(PageParseException.class, () -> generator().generate("/bad.jsp", "\n<%= 1 %>", PAGE_CLASS, options));
        assertEquals(2, error.location().line());
    }

    @Test
    void compilerErrorsMapBackToJspSourceAndGeneratedClassesAreJava25() throws Exception {
        var bad = generator().generate("/broken.jsp", "<%@ page session='false' %>\n<% nonexistent(); %>", PAGE_CLASS, GenerationOptions.DEFAULT);
        var failure = assertThrows(PageCompilationException.class, () -> compile(List.of(bad)));
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
        assertEquals("<p title=\"${literal}\"/>", run(generated, "", ""));
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
        assertThrows(PageParseException.class, () -> generator().generate("/session.jsp",
                "<%@ page session='false' %><jsp:useBean id='bean' type='fixture.Bean' scope='session'/>",
                PAGE_CLASS, GenerationOptions.DEFAULT));
    }

    @Test
    void declarationsCannotOverrideTheGeneratedLifecycleEntryPoints() {
        for (String declaration : List.of("public void init(jakarta.servlet.ServletConfig config) {}",
                "public void service(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {}")) {
            var generated = generator().generate("/lifecycle.jsp", "<%@ page session='false' %><%! " + declaration + " %>",
                    PAGE_CLASS, GenerationOptions.DEFAULT);
            assertThrows(PageCompilationException.class, () -> compile(List.of(generated)));
        }
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
        var failure = assertThrows(PageParseException.class, () -> generator().generate("/main.jsp",
                "<%@ include file='part.jspf' %>", PAGE_CLASS, options));
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
        var error = assertThrows(PageParseException.class, () -> generator().generate("/names.jsp", "first\n<x:item/>", PAGE_CLASS, strict));
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
        var error = assertThrows(PageParseException.class, () -> generator().generate("/main.jsp",
                "<%@ include file='tags.jspf' %><c:item/>", PAGE_CLASS, strict));
        assertTrue(error.getMessage().contains("No build-time tag library"));
        assertEquals("/main.jsp", error.location().source());
        var later = assertThrows(PageParseException.class, () -> generator().generate("/main.jsp",
                "<c:item/><%@ include file='tags.jspf' %>", PAGE_CLASS, strict));
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
                    () -> generator().generate("/policy.jsp", "plain", PAGE_CLASS, options), name);
            assertEquals(new SourceLocation("/policy.jsp", 1, 1), error.location());
            assertTrue(error.getMessage().contains(name));
        }
        for (String name : List.of("session", "autoFlush", "isErrorPage", "isELIgnored",
                "deferredSyntaxAllowedAsLiteral", "trimDirectiveWhitespaces", "errorOnELNotFound")) {
            var error = assertThrows(PageParseException.class, () -> generator().generate("/policy.jsp",
                    "first\n<%@ page " + name + "='yes' %>", PAGE_CLASS, GenerationOptions.DEFAULT), name);
            assertEquals(new SourceLocation("/policy.jsp", 2, 1), error.location());
            assertTrue(error.getMessage().contains(name));
        }
    }

    @Test
    void strictNamespaceErrorsRetainIncludeOriginsAndProcessHtmlComments() {
        var strict = new GenerationOptions(List.of(new JspPropertyGroup(List.of("*.jsp"),
                Map.of("error-on-undeclared-namespace", "true"), List.of(), List.of())),
                Map.of("/fragment.jspf", "first\n<x:item/>"));
        var error = assertThrows(PageParseException.class, () -> generator().generate("/main.jsp",
                "<%@ include file='fragment.jspf' %>", PAGE_CLASS, strict));
        assertEquals(new SourceLocation("/fragment.jspf", 2, 1), error.location());
        assertThrows(PageParseException.class, () -> generator().generate("/main.jsp",
                "<!-- <x:item/> -->", PAGE_CLASS, strict));
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
        var duplicate = assertThrows(PageParseException.class, () -> generator().generate("/main.jsp",
                "<%@ page pageEncoding='UTF-8' %>\n<%@ page pageEncoding='UTF-8' %>", PAGE_CLASS, GenerationOptions.DEFAULT));
        assertEquals(new SourceLocation("/main.jsp", 2, 1), duplicate.location());
        assertTrue(duplicate.getMessage().contains("once per file"));
        var options = new GenerationOptions(List.of(), Map.of("/part.jspf", "first\n<%@ page pageEncoding='not-an-encoding' %>"));
        var unknown = assertThrows(PageParseException.class, () -> generator().generate("/main.jsp",
                "<%@ include file='part.jspf' %>", PAGE_CLASS, options));
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
        var error = assertThrows(PageParseException.class, () -> generator().generate("/main.jsp",
                "<%@ include file='part.jspx' %>", PAGE_CLASS, options));
        assertEquals("/part.jspx", error.location().source());
        assertEquals(2, error.location().line());
    }

    @Test
    void invalidBufferAndUnknownGroupKeysHaveTranslationDiagnostics() {
        var options = new GenerationOptions(List.of(), Map.of("/part.jspf", "first\n<%@ page buffer='huge' %>"));
        var buffer = assertThrows(PageParseException.class, () -> generator().generate("/main.jsp",
                "<%@ include file='part.jspf' %>", PAGE_CLASS, options));
        assertEquals(new SourceLocation("/part.jspf", 2, 1), buffer.location());
        var typo = new GenerationOptions(List.of(new JspPropertyGroup(List.of("*.jsp"),
                Map.of("error-on-el-not-fond", "true"), List.of(), List.of())), Map.of());
        var error = assertThrows(PageParseException.class, () -> generator().generate("/main.jsp", "plain", PAGE_CLASS, typo));
        assertTrue(error.getMessage().contains("error-on-el-not-fond"));
    }

    @Test
    void standardTaglibCannotRedefineAnEnclosingXmlNamespace() {
        var options = new GenerationOptions(List.of(), Map.of("/tags.jspf", "<%@ taglib prefix='c' uri='urn:other' %>"));
        var error = assertThrows(PageParseException.class, () -> generator().generate("/main.jspx",
                "<jsp:root xmlns:jsp='" + PageParser.JSP_NAMESPACE + "' xmlns:c='urn:existing' version='4.0'>"
                + "<jsp:directive.include file='tags.jspf'/></jsp:root>", PAGE_CLASS, options));
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
        var error = assertThrows(PageParseException.class, () -> generator().generate("/main.jspx",
                "<j:root xmlns:j='" + PageParser.JSP_NAMESPACE + "' version='4.0'><j:directive.include file='tags.jspf'/></j:root>", PAGE_CLASS, options));
        assertTrue(error.getMessage().contains("No build-time tag library"));
    }

    @Test
    void xmlIncludeMayDeclareItsJspNamespaceLocally() throws Exception {
        var options = new GenerationOptions(List.of(), Map.of("/part.jspf", "included"));
        assertEquals("<p>included</p>", run(generator().generate("/main.jspx", "<p><j:directive.include xmlns:j='"
                + PageParser.JSP_NAMESPACE + "' file='part.jspf'/></p>", PAGE_CLASS, options), "", ""));
    }

    @Test
    void defaultJspNamespacesStillValidateFileLocalEncodings() {
        var error = assertThrows(PageParseException.class, () -> generator().generate("/main.jspx",
                "<root xmlns='" + PageParser.JSP_NAMESPACE + "' version='4.0'>\n"
                + "<directive.page pageEncoding='not-an-encoding'/></root>", PAGE_CLASS, GenerationOptions.DEFAULT));
        assertEquals(new SourceLocation("/main.jspx", 2, 1), error.location());
        String prefix = "<root xmlns='" + PageParser.JSP_NAMESPACE + "' version='4.0'>\n";
        for (String directives : List.of("<directive.page pageEncoding='not-an-encoding'/>",
                "<directive.page pageEncoding='UTF-8'/><directive.page pageEncoding='UTF-8'/>")) {
            var options = new GenerationOptions(List.of(), Map.of("/part.jspx", prefix + directives + "</root>"));
            var included = assertThrows(PageParseException.class, () -> generator().generate("/main.jsp",
                    "<%@ include file='part.jspx' %>", PAGE_CLASS, options));
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
        assertEquals("<p title=\"  \"/>", run(generated, "", ""));
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
        assertEquals("<p>é</p>", run(generator().generate("/main.jspx", bytes, PAGE_CLASS, GenerationOptions.DEFAULT), "",
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
        assertThrows(IllegalArgumentException.class, () -> new GenerationOptions(List.of(), Map.of("/part.jspf", "text"))
                .withByteSources(Map.of("/part.jspf", bytes)));
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
        var failure = assertThrows(PageParseException.class, () -> generator().generate("/bad-el.jsp", "before\n${1 + }",
                PAGE_CLASS, GenerationOptions.DEFAULT));
        assertTrue(failure.getMessage().contains("/bad-el.jsp:2:"));
    }
    @Test void defaultExplicitWildcardAndNestedImportsAreVisibleToEl() throws Exception {
        assertEquals("200|true|true", render("<%@ page session='false' import='java.util.*,java.util.Map.Entry' %>${HttpServletResponse.SC_OK}|${List != null}|${Entry != null}", "", ""));
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
        assertThrows(PageParseException.class, () -> generator().generate("/bad.jsp", "<%@ page import='static java.lang.Math.PI' %>", PAGE_CLASS, GenerationOptions.DEFAULT));
    }
    @Test void unavailableImportedTypesKeepDirectiveOrigins() {
        var failure = assertThrows(PageParseException.class, () -> generator().generate("/missing-import.jsp", "before\n<%@ page import='java.util.DoesNotExist' %>", PAGE_CLASS, GenerationOptions.DEFAULT));
        assertTrue(failure.getMessage().contains("/missing-import.jsp:2:"));
    }

    private PageGenerator generator() { return new PageGenerator(List.of(directory)); }

    private String render(String source, String setup, String after) throws Exception {
        return run(generator().generate("/fixture.jsp", source, PAGE_CLASS, GenerationOptions.DEFAULT.withStartupEl(true)), setup, after);
    }

    private String run(GeneratedPage generated, String setup, String after) throws Exception {
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
        compile(List.of(generated, new GeneratedPage("io.vidocq.ibarra.core.internal.RenderMain", fixture, Map.of()),
                new GeneratedPage("io.vidocq.ibarra.core.internal.ServletDoubles", doubles, Map.of())));
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "--module-path", modulePath(), "--add-modules", "io.vidocq.peano.core",
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
                "--add-modules", "io.vidocq.ibarra.core", "--patch-module", "io.vidocq.ibarra.core=" + directory));
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

