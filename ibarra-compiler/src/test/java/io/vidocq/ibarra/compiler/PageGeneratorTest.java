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
    void rejectsUnsupportedMissingElPolicyAndSessionlessSessionBeans() {
        assertThrows(PageParseException.class, () -> generator().generate("/missing.jsp",
                "<%@ page errorOnELNotFound='true' %>${missing}", PAGE_CLASS, GenerationOptions.DEFAULT));
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
    void sharedScopeCreationIsRejectedUntilAtomicLifecycleSupportExists() throws Exception {
        prepareBean();
        for (String scope : List.of("session", "application")) {
            var error = assertThrows(PageParseException.class, () -> generator().generate("/shared.jsp",
                    "<jsp:useBean id='bean' class='fixture.Bean' scope='" + scope + "'/>", PAGE_CLASS, GenerationOptions.DEFAULT));
            assertTrue(error.getMessage().contains("Shared-scope"));
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
    void unsupportedGroupPoliciesAreNotSilentlyIgnored() {
        var options = new GenerationOptions(List.of(new JspPropertyGroup(List.of("*.jsp"),
                Map.of("error-on-el-not-found", "true"), List.of(), List.of())), Map.of());
        assertThrows(PageParseException.class, () -> generator().generate("/strict.jsp", "${missing}", PAGE_CLASS, options));
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
    void startupElIsAnExplicitFallbackWhileTheBuildTimeCompilerIsUnavailable() {
        var rejected = assertThrows(PageParseException.class, () -> generator().generate("/el.jsp", "${name}",
                PAGE_CLASS, GenerationOptions.DEFAULT));
        assertTrue(rejected.getMessage().contains("Build-time EL"));
        assertDoesNotThrow(() -> generator().generate("/el.jsp", "${name}", PAGE_CLASS,
                GenerationOptions.DEFAULT.withStartupEl(true)));
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
        var names = Set.of("io.vidocq.ibarra.core", "jakarta.servlet", "jakarta.servlet.jsp", "jakarta.el", "io.vidocq.peano.core");
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

