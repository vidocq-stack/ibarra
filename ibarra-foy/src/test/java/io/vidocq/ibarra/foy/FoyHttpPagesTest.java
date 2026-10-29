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
package io.vidocq.ibarra.foy;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.lang.module.ModuleFinder;
import javax.tools.ToolProvider;
import io.vidocq.ibarra.compiler.*;
import org.jboss.arquillian.container.spi.client.container.*;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.*;
import org.jboss.shrinkwrap.api.*;
import org.jboss.shrinkwrap.api.importer.ZipImporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FoyHttpPagesTest {
    @TempDir Path directory;

    @Test
    void arquillianDeploymentServesCompiledPagesAliasesAndIncludesOverHttp() throws Exception {
        Path app = prepareApplication();
        var archive = ShrinkWrap.create(ZipImporter.class, "pages.jar").importFrom(app.toFile()).as(JavaArchive.class);
        var container = new PagesContainer();
        var configuration = new Configuration(); configuration.directory = directory; configuration.modulePath = modulePath(false);
        container.setup(configuration); container.start();
        boolean deployed = false;
        try {
            var metadata = container.deploy(archive);
            deployed = true;
            var http = metadata.getContexts(HTTPContext.class).iterator().next();
            String base = "http://" + http.getHost() + ":" + http.getPort() + "/site";
            try (var client = HttpClient.newHttpClient()) {
                assertCompiledPagesAndAliases(client, base);
                assertTagsImportsAndFunctions(client, base);
                assertApplicationAndSessionScopes(client, base);
                assertNamedTagsAndProtectedResources(client, base);
            }
        } finally { try { if (deployed) container.undeploy(archive); } finally { container.stop(); } }
        assertEquals("1:1", Files.readString(directory.resolve("listener-destroyed.txt")));
    }

    private void assertCompiledPagesAndAliases(HttpClient client, String base) throws Exception {
        assertEquals("Hello A true jsp", get(client, base + "/index.jsp?who=A").body());
        assertEquals("Hello B true dynamic", get(client, base + "/dynamic?who=B").body());
        assertEquals("Hello C true named", get(client, base + "/named?who=C").body());
        assertEquals("beforepieceafter", get(client, base + "/include.jsp").body());
        assertEquals("XML", get(client, base + "/document.jspx").body());
        assertEquals("ready ready 1", get(client, base + "/listeners.jsp").body());
    }

    private void assertTagsImportsAndFunctions(HttpClient client, String base) throws Exception {
        assertEquals("one:1:BODYBODY!", get(client, base + "/tags.jsp?label=one").body());
        assertEquals("two:1:BODYBODY!", get(client, base + "/tags.jsp?label=two").body());
        assertEquals("xml:1:BODYBODY!", get(client, base + "/tags.jspx").body());
        assertEquals("A:Direct:4", get(client, base + "/compiled-el.jspx?who=A").body(), Files.readString(directory.resolve("server.log")));
        assertEquals("B:Direct:4", get(client, base + "/compiled-el.jspx?who=B").body());
        assertEquals("imported:1:method:200method:200!", get(client, base + "/imports.jspx").body());
        assertEquals("imported", get(client, base + "/imports.jsp").body());
        assertEquals("hello-A:method", get(client, base + "/functions.jsp?who=A").body());
        assertEquals("hello-A:1:methodmethod!", get(client, base + "/functions.jspx?who=A").body());
        assertEquals("hello-B:1:methodmethod!", get(client, base + "/functions.jspx?who=B").body());
        assertEquals("hello-A:1:xx!|ready|1", get(client, base + "/external.jsp?who=A").body());
        assertEquals("hello-B:1:xx!|ready|1", get(client, base + "/external.jspx?who=B").body());
        assertEquals("A|Direct:call", get(client, base + "/deferred.jsp?who=A").body(), Files.readString(directory.resolve("server.log")));
        assertEquals("B|Direct:call", get(client, base + "/deferred.jspx?who=B").body());
    }

    private static void assertApplicationAndSessionScopes(HttpClient client, String base) throws Exception {
        var sharedCalls = new java.util.ArrayList<java.util.concurrent.CompletableFuture<HttpResponse<String>>>();
        for (int i = 0; i < 16; i++) sharedCalls.add(client.sendAsync(
                HttpRequest.newBuilder(URI.create(base + (i % 2 == 0 ? "/shared.jsp" : "/shared.jspx"))).build(), HttpResponse.BodyHandlers.ofString()));
        int created = 0;
        for (var call : sharedCalls) {
            var result = call.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(200, result.statusCode(), result.body());
            if (result.body().equals("created:1")) created++;
            else assertEquals("1", result.body());
        }
        assertEquals(1, created);
        try (var sessionClient = HttpClient.newBuilder().cookieHandler(new java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL)).build()) {
            assertEquals("created:2", get(sessionClient, base + "/session.jsp").body());
            assertEquals("2", get(sessionClient, base + "/session.jsp").body());
        }
        try (var otherSession = HttpClient.newBuilder().cookieHandler(new java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL)).build()) {
            assertEquals("created:3", get(otherSession, base + "/session.jsp").body());
        }
        assertEquals("1", get(client, base + "/shared.jspx").body());
    }

    private static void assertNamedTagsAndProtectedResources(HttpClient client, String base) throws Exception {
        assertEquals("<fragment>fragmentBODYBODY", get(client, base + "/named-tags.jspx").body());
        assertEquals("<b title=\"&quot;&amp;\">label</b>:1:BB!", get(client, base + "/static-named-tags.jspx").body());
        assertEquals(404, get(client, base + "/missing.jsp").statusCode());
        assertEquals(404, get(client, base + "/WEB-INF/secret.jsp").statusCode());
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private Path prepareApplication() throws Exception {
        Path output = directory.resolve("application"); Path source = directory.resolve("source"); Path web = directory.resolve("web");
        Files.createDirectories(output); Files.createDirectories(source); Files.createDirectories(web.resolve("WEB-INF"));
        Path info = source.resolve("module-info.java");
        Files.writeString(info, "module integration.pages { requires io.vidocq.ibarra.api; requires io.vidocq.peano.api; provides io.vidocq.peano.spi.AccessorProvider with app.Accessors; }");
        Path profile = source.resolve("app/Profile.java"), accessors = source.resolve("app/Accessors.java"), constants = source.resolve("app/Constants.java");
        Files.createDirectories(profile.getParent());
        Files.writeString(profile, "package app; public class Profile { public String getName() { return \"Direct\"; } public String greet(String name){return getName()+\":\"+name;} }");
        Path deferred = source.resolve("app/Deferred.java");
        Files.writeString(deferred, """
                package app;
                public class Deferred extends jakarta.servlet.jsp.tagext.SimpleTagSupport {
                    private jakarta.el.ValueExpression value; private jakarta.el.MethodExpression action;
                    public void setValue(jakarta.el.ValueExpression value){this.value=value;}
                    public void setAction(jakarta.el.MethodExpression action){this.action=action;}
                    public void doTag() throws java.io.IOException {
                        var page=(jakarta.servlet.jsp.PageContext)getJspContext();
                        page.getRequest().setAttribute("later",page.getRequest().getParameter("who"));
                        var context=page.getELContext();
                        var loader=Thread.currentThread().getContextClassLoader();
                        action.getMethodInfo(context); action.getMethodReference(context);
                        page.getOut().write(value.getValue(context)+"|"+action.invoke(context,new Object[]{"call"}));
                        if(Thread.currentThread().getContextClassLoader()!=loader)throw new AssertionError("Deferred method leaked application loader");
                    }
                }
                """);
        Path scopeBean = source.resolve("app/ScopeBean.java");
        Files.writeString(scopeBean, "package app; public class ScopeBean { private static final java.util.concurrent.atomic.AtomicInteger sequence=new java.util.concurrent.atomic.AtomicInteger(); private final int id=sequence.incrementAndGet(); public int getId(){return id;} }");
        Files.writeString(constants, "package app; public class Constants { private Constants(){} public static final String TEXT=text(); private static String text(){System.setProperty(\"ibarra.import.initialized\",\"yes\");return \"imported\";} public static String caption(){return \"method\";} public static String greet(String value){return \"hello-\"+value;} }");
        Files.writeString(accessors, """
                package app;
                public class Accessors implements io.vidocq.peano.spi.AccessorProvider {
                    public java.util.Collection<io.vidocq.peano.spi.AccessorTable> accessors() {
                        return java.util.List.of(new io.vidocq.peano.spi.AccessorTable() {
                            public Class<?> targetType() { return Profile.class; }
                            public Object getValue(jakarta.el.ELContext context, Object base, Object property) {
                                if (base instanceof Profile profile && "name".equals(property)) { context.setPropertyResolved(base, property); return profile.getName(); }
                                return null;
                            }
                            public jakarta.el.MethodReference getMethodReference(jakarta.el.ELContext context,Object base,String method,Class<?>[] types,Object[] arguments) {
                                if (base instanceof Profile && method.equals("greet")) return new jakarta.el.MethodReference(base,
                                    new jakarta.el.MethodInfo("greet",String.class,new Class<?>[]{String.class}),new java.lang.annotation.Annotation[0],arguments);
                                return null;
                            }
                            public Object invoke(jakarta.el.ELContext context,Object base,Object method,Class<?>[] types,Object[] arguments) {
                                if (base instanceof Profile profile && "greet".equals(method)) {context.setPropertyResolved(base,method);return profile.greet(context.convertToType(arguments[0],String.class));}
                                return null;
                            }
                            public Class<?> getType(jakarta.el.ELContext context, Object base, Object property) { return null; }
                            public void setValue(jakarta.el.ELContext context, Object base, Object property, Object value) { }
                            public boolean isReadOnly(jakarta.el.ELContext context, Object base, Object property) { return true; }
                            public Class<?> getCommonPropertyType(jakarta.el.ELContext context, Object base) { return String.class; }
                        }, new io.vidocq.peano.spi.AccessorTable() {
                            public Class<?> targetType() { return Constants.class; }
                            public Object getValue(jakarta.el.ELContext context, Object base, Object property) {
                                if (base instanceof jakarta.el.ELClass type && type.getKlass()==Constants.class && "TEXT".equals(property)) { context.setPropertyResolved(base, property); return Constants.TEXT; }
                                return null;
                            }
                            public Object invoke(jakarta.el.ELContext context, Object base, Object method, Class<?>[] types, Object[] values) {
                                if (base instanceof jakarta.el.ELClass type && type.getKlass()==Constants.class && "caption".equals(method)) { context.setPropertyResolved(base, method); return Constants.caption(); }
                                return null;
                            }
                            public Class<?> getType(jakarta.el.ELContext context, Object base, Object property) { return null; }
                            public void setValue(jakarta.el.ELContext context, Object base, Object property, Object value) { }
                            public boolean isReadOnly(jakarta.el.ELContext context, Object base, Object property) { return true; }
                            public Class<?> getCommonPropertyType(jakarta.el.ELContext context, Object base) { return String.class; }
                        });
                    }
                }
                """);
        Path listener = source.resolve("app/Startup.java"); Files.createDirectories(listener.getParent());
        Path externalListener = source.resolve("app/ExternalStartup.java");
        Files.writeString(externalListener, """
                package app;
                public class ExternalStartup implements jakarta.servlet.ServletContextListener {
                    public static int constructed;
                    public ExternalStartup() { constructed++; System.setProperty("ibarra.external.initialized", "yes"); }
                    public void contextInitialized(jakarta.servlet.ServletContextEvent event) { event.getServletContext().setAttribute("externalTld", "ready"); }
                    public void contextDestroyed(jakarta.servlet.ServletContextEvent event) { event.getServletContext().setAttribute("externalDestroyed", "1"); }
                }
                """);
        Files.writeString(listener, """
                package app;
                public class Startup implements jakarta.servlet.ServletContextListener, jakarta.servlet.ServletRequestListener {
                    public static int constructed;
                    public Startup() { constructed++; }
                    public void contextInitialized(jakarta.servlet.ServletContextEvent event) {
                        var context = event.getServletContext();
                        try { context.addServlet("forbidden", new jakarta.servlet.http.HttpServlet() { }); throw new AssertionError("TLD listener can configure servlets"); }
                        catch (UnsupportedOperationException expected) { }
                        context.setAttribute("tld", "ready");
                    }
                    public void requestInitialized(jakarta.servlet.ServletRequestEvent event) { event.getServletRequest().setAttribute("tldRequest", "ready"); }
                    public void contextDestroyed(jakarta.servlet.ServletContextEvent event) { event.getServletContext().setAttribute("tldDestroyed", "1"); }
                }
                """);
        Path wrap = source.resolve("app/Wrap.java"), upper = source.resolve("app/Upper.java"), fragment = source.resolve("app/Fragment.java");
        Files.writeString(fragment, """
                package app;
                public class Fragment extends jakarta.servlet.jsp.tagext.SimpleTagSupport {
                    private jakarta.servlet.jsp.tagext.JspFragment piece;
                    public void setPiece(jakarta.servlet.jsp.tagext.JspFragment value) { piece=value; }
                    public void doTag() throws jakarta.servlet.jsp.JspException, java.io.IOException {
                        var writer=new java.io.StringWriter(); piece.invoke(writer);
                        getJspContext().getOut().write("<"+writer+">"); piece.invoke(null);
                        getJspBody().invoke(null); getJspBody().invoke(null);
                    }
                }
                """);
        Files.writeString(wrap, """
                package app;
                public class Wrap extends jakarta.servlet.jsp.tagext.SimpleTagSupport {
                    private String label; private int calls;
                    public void setLabel(String value) { label=value; }
                    public void doTag() throws jakarta.servlet.jsp.JspException, java.io.IOException {
                        var out=getJspContext().getOut(); out.write(label+":"+(++calls)+":");
                        getJspBody().invoke(null); getJspBody().invoke(null); out.write("!");
                    }
                }
                """);
        Files.writeString(upper, """
                package app;
                public class Upper extends jakarta.servlet.jsp.tagext.BodyTagSupport {
                    public int doAfterBody() throws jakarta.servlet.jsp.JspException {
                        try { bodyContent.getEnclosingWriter().write(bodyContent.getString().toUpperCase(java.util.Locale.ROOT)); }
                        catch(java.io.IOException failure) { throw new jakarta.servlet.jsp.JspException(failure); }
                        return SKIP_BODY;
                    }
                }
                """);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "25", "--module-path",
                modulePath(true), "-d", output.toString(), info.toString(), listener.toString(), wrap.toString(), upper.toString(), fragment.toString(), profile.toString(), accessors.toString(), constants.toString(), scopeBean.toString(), externalListener.toString(), deferred.toString()));
        Files.writeString(web.resolve("WEB-INF/deferred.tld"), "<taglib><uri>urn:deferred</uri><tag><name>run</name><tag-class>app.Deferred</tag-class><body-content>empty</body-content><attribute><name>value</name><deferred-value><type>java.lang.String</type></deferred-value></attribute><attribute><name>action</name><deferred-method><method-signature>java.lang.String documented(java.lang.String)</method-signature></deferred-method></attribute></tag></taglib>");
        Files.writeString(web.resolve("deferred.jsp"), "<%@ page session='false' %><%@ taglib prefix='d' uri='urn:deferred' %><jsp:useBean id='profile' class='app.Profile'/><d:run value='#{requestScope.later}' action='#{profile.greet}'/>");
        Files.writeString(web.resolve("deferred.jspx"), "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' xmlns:d='urn:deferred' version='4.0'><jsp:directive.page session='false'/><jsp:useBean id='profile' class='app.Profile'/><d:run><jsp:attribute name='value'> #{requestScope.later} </jsp:attribute><jsp:attribute name='action'> #{profile.greet} </jsp:attribute></d:run></jsp:root>");
        Files.createDirectories(web.resolve("outside"));
        Files.writeString(web.resolve("outside/library.tld"), "<taglib><listener><listener-class>app.ExternalStartup</listener-class></listener><tag><name>wrap</name><tag-class>app.Wrap</tag-class><body-content>scriptless</body-content><attribute><name>label</name><type>java.lang.String</type><required>true</required><rtexprvalue>true</rtexprvalue></attribute></tag><function><name>greet</name><function-class>app.Constants</function-class><function-signature>java.lang.String greet(java.lang.String)</function-signature></function></taglib>");
        Files.writeString(web.resolve("external.jsp"), "<%@ page session='false' %><%@ taglib prefix='e' uri='outside/library.tld' %><e:wrap label='${e:greet(param.who)}'>x</e:wrap>|<%= application.getAttribute(\"externalTld\") %>|<%= app.ExternalStartup.constructed %>");
        Files.writeString(web.resolve("external.jspx"), "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' xmlns:e='urn:jsptld:/outside/library.tld' version='4.0'><jsp:directive.page session='false'/><e:wrap label='${e:greet(param.who)}'>x</e:wrap><jsp:text>|</jsp:text><jsp:expression>application.getAttribute(\"externalTld\")</jsp:expression><jsp:text>|</jsp:text><jsp:expression>app.ExternalStartup.constructed</jsp:expression></jsp:root>");
        Files.writeString(web.resolve("shared.jsp"), "<%@ page session='false' %><jsp:useBean id='shared' class='app.ScopeBean' scope='application'>created:</jsp:useBean><jsp:getProperty name='shared' property='id'/>");
        Files.writeString(web.resolve("shared.jspx"), "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' version='4.0'><jsp:directive.page session='false'/><jsp:useBean id='shared' class='app.ScopeBean' scope='application'><jsp:text>created:</jsp:text></jsp:useBean><jsp:getProperty name='shared' property='id'/></jsp:root>");
        Files.writeString(web.resolve("session.jsp"), "<jsp:useBean id='shared' class='app.ScopeBean' scope='session'>created:</jsp:useBean><jsp:getProperty name='shared' property='id'/>");
        Files.writeString(web.resolve("WEB-INF/handlers.tld"), """
                <taglib><uri>urn:tags</uri>
                <tag><name>wrap</name><tag-class>app.Wrap</tag-class><body-content>scriptless</body-content>
                  <attribute><name>label</name><type>java.lang.String</type><required>true</required><rtexprvalue>true</rtexprvalue></attribute></tag>
                <tag><name>upper</name><tag-class>app.Upper</tag-class><body-content>scriptless</body-content></tag>
                <tag><name>fragment</name><tag-class>app.Fragment</tag-class><body-content>scriptless</body-content>
                  <attribute><name>piece</name><fragment>true</fragment><required>true</required></attribute></tag>
                <tag><name>staticWrap</name><tag-class>app.Wrap</tag-class><body-content>scriptless</body-content>
                  <attribute><name>label</name><required>true</required></attribute></tag>
                </taglib>
                """);
        Files.writeString(web.resolve("tags.jsp"), "<%@ page session='false' %><%@ taglib prefix='t' uri='urn:tags' %><t:wrap label='${param.label}'><t:upper>body</t:upper></t:wrap>");
        Files.writeString(web.resolve("compiled-el.jspx"), "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' version='4.0'><jsp:directive.page session='false'/><jsp:useBean id='profile' class='app.Profile'/><jsp:text>${param.who}:${profile.name}:${[1,2,3].stream().map(x->x*2).toList()[1]}</jsp:text></jsp:root>");
        Files.writeString(web.resolve("imports.jspx"), "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' xmlns:t='urn:tags' version='4.0'><jsp:directive.page session='false' import='app.Constants'/><t:wrap label='${Constants.TEXT}'><jsp:body>${Constants.caption()}:${HttpServletResponse != null ? '200' : 'bad'}</jsp:body></t:wrap></jsp:root>");
        Files.writeString(web.resolve("imports.jspf"), "<%@ page import='app.Constants' %>");
        Files.writeString(web.resolve("imports.jsp"), "<%@ page session='false' %><%@ include file='imports.jspf' %>${Constants.TEXT}");
        Files.writeString(web.resolve("WEB-INF/functions.tld"), "<taglib><uri>urn:fn</uri><function><name>greet</name><function-class>app.Constants</function-class><function-signature>java.lang.String greet(java.lang.String)</function-signature></function><function><name>caption</name><function-class>app.Constants</function-class><function-signature>java.lang.String caption()</function-signature></function></taglib>");
        Files.writeString(web.resolve("functions.jsp"), "<%@ page session='false' %><%@ taglib prefix='f' uri='urn:fn' %>${f:greet(param.who)}:${f:caption()}");
        Files.writeString(web.resolve("functions.jspx"), "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' xmlns:t='urn:tags' xmlns:f='urn:fn' version='4.0'><jsp:directive.page session='false'/><t:wrap label='${f:greet(param.who)}'><jsp:body>${f:caption()}</jsp:body></t:wrap></jsp:root>");
        Files.writeString(web.resolve("tags.jspx"), "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' xmlns:t='urn:tags' version='4.0'><jsp:directive.page session='false'/><t:wrap label='xml'><t:upper>body</t:upper></t:wrap></jsp:root>");
        Files.writeString(web.resolve("named-tags.jspx"), "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' xmlns:t='urn:tags' version='4.0'><jsp:directive.page session='false'/><t:fragment><jsp:attribute name='piece'> fragment </jsp:attribute><jsp:body><t:upper><jsp:body>body</jsp:body></t:upper></jsp:body></t:fragment></jsp:root>");
        Files.writeString(web.resolve("static-named-tags.jspx"), "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' xmlns:t='urn:tags' version='4.0'><jsp:directive.page session='false'/><t:staticWrap><jsp:attribute name='label'><b title='&quot;&amp;'><jsp:text>label</jsp:text></b></jsp:attribute><jsp:body>B</jsp:body></t:staticWrap></jsp:root>");
        Files.writeString(web.resolve("WEB-INF/startup.tld"), "<taglib><listener><listener-class>app.Startup</listener-class></listener></taglib>");
        Files.writeString(web.resolve("WEB-INF/duplicate.tld"), "<taglib><listener><listener-class>app.Startup</listener-class></listener></taglib>");
        Files.writeString(web.resolve("listeners.jsp"), "<%@ page session='false' %><%= application.getAttribute(\"tld\") %> <%= request.getAttribute(\"tldRequest\") %> <%= app.Startup.constructed %>");
        Files.writeString(web.resolve("index.jsp"), "<%@ page session='false' %>Hello <%= request.getParameter(\"who\") %> <%= Thread.currentThread().isVirtual() %> <%= config.getServletName() %>");
        Files.writeString(web.resolve("piece.jsp"), "<%@ page session='false' %>piece");
        Files.writeString(web.resolve("document.jspx"), "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' version='4.0'><jsp:directive.page session='false'/><jsp:text>XML</jsp:text></jsp:root>");
        Files.writeString(web.resolve("include.jsp"), "<%@ page session='false' %>before<jsp:include page='/piece.jsp' flush='false'/>after");
        Files.writeString(web.resolve("WEB-INF/secret.jsp"), "<%@ page session='false' %>SECRET");
        new PagePrecompiler().precompile(web, output, paths(true), null, GenerationOptions.DEFAULT);
        assertNull(System.getProperty("ibarra.import.initialized"), "Import discovery initialized application code");
        assertNull(System.getProperty("ibarra.external.initialized"), "Direct TLD discovery initialized application code");
        Files.writeString(output.resolve("web.xml"), """
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.1">
                  <servlet><servlet-name>named</servlet-name><jsp-file>/index.jsp</jsp-file></servlet>
                  <servlet-mapping><servlet-name>named</servlet-name><url-pattern>/named</url-pattern></servlet-mapping>
                </web-app>
                """);
        Path jar = directory.resolve("application.jar");
        try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(jar)); var files = Files.walk(output)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new java.util.jar.JarEntry(output.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, out); out.closeEntry();
            }
        }
        prepareHost();
        return jar;
    }

    private void prepareHost() throws Exception {
        Path source = directory.resolve("host-source"); Path output = directory.resolve("host");
        Files.createDirectories(source.resolve("host")); Files.createDirectories(output);
        Files.writeString(source.resolve("module-info.java"), "module integration.host { requires io.vidocq.foy.core; requires io.vidocq.chappe.api; requires io.vidocq.ibarra.foy; requires io.vidocq.ibarra.api; requires io.vidocq.peano.core; provides io.vidocq.ibarra.spi.PageIndex with host.Main.ParentIndex; }");
        Files.writeString(source.resolve("host/Main.java"), """
                package host;
                import java.nio.file.*;
                import java.lang.module.*;
                import io.vidocq.foy.internal.boot.*;
                import io.vidocq.foy.internal.webxml.*;
                import io.vidocq.chappe.api.Server;
                public class Main {
                    public static final class ParentIndex implements io.vidocq.ibarra.spi.PageIndex {
                        public ParentIndex() { throw new AssertionError("Parent index must not be constructed for a child application"); }
                        public java.util.List<io.vidocq.ibarra.spi.PageDescriptor> pages() { return java.util.List.of(); }
                    }
                    public static void main(String[] args) throws Exception {
                        if (ModuleLayer.boot().findModule("java.compiler").isPresent()
                                || ModuleLayer.boot().findModule("io.vidocq.peano.compiler").isPresent()
                                || ModuleLayer.boot().findModule("io.vidocq.ibarra.compiler").isPresent()) throw new AssertionError("Compiler present at runtime");
                        var finder = ModuleFinder.of(Path.of(args[0]));
                        var configuration = ModuleLayer.boot().configuration().resolveAndBind(finder, ModuleFinder.of(), java.util.Set.of("integration.pages"));
                        var layer = ModuleLayer.defineModulesWithOneLoader(configuration, java.util.List.of(ModuleLayer.boot()), ClassLoader.getSystemClassLoader()).layer();
                        var loader = layer.findLoader("integration.pages");
                        var options = DeployOptions.defaults(loader);
                        var model = WebAppModel.builder("/site");
                        try (var zip = new java.util.jar.JarFile(args[0]); var input = zip.getInputStream(zip.getJarEntry("web.xml"))) {
                            DescriptorMerger.merge(WebXmlParser.parse(input), DescriptorMerger.AnnotatedComponents.none(), options.componentFactory(), model);
                        }
                        model.initializer((types, context) -> context.addJspFile("dynamic", "/index.jsp").addMapping("/dynamic"));
                        jakarta.servlet.ServletContext context;
                        try (var deployment = WebAppDeployer.deploy(model.build(), options, layer);
                             var server = Server.builder().host("127.0.0.1").port(0).handler(deployment.handler()).build()) {
                            server.start();
                            context = deployment.servletContext();
                            System.out.println("READY:" + server.port()); System.out.flush();
                            System.in.read();
                            server.stop();
                        }
                        Files.writeString(Path.of(args[1]), context.getAttribute("tldDestroyed") + ":" + context.getAttribute("externalDestroyed"));
                    }
                }
                """);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "25", "--module-path",
                modulePath(true), "-d", output.toString(), source.resolve("module-info.java").toString(), source.resolve("host/Main.java").toString()));
    }

    private List<Path> paths(boolean compiler) { return Arrays.stream(modulePath(compiler).split(java.util.regex.Pattern.quote(File.pathSeparator))).map(Path::of).toList(); }
    private String modulePath(boolean compiler) {
        var names = new HashSet<>(Set.of("io.vidocq.ibarra.api", "io.vidocq.ibarra.core", "io.vidocq.ibarra.foy",
                "io.vidocq.foy.api", "io.vidocq.foy.core", "io.vidocq.chappe.api", "io.vidocq.chappe.core",
                "jakarta.servlet", "jakarta.servlet.jsp", "jakarta.el", "io.vidocq.peano.api", "io.vidocq.peano.core"));
        if (compiler) names.add("io.vidocq.ibarra.compiler");
        String available = System.getProperty("jdk.module.path", "") + File.pathSeparator + System.getProperty("surefire.test.class.path", "");
        var paths = Arrays.stream(available.split(java.util.regex.Pattern.quote(File.pathSeparator))).filter(s -> !s.isEmpty())
                .map(Path::of).filter(FoyHttpPagesTest::modular)
                .distinct().toArray(Path[]::new);
        return ModuleFinder.of(paths).findAll().stream().filter(ref -> names.contains(ref.descriptor().name())
                        || ref.descriptor().name().startsWith("io.vidocq.chappe."))
                .map(ref -> Path.of(ref.location().orElseThrow()).toString()).collect(java.util.stream.Collectors.joining(File.pathSeparator));
    }

    private static boolean modular(Path path) {
        if (Files.isRegularFile(path.resolve("module-info.class"))) return true;
        if (!path.toString().endsWith(".jar")) return false;
        try (var jar = new java.util.jar.JarFile(path.toFile())) {
            return jar.getJarEntry("module-info.class") != null || jar.stream().anyMatch(entry -> entry.getName().matches("META-INF/versions/[0-9]+/module-info.class"));
        } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }

    static class Configuration implements ContainerConfiguration {
        Path directory; String modulePath;
        public void validate() { Objects.requireNonNull(directory); Objects.requireNonNull(modulePath); }
    }
    /** Arquillian deployable-container adapter; application and server run only on the module path. */
    static class PagesContainer implements DeployableContainer<Configuration> {
        private Configuration configuration; private Process process;
        public Class<Configuration> getConfigurationClass() { return Configuration.class; }
        @Override public void setup(Configuration configuration) { configuration.validate(); this.configuration = configuration; }
        public ProtocolDescription getDefaultProtocol() { return new ProtocolDescription("Servlet 3.0"); }
        public ProtocolMetaData deploy(Archive<?> archive) throws DeploymentException {
            try {
                Path jar = configuration.directory.resolve("deployed.jar"); archive.as(ZipExporter.class).exportTo(jar.toFile(), true);
                Path log = configuration.directory.resolve("server.log");
                process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                        "-Dpeano.reflection=deny",
                        "--module-path", configuration.modulePath + File.pathSeparator + configuration.directory.resolve("host"),
                        "--limit-modules", "integration.host,io.vidocq.chappe.core",
                        "--add-modules", "io.vidocq.chappe.core", "-m", "integration.host/host.Main", jar.toString(),
                        configuration.directory.resolve("listener-destroyed.txt").toString())
                        .redirectErrorStream(true).redirectOutput(log.toFile()).start();
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
                while (System.nanoTime() < deadline && process.isAlive()) {
                    String output = Files.readString(log);
                    int start = output.indexOf("READY:");
                    if (start >= 0 && output.indexOf('\n', start) >= 0) {
                        int port = Integer.parseInt(output.substring(start + 6, output.indexOf('\n', start)).trim());
                        return new ProtocolMetaData().addContext(new HTTPContext("127.0.0.1", port));
                    }
                    if (process.waitFor(20, TimeUnit.MILLISECONDS)) break;
                }
                throw new DeploymentException("Server did not start: " + Files.readString(log));
            } catch (Exception failure) { if (failure instanceof DeploymentException deployment) throw deployment; throw new DeploymentException("Cannot deploy modular pages", failure); }
        }
        public void undeploy(Archive<?> archive) throws DeploymentException {
            if (process == null) return;
            try {
                process.getOutputStream().write(1); process.getOutputStream().flush();
                if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new DeploymentException("Server shutdown timed out"); }
                if (process.exitValue() != 0) throw new DeploymentException("Server failed: " + Files.readString(configuration.directory.resolve("server.log")));
            } catch (Exception failure) { if (failure instanceof DeploymentException deployment) throw deployment; throw new DeploymentException("Cannot undeploy pages", failure); }
        }
        @Override public void stop() { if (process != null && process.isAlive()) process.destroyForcibly(); }
    }
}
