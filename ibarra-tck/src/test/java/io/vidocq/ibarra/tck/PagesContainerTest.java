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
package io.vidocq.ibarra.tck;

import io.vidocq.ibarra.tck.fixtures.LibraryHelper;
import io.vidocq.ibarra.tck.fixtures.LibraryTag;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PagesContainerTest {
    @Test
    void compiledCollectionMethodsExposeMetadataAndInvokeWithoutShadowingListProperties() throws Exception {
        var war=ShrinkWrap.create(WebArchive.class,"collection-methods.war")
                .addClass(io.vidocq.ibarra.tck.fixtures.DeferredCollectionTag.class)
                .addAsWebResource(new StringAsset("<taglib xmlns='https://jakarta.ee/xml/ns/jakartaee' version='3.0'>"
                        + "<tlib-version>1.0</tlib-version><short-name>methods</short-name><uri>urn:methods</uri>"
                        + "<tag><name>probe</name><tag-class>io.vidocq.ibarra.tck.fixtures.DeferredCollectionTag</tag-class>"
                        + "<body-content>empty</body-content><attribute><name>action</name><required>true</required>"
                        + "<deferred-method><method-signature>boolean add(Object)</method-signature></deferred-method>"
                        + "</attribute></tag></taglib>"),"WEB-INF/methods.tld")
                .addAsWebResource(new StringAsset("<%@ taglib prefix='m' uri='urn:methods' %>"
                        + "<jsp:useBean id='values' class='java.util.Vector'/>"
                        + "<m:probe action='#{values.add}'/>|${values[0]}|${values.add(0,'first')}|${values[0]}"),"index.jsp");
        var container=new PagesContainer();container.setup(new PagesConfiguration());
        try {
            var http=container.deploy(war).getContext(org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext.class);
            try(var client=HttpClient.newHttpClient()) {
                var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+http.getPort()+"/collection-methods/index.jsp")).build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(200,response.statusCode(),response.body());
                assertEquals("add:boolean:java.lang.Object:0:true:1|x||first",response.body());
            }
            container.undeploy(war);
        } finally { container.stop(); }
    }
    @Test
    void everyPrimitivePropertyBoxesReadsAndCoercesWritesThroughTheEvaluationContext() throws Exception {
        var war=ShrinkWrap.create(WebArchive.class,"primitive-beans.war")
                .addClass(io.vidocq.ibarra.tck.fixtures.PrimitiveBean.class)
                .addAsWebResource(new StringAsset("<jsp:useBean id='bean' class='io.vidocq.ibarra.tck.fixtures.PrimitiveBean'/>"
                        + "<% var el=pageContext.getELContext(); var base=pageContext.getAttribute(\"bean\");"
                        + "String[] names={\"booleanValue\",\"byteValue\",\"charValue\",\"shortValue\",\"intValue\",\"longValue\",\"floatValue\",\"doubleValue\"};"
                        + "String[] values={\"true\",\"12\",\"x\",\"34\",\"56\",\"78\",\"1.5\",\"2.5\"};"
                        + "for(int i=0;i<names.length;i++) el.getELResolver().setValue(el,base,names[i],values[i]); %>"
                        + "${bean.booleanValue}|${bean.byteValue}|${bean.charValue}|${bean.shortValue}|${bean.intValue}|${bean.longValue}|${bean.floatValue}|${bean.doubleValue}"),"index.jsp");
        var container=new PagesContainer();container.setup(new PagesConfiguration());
        try {
            var http=container.deploy(war).getContext(org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext.class);
            try(var client=HttpClient.newHttpClient()) {
                var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+http.getPort()+"/primitive-beans/index.jsp")).build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(200,response.statusCode(),response.body());assertEquals("true|12|x|34|56|78|1.5|2.5",response.body());
            }
            container.undeploy(war);
        } finally { container.stop(); }
    }
    @Test
    void mapResolutionIsPreservedAndUnusedBeansAreNotInitializedByDiscovery() throws Exception {
        var war = ShrinkWrap.create(WebArchive.class,"map-beans.war")
                .addClasses(io.vidocq.ibarra.tck.fixtures.PropertyMap.class,io.vidocq.ibarra.tck.fixtures.MetadataBean.class)
                .addAsWebResource(new StringAsset("<jsp:useBean id='mapping' class='io.vidocq.ibarra.tck.fixtures.PropertyMap'/>${mapping.name}"),"index.jsp");
        var container=new PagesContainer();container.setup(new PagesConfiguration());
        try {
            var http=container.deploy(war).getContext(org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext.class);
            try(var client=HttpClient.newHttpClient()) {
                var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+http.getPort()+"/map-beans/index.jsp")).build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(200,response.statusCode(),response.body());assertEquals("map",response.body());
            }
            container.undeploy(war);
        } finally { container.stop(); }
    }
    @Test
    void privateWarBeanPropertiesUseDirectAccessorServicesWithReflectionDenied() throws Exception {
        var war = ShrinkWrap.create(WebArchive.class, "beans.war")
                .addClasses(io.vidocq.ibarra.tck.fixtures.PropertyBean.class, io.vidocq.ibarra.tck.fixtures.PropertyParent.class, io.vidocq.ibarra.tck.fixtures.PropertyView.class)
                .addAsWebResource(new StringAsset("<%@ page session='false' %><jsp:useBean id='bean' class='io.vidocq.ibarra.tck.fixtures.PropertyBean'/>"
                        + "${bean.name}|${bean.count = '7'}|${bean.count}|${bean.active}|${bean.readOnly}|${bean['empty'] == null}"), "index.jsp")
                .addAsWebResource(new StringAsset("<%@ page session='false' %><jsp:useBean id='bean' class='io.vidocq.ibarra.tck.fixtures.PropertyBean'/>"
                        + "<% var el=pageContext.getELContext(); var base=pageContext.getAttribute(\"bean\");"
                        + "out.print(el.getELResolver().isReadOnly(el,base,\"readOnly\")); out.print(\"|\");"
                        + "out.print(el.getELResolver().getType(el,base,\"readOnly\")==null); %>"), "readonly.jsp");
        var container = new PagesContainer(); container.setup(new PagesConfiguration());
        try {
            var http = container.deploy(war).getContext(org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext.class);
            try (var client = HttpClient.newHttpClient()) {
                var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + http.getPort() + "/beans/index.jsp")).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), response.body()); assertEquals("private|7|7|true|parent|true", response.body());
                response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + http.getPort() + "/beans/readonly.jsp")).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), response.body()); assertEquals("true|true", response.body());
            }
            container.undeploy(war);
        } finally { container.stop(); }
    }
    @Test
    void warLibrariesShareThePrivateApplicationModuleAndDiscoverTheirTlds() throws Exception {
        var library = ShrinkWrap.create(org.jboss.shrinkwrap.api.spec.JavaArchive.class, "tags.jar")
                .addClass(LibraryTag.class)
                .addAsResource(new StringAsset("<taglib xmlns='https://jakarta.ee/xml/ns/jakartaee' version='3.0'><tlib-version>1.0</tlib-version><short-name>lib</short-name><uri>urn:library</uri><tag><name>value</name><tag-class>"
                        + LibraryTag.class.getName() + "</tag-class><body-content>empty</body-content></tag></taglib>"), "META-INF/taglib.tld")
                .addAsResource(new StringAsset("resource"), "library.txt");
        var second = ShrinkWrap.create(org.jboss.shrinkwrap.api.spec.JavaArchive.class, "second.jar")
                .addAsResource(new StringAsset("<taglib xmlns='https://jakarta.ee/xml/ns/jakartaee' version='3.0'><tlib-version>1.0</tlib-version><short-name>second</short-name><uri>urn:second</uri><tag><name>value</name><tag-class>"
                        + LibraryTag.class.getName() + "</tag-class><body-content>empty</body-content></tag></taglib>"), "META-INF/taglib.tld");
        var war = ShrinkWrap.create(WebArchive.class, "libraries.war").addAsLibrary(library)
                .addAsLibrary(second)
                .addClass(LibraryHelper.class)
                .addAsWebResource(new StringAsset("<%@ taglib prefix='lib' uri='urn:library' %><lib:value/>"), "index.jsp")
                .addAsWebResource(new StringAsset("<%@ taglib prefix='lib' uri='/WEB-INF/lib/tags.jar' %><lib:value/>"), "direct.jsp")
                .addAsWebResource(new StringAsset("<%@ taglib prefix='lib' uri='urn:second' %><lib:value/>"), "second.jsp");
        var container = new PagesContainer(); container.setup(new PagesConfiguration());
        try {
            var http = container.deploy(war).getContext(org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext.class);
            try (var client = HttpClient.newHttpClient()) {
                for (String page : java.util.List.of("index.jsp", "direct.jsp", "second.jsp")) {
                    var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + http.getPort() + "/libraries/" + page)).build(), HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, response.statusCode(), response.body()); assertEquals("helper|resource|private", response.body());
                }
            }
            container.undeploy(war);
        } finally { container.stop(); }
    }

    @Test
    void descriptorComponentsUseDirectFactoriesInUnexportedApplicationPackages() throws Exception {
        String servlet = DescriptorServlet.class.getName();
        String filter = DescriptorFilter.class.getName();
        String listener = DescriptorListener.class.getName();
        var war = ShrinkWrap.create(WebArchive.class, "components.war")
                .addClasses(DescriptorServlet.class, DescriptorFilter.class, DescriptorListener.class)
                .setWebXML(new StringAsset("<web-app xmlns='https://jakarta.ee/xml/ns/jakartaee' version='6.1'>"
                        + "<listener><listener-class>" + listener + "</listener-class></listener>"
                        + "<servlet><servlet-name>probe</servlet-name><servlet-class>" + servlet + "</servlet-class><init-param><param-name>value</param-name><param-value>servlet</param-value></init-param><load-on-startup>1</load-on-startup></servlet>"
                        + "<servlet-mapping><servlet-name>probe</servlet-name><url-pattern>/probe</url-pattern></servlet-mapping>"
                        + "<filter><filter-name>prefix</filter-name><filter-class>" + filter + "</filter-class></filter>"
                        + "<filter-mapping><filter-name>prefix</filter-name><url-pattern>/*</url-pattern></filter-mapping></web-app>"))
                .addAsWebResource(new StringAsset("${applicationScope.ready}"), "index.jsp");
        var container = new PagesContainer(); container.setup(new PagesConfiguration());
        try {
            var http = container.deploy(war).getContext(org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext.class);
            try (var client = HttpClient.newHttpClient()) {
                for (String path : java.util.List.of("probe", "index.jsp")) {
                    var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + http.getPort() + "/components/" + path)).build(), HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, response.statusCode(), response.body());
                    assertEquals(path.equals("probe") ? "filter|servlet|listener|private" : "filter|listener", response.body());
                }
            }
            container.undeploy(war);
        } finally { container.stop(); }
    }

    public static class DescriptorServlet extends jakarta.servlet.http.HttpServlet {
        public DescriptorServlet() throws java.io.IOException { }
        @Override protected void doGet(jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
            var module = getClass().getModule();
            if (!module.isNamed() || module.isExported(getClass().getPackageName()) || module.isOpen(getClass().getPackageName()))
                throw new IllegalStateException("Application package must remain private");
            response.getWriter().write(getInitParameter("value") + "|" + getServletContext().getAttribute("ready") + "|private");
        }
    }

    public static class DescriptorFilter implements jakarta.servlet.Filter {
        @Override public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response, jakarta.servlet.FilterChain chain) throws java.io.IOException, jakarta.servlet.ServletException {
            response.getWriter().write("filter|"); chain.doFilter(request, response);
        }
    }

    public static class DescriptorListener implements jakarta.servlet.ServletContextListener {
        @Override public void contextInitialized(jakarta.servlet.ServletContextEvent event) {
            event.getServletContext().setAttribute("ready", "listener");
        }
    }

    @Test
    void invalidComponentConstructorsFailBeforeStartingTheServer() throws Exception {
        var container = new PagesContainer(); container.setup(new PagesConfiguration());
        try {
            for (Class<?> type : java.util.List.of(AbstractServlet.class, MissingConstructorServlet.class, PrivateConstructorServlet.class)) {
                var war = ShrinkWrap.create(WebArchive.class, "invalid.war").addClass(type)
                        .setWebXML(new StringAsset("<web-app><servlet><servlet-name>invalid</servlet-name><servlet-class>"
                                + type.getName() + "</servlet-class></servlet></web-app>"));
                var failure = assertThrows(org.jboss.arquillian.container.spi.client.container.DeploymentException.class, () -> container.deploy(war));
                assertTrue(failure.getCause().getMessage().contains("public no-arg constructor"), failure.toString());
            }
            var war = ShrinkWrap.create(WebArchive.class, "reusable.war").addAsWebResource(new StringAsset("ready"), "index.jsp");
            assertNotNull(container.deploy(war)); container.undeploy(war);
        } finally { container.stop(); }
    }

    public abstract static class AbstractServlet extends jakarta.servlet.http.HttpServlet { }
    public static class MissingConstructorServlet extends jakarta.servlet.http.HttpServlet {
        public MissingConstructorServlet(String value) { }
    }
    public static class PrivateConstructorServlet extends jakarta.servlet.http.HttpServlet {
        private PrivateConstructorServlet() { }
    }

    @Test
    void invalidPagesFailOnlyTheirRequestsWhileValidPagesKeepRendering() throws Exception {
        var war = ShrinkWrap.create(WebArchive.class, "partial.war")
                .addAsWebResource(new StringAsset("ready"), "index.jsp")
                .addAsWebResource(new StringAsset("<%@ page session='perhaps' %>"), "parse.jsp")
                .addAsWebResource(new StringAsset("${1+1}<% int x = ; %>"), "javac.jsp");
        var container = new PagesContainer(); container.setup(new PagesConfiguration());
        try {
            var http = container.deploy(war).getContext(org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext.class);
            try (var client = HttpClient.newHttpClient()) {
                for (String path : java.util.List.of("parse.jsp", "javac.jsp", "parse.jsp?jsp_precompile=true")) {
                    var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + http.getPort() + "/partial/" + path)).build(), HttpResponse.BodyHandlers.ofString());
                    assertEquals(500, response.statusCode(), path);
                }
                var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + http.getPort() + "/partial/index.jsp")).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode()); assertEquals("ready", response.body());
            }
            container.undeploy(war);
        } finally { container.stop(); }
    }

    @Test
    void descriptorGroupsApplyPrecedenceAndImplicitIncludesBeforeServing() throws Exception {
        var war = ShrinkWrap.create(WebArchive.class, "configuration.war")
                .setWebXML(new StringAsset("<web-app xmlns='https://jakarta.ee/xml/ns/jakartaee' version='6.1'><jsp-config><jsp-property-group><url-pattern>*.jsp</url-pattern><el-ignored>true</el-ignored><include-prelude>/head.jspf</include-prelude><include-coda>/tail.jspf</include-coda></jsp-property-group><jsp-property-group><url-pattern>/index.jsp</url-pattern><el-ignored>false</el-ignored></jsp-property-group></jsp-config></web-app>"))
                .addAsWebResource(new StringAsset("${param.name}"), "index.jsp")
                .addAsWebResource(new StringAsset("${param.name}"), "literal.jsp")
                .addAsWebResource(new StringAsset("head|"), "head.jspf")
                .addAsWebResource(new StringAsset("|tail"), "tail.jspf");
        var container = new PagesContainer(); container.setup(new PagesConfiguration());
        try {
            var http = container.deploy(war).getContext(org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext.class);
            try (var client = HttpClient.newHttpClient()) {
                for (String page : java.util.List.of("index.jsp", "literal.jsp")) {
                    var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + http.getPort() + "/configuration/" + page + "?name=compiled")).build(), HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, response.statusCode(), response.body());
                    assertEquals(page.equals("index.jsp") ? "head|compiled|tail" : "head|${param.name}|tail", response.body());
                }
            }
            container.undeploy(war);
        } finally { container.stop(); }
    }

    @Test
    void legacyDescriptorKeepsTemplateElLiteral() throws Exception {
        var war = ShrinkWrap.create(WebArchive.class, "legacy.war")
                .setWebXML(new StringAsset("<!DOCTYPE web-app PUBLIC '-//Sun Microsystems, Inc.//DTD Web Application 2.3//EN' 'http://127.0.0.1:1/never.dtd'><web-app><display-name>legacy</display-name></web-app>"))
                .addAsWebResource(new StringAsset("${param.name}"), "index.jsp");
        var container = new PagesContainer(); container.setup(new PagesConfiguration());
        try {
            var http = container.deploy(war).getContext(org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext.class);
            try (var client = HttpClient.newHttpClient()) {
                var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + http.getPort() + "/legacy/index.jsp?name=compiled")).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), response.body()); assertEquals("${param.name}", response.body());
            }
            container.undeploy(war);
        } finally { container.stop(); }
    }
    @Test
    void precompilesWarAndServesItInAnIsolatedNamedModule() throws Exception {
        var war = ShrinkWrap.create(WebArchive.class, "pages-probe.war")
                .addAsWebResource(new StringAsset("<%@ page contentType=\"text/plain\" %><%= jakarta.servlet.jsp.JspFactory.getDefaultFactory().getEngineInfo().getSpecificationVersion() %>|${param.name}"), "index.jsp");
        var container = new PagesContainer();
        container.setup(new PagesConfiguration());
        container.start();
        try {
            var metadata = container.deploy(war);
            var http = metadata.getContext(org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext.class);
            try (var client = HttpClient.newHttpClient()) {
                var response = client.send(HttpRequest.newBuilder(URI.create("http://" + http.getHost() + ":" + http.getPort() + "/pages-probe/index.jsp?name=compiled")).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), response.body());
                assertEquals("4.0|compiled", response.body());
            }
            container.undeploy(war);
            assertThrows(IllegalArgumentException.class, () -> container.deploy(ShrinkWrap.create(org.jboss.shrinkwrap.api.spec.JavaArchive.class)));
        } finally { container.stop(); }
    }

    @Test
    void extractedResourcesAreVisibleToThePageAndStopPreservesInterruption() throws Exception {
        var war = ShrinkWrap.create(WebArchive.class, "resources.war")
                .addAsWebResource(new StringAsset("<%@ page contentType=\"text/plain\" %><% try (var input=application.getResourceAsStream(\"/answer.txt\")) { out.write(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)); } %>"), "index.jsp")
                .addAsWebResource(new StringAsset("archive-resource"), "answer.txt");
        var container = new PagesContainer(); container.setup(new PagesConfiguration());
        try {
            var http = container.deploy(war).getContext(org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext.class);
            try (var client = HttpClient.newHttpClient()) {
                var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + http.getPort() + "/resources/index.jsp")).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), response.body()); assertEquals("archive-resource", response.body());
            }
            Thread.currentThread().interrupt();
            container.stop();
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); container.stop(); }
    }

    @Test
    void multipleDeploymentsHaveIndependentPortsAndLifecycles() throws Exception {
        var first = ShrinkWrap.create(WebArchive.class, "first.war").addAsWebResource(new StringAsset("first"), "index.jsp");
        var second = ShrinkWrap.create(WebArchive.class, "second.war").addAsWebResource(new StringAsset("second"), "index.jsp");
        var container = new PagesContainer(); container.setup(new PagesConfiguration());
        try {
            var one = container.deploy(first).getContext(org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext.class);
            var two = container.deploy(second).getContext(org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext.class);
            assertNotEquals(one.getPort(), two.getPort());
            container.undeploy(first);
            try (var client = HttpClient.newHttpClient()) {
                var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + two.getPort() + "/second/index.jsp")).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode()); assertEquals("second", response.body());
            }
            container.undeploy(second);
        } finally { container.stop(); }
    }

    @Test
    void unsupportedDeploymentFeaturesFailExplicitlyAndLeaveTheAdapterReusable() throws Exception {
        var container = new PagesContainer(); container.setup(new PagesConfiguration());
        try {
            var unsupported = ShrinkWrap.create(WebArchive.class, "components.war").setWebXML(new StringAsset("<web-app xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"6.1\"><servlet><servlet-name>probe</servlet-name><servlet-class>app.Probe</servlet-class></servlet></web-app>"));
            var failure = assertThrows(org.jboss.arquillian.container.spi.client.container.DeploymentException.class, () -> container.deploy(unsupported));
            assertTrue(failure.getCause().getMessage().contains("Component class is missing"));
            var configured = ShrinkWrap.create(WebArchive.class, "configuration.war").setWebXML(new StringAsset("<web-app xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"6.1\"><jsp-config><jsp-property-group><url-pattern>*.jsp</url-pattern><el-ignored>maybe</el-ignored></jsp-property-group></jsp-config></web-app>"));
            var configurationFailure = assertThrows(org.jboss.arquillian.container.spi.client.container.DeploymentException.class, () -> container.deploy(configured));
            assertTrue(configurationFailure.getCause().getMessage().contains("true or false"));
            var annotated = ShrinkWrap.create(WebArchive.class, "annotations.war").addClass(AnnotatedServlet.class);
            var annotationFailure = assertThrows(org.jboss.arquillian.container.spi.client.container.DeploymentException.class, () -> container.deploy(annotated));
            assertTrue(annotationFailure.getCause().getMessage().contains("annotations"));
            var supported = ShrinkWrap.create(WebArchive.class, "recovery.war").addAsWebResource(new StringAsset("ready"), "index.jsp");
            assertNotNull(container.deploy(supported)); container.undeploy(supported);
        } finally { container.stop(); }
    }

    @jakarta.servlet.annotation.WebServlet("/annotated")
    public static class AnnotatedServlet extends jakarta.servlet.http.HttpServlet { }
}
