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
package io.vidocq.ibarra.arquillian;

import io.vidocq.ibarra.arquillian.fixtures.LibraryHelper;
import io.vidocq.ibarra.arquillian.fixtures.LibraryTag;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PagesContainerTest {
    @Test
    void hostCommandWithoutConfigurationIsTheHistoricalOne() {
        assertEquals(List.of("/jdk/bin/java", "-Dpeano.reflection=deny", "--module-path", "/r1" + File.pathSeparator + "/r2",
                "--limit-modules", "tck.host,io.vidocq.chappe.core", "--add-modules", "io.vidocq.chappe.core",
                "-m", "tck.host/host.Main", "/w/application", "/w/web", "/ctx"),
            PagesContainer.hostCommand(Path.of("/jdk/bin/java"), List.of(Path.of("/r1"), Path.of("/r2")), List.of(),
                List.of(Path.of("/w/application")), Path.of("/w/web"), "/ctx"));
    }

    @Test
    void hostCommandAppendsJdkModules() {
        var command = PagesContainer.hostCommand(Path.of("/jdk/bin/java"), List.of(Path.of("/r1")),
                List.of("java.naming", "jdk.localedata"), List.of(Path.of("/w/application")), Path.of("/w/web"), "/ctx");
        assertEquals("tck.host,io.vidocq.chappe.core,java.naming,jdk.localedata",
                command.get(command.indexOf("--limit-modules") + 1));
        assertEquals("io.vidocq.chappe.core,java.naming,jdk.localedata", command.get(command.indexOf("--add-modules") + 1));
    }

    @Test
    void xmlTagFileDeclarationKeepsItsUtf8EncodingWithoutChangingTheResponseCharset() throws Exception {
        String jsp = "<%@ page contentType='text/plain' %><%@ taglib prefix='t' tagdir='/WEB-INF/tags' %><t:declaration/>";
        String utf8Jsp = "<%@ page contentType='text/plain;charset=UTF-8' %>"
                + "<%@ taglib prefix='t' tagdir='/WEB-INF/tags' %><t:declaration/>";
        String tagx = "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' version='2.0'>"
                + "<jsp:output omit-xml-declaration='false'/></jsp:root>";
        var war = ShrinkWrap.create(WebArchive.class, "tag-file-encoding.war")
                .addAsWebResource(new StringAsset(jsp), "index.jsp")
                .addAsWebResource(new StringAsset(utf8Jsp), "utf8.jsp")
                .addAsWebResource(new StringAsset(tagx), "WEB-INF/tags/declaration.tagx");
        var container = new PagesContainer(); container.setup(new PagesConfiguration());
        try {
            var http = container.deploy(war).getContext(org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext.class);
            for (var path : List.of("index.jsp", "utf8.jsp")) {
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + http.getPort()
                        + "/tag-file-encoding/" + path)).GET().build();
                var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), response.body());
                String expectedCharset = path.equals("index.jsp") ? "ISO-8859-1" : "UTF-8";
                assertTrue(response.headers().firstValue("content-type").orElse("")
                        .contains("charset=" + expectedCharset), response.headers().toString());
                assertTrue(response.body().contains("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"), response.body());
            }
        } finally { container.undeploy(war); }
    }

    @Test
    void nestedIncludedForwardPreservesApplicationHeaderAndCachedWriterTransformations() throws Exception {
        var war = ShrinkWrap.create(WebArchive.class, "typed-forward.war")
                .addClasses(io.vidocq.ibarra.arquillian.fixtures.ForwardResponseFilter.class,
                        io.vidocq.ibarra.arquillian.fixtures.ForwardResponseFilter.BodyWriter.class,
                        io.vidocq.ibarra.arquillian.fixtures.ForwardResponseFilter.WrappedResponse.class)
                .setWebXML(new StringAsset("<web-app xmlns='https://jakarta.ee/xml/ns/jakartaee' version='6.1'>"
                        + "<filter><filter-name>wrap</filter-name><filter-class>io.vidocq.ibarra.arquillian.fixtures.ForwardResponseFilter</filter-class></filter>"
                        + "<filter-mapping><filter-name>wrap</filter-name><url-pattern>/*</url-pattern><dispatcher>INCLUDE</dispatcher></filter-mapping></web-app>"))
                .addAsWebResource(new StringAsset("discard-parent<jsp:include page='/nested/middle.jsp' flush='false'/>"
                        + "<% application.setAttribute(\"after-parent\", true); %>"), "index.jsp")
                .addAsWebResource(new StringAsset("discard-middle<jsp:include page='/nested/child.jsp' flush='false'/>"
                        + "<% application.setAttribute(\"after-middle\", true); %>"), "nested/middle.jsp")
                .addAsWebResource(new StringAsset("discard-child<jsp:forward page='/dest/target.jsp'/>"), "nested/child.jsp")
                .addAsWebResource(new StringAsset("<% response.setHeader(\"X-Target\", \"target\"); %>target<jsp:include page='part.jsp' flush='false'/>"), "dest/target.jsp")
                .addAsWebResource(new StringAsset("part"), "dest/part.jsp")
                .addAsWebResource(new StringAsset("discard-missing<jsp:include page='/nested/missing.jsp' flush='false'/>"), "missing.jsp")
                .addAsWebResource(new StringAsset("<jsp:forward page='/absent.bin'/>"), "nested/missing.jsp")
                .addAsWebResource(new StringAsset("<%=application.getAttribute(\"after-parent\")%>|<%=application.getAttribute(\"after-middle\")%>"), "state.jsp");
        var container = new PagesContainer(); container.setup(new PagesConfiguration());
        try {
            var http = container.deploy(war).getContext(org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext.class);
            String base = "http://127.0.0.1:" + http.getPort() + "/typed-forward/";
            try (var client = HttpClient.newHttpClient()) {
                var response = client.send(HttpRequest.newBuilder(URI.create(base + "index.jsp")).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), response.body()); assertEquals("TARGETPART", response.body());
                assertEquals("TARGET", response.headers().firstValue("X-Target").orElse("missing"));
                var state = client.send(HttpRequest.newBuilder(URI.create(base + "state.jsp")).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals("null|null", state.body());
                var missing = client.send(HttpRequest.newBuilder(URI.create(base + "missing.jsp")).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(404, missing.statusCode(), missing.body()); assertFalse(missing.body().contains("discard-missing"));
            }
            container.undeploy(war);
        } finally { container.stop(); }
    }

    @Test
    void precompileRequestsValidateDecodedValuesAndNeverExecuteThePageThroughRoutesOrAliases() throws Exception {
        var war=ShrinkWrap.create(WebArchive.class,"precompile-protocol.war")
                .setWebXML(new StringAsset("<web-app xmlns='https://jakarta.ee/xml/ns/jakartaee' version='6.1'>"
                        + "<servlet><servlet-name>alias</servlet-name><jsp-file>/index.jsp</jsp-file></servlet>"
                        + "<servlet-mapping><servlet-name>alias</servlet-name><url-pattern>/alias</url-pattern></servlet-mapping></web-app>"))
                .addAsWebResource(new StringAsset("<%@ page session='false' %><%int count=application.getAttribute(\"executions\")==null?0:(Integer)application.getAttribute(\"executions\");"
                        + "application.setAttribute(\"executions\",count+1);out.print(\"executed-\"+(count+1));%>"),"index.jsp")
                .addAsWebResource(new StringAsset("<%@ page session='false' %><%=application.getAttribute(\"executions\")==null?0:application.getAttribute(\"executions\")%>"),"count.jsp")
                .addAsWebResource(new StringAsset("secret"),"WEB-INF/private.jsp");
        var container=new PagesContainer();container.setup(new PagesConfiguration());
        try {
            var http=container.deploy(war).getContext(org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext.class);
            String base="http://127.0.0.1:"+http.getPort()+"/precompile-protocol/";
            try(var client=HttpClient.newHttpClient()) {
                for(String path:java.util.List.of("index.jsp","alias")) {
                    for(String query:java.util.List.of("jsp_precompile","jsp_precompile=","jsp_precompile=true","jsp_precompile=false",
                            "test=other&jsp_precompile=true","jsp_precompile=%74rue","jsp%5Fprecompile=true","jsp_precompile=true&jsp_precompile=false")) {
                        var response=client.send(HttpRequest.newBuilder(URI.create(base+path+"?"+query)).build(),HttpResponse.BodyHandlers.ofString());
                        assertEquals(200,response.statusCode(),path+"?"+query);assertEquals("",response.body(),path+"?"+query);
                    }
                    for(String query:java.util.List.of("jsp_precompile=invalid","jsp_precompile=TRUE","jsp_precompile=true%20","jsp_precompile=true&jsp_precompile=invalid")) {
                        var response=client.send(HttpRequest.newBuilder(URI.create(base+path+"?"+query)).build(),HttpResponse.BodyHandlers.ofString());
                        assertEquals(500,response.statusCode(),path+"?"+query);
                    }
                }
                var response=client.send(HttpRequest.newBuilder(URI.create(base+"count.jsp")).build(),HttpResponse.BodyHandlers.ofString());
                assertEquals("0",response.body());
                for(String path:java.util.List.of("missing.jsp?jsp_precompile=true","WEB-INF/private.jsp?jsp_precompile=invalid")) {
                    response=client.send(HttpRequest.newBuilder(URI.create(base+path)).build(),HttpResponse.BodyHandlers.ofString());assertEquals(404,response.statusCode(),path);
                }
                response=client.send(HttpRequest.newBuilder(URI.create(base+"index.jsp")).build(),HttpResponse.BodyHandlers.ofString());assertEquals("executed-1",response.body());
                response=client.send(HttpRequest.newBuilder(URI.create(base+"alias")).build(),HttpResponse.BodyHandlers.ofString());assertEquals("executed-2",response.body());
            }
            container.undeploy(war);
        } finally { container.stop(); }
    }

    @Test
    void propertyEditorsKeepTheDesktopReadAfterCollectionProviderRegistration() throws Exception {
        var war = ShrinkWrap.create(WebArchive.class, "editor-collections.war")
                .addClasses(io.vidocq.ibarra.arquillian.fixtures.EditorBean.class,
                        io.vidocq.ibarra.arquillian.fixtures.EditorBeanBeanInfo.class, io.vidocq.ibarra.arquillian.fixtures.EditorValue.class)
                .addAsWebResource(new StringAsset("<jsp:useBean id='bean' class='io.vidocq.ibarra.arquillian.fixtures.EditorBean'/>"
                        + "<jsp:useBean id='values' class='java.util.Vector'/>"
                        + "<jsp:setProperty name='bean' property='value' value='text'/>"
                        + "<jsp:getProperty name='bean' property='value'/>|${values.size()}|"
                        + "<%=bean.getClass().getModule().getDescriptor().requires().stream().anyMatch(read -> read.name().equals(\"java.desktop\"))%>"
                        + "<% var descriptor = bean.getClass().getModule().getDescriptor();"
                        + " var exports = descriptor.exports(); var beanExport = exports.stream().findFirst().orElse(null);"
                        + " if (!descriptor.opens().isEmpty() || exports.size() != 1 || beanExport == null || !beanExport.source().equals(bean.getClass().getPackageName())"
                        + " || !beanExport.targets().equals(java.util.Set.of(\"jakarta.el\")))"
                        + " throw new AssertionError(\"Bean packages must be qualified-exported only to Jakarta EL\"); %>"), "index.jsp");
        var container = new PagesContainer(); container.setup(new PagesConfiguration());
        try {
            var http = container.deploy(war).getContext(org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext.class);
            try (var client = HttpClient.newHttpClient()) {
                var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + http.getPort()
                        + "/editor-collections/index.jsp")).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), response.body());
                assertEquals("edited:text|0|true", response.body());
            }
            container.undeploy(war);
        } finally { container.stop(); }
    }

    @Test
    void compiledForwardsDistinguishPendingBufferedOutputFromUnbufferedWritesAndRouteErrors() throws Exception {
        var war=ShrinkWrap.create(WebArchive.class,"forward-output.war")
                .addAsWebResource(new StringAsset("<%@ page buffer='none' %><jsp:forward page='target.jsp'/>"),"empty.jsp")
                .addAsWebResource(new StringAsset("discard<jsp:forward page='target.jsp'/>"),"buffered.jsp")
                .addAsWebResource(new StringAsset("<%@ page buffer='none' %>sent<%try { pageContext.forward(\"target.jsp\"); }"
                        + "catch(IllegalStateException expected) { out.print(\"|blocked\"); }%>"),"unbuffered.jsp")
                .addAsWebResource(new StringAsset("<%@ page buffer='none' errorPage='error.jsp' %>sent|<jsp:forward page='target.jsp'/>"),"failure.jsp")
                .addAsWebResource(new StringAsset("<%@ page isErrorPage='true' %><%=exception instanceof IllegalStateException%>"),"error.jsp")
                .addAsWebResource(new StringAsset("target"),"target.jsp");
        var container=new PagesContainer();container.setup(new PagesConfiguration());
        try {
            var http=container.deploy(war).getContext(org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext.class);
            try(var client=HttpClient.newHttpClient()) {
                for(var entry:java.util.Map.of("empty.jsp","target","buffered.jsp","target","unbuffered.jsp","sent|blocked","failure.jsp","sent|true").entrySet()) {
                    var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+http.getPort()+"/forward-output/"+entry.getKey())).build(),HttpResponse.BodyHandlers.ofString());
                    assertEquals(200,response.statusCode(),entry.getKey()+": "+response.body());assertEquals(entry.getValue(),response.body(),entry.getKey());
                }
            }
            container.undeploy(war);
        } finally { container.stop(); }
    }
    @Test
    void compiledCollectionMethodsExposeMetadataAndInvokeWithoutShadowingListProperties() throws Exception {
        var war=ShrinkWrap.create(WebArchive.class,"collection-methods.war")
                .addClass(io.vidocq.ibarra.arquillian.fixtures.DeferredCollectionTag.class)
                .addAsWebResource(new StringAsset("<taglib xmlns='https://jakarta.ee/xml/ns/jakartaee' version='3.0'>"
                        + "<tlib-version>1.0</tlib-version><short-name>methods</short-name><uri>urn:methods</uri>"
                        + "<tag><name>probe</name><tag-class>io.vidocq.ibarra.arquillian.fixtures.DeferredCollectionTag</tag-class>"
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
                .addClass(io.vidocq.ibarra.arquillian.fixtures.PrimitiveBean.class)
                .addAsWebResource(new StringAsset("<jsp:useBean id='bean' class='io.vidocq.ibarra.arquillian.fixtures.PrimitiveBean'/>"
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
                .addClasses(io.vidocq.ibarra.arquillian.fixtures.PropertyMap.class,io.vidocq.ibarra.arquillian.fixtures.MetadataBean.class)
                .addAsWebResource(new StringAsset("<jsp:useBean id='mapping' class='io.vidocq.ibarra.arquillian.fixtures.PropertyMap'/>${mapping.name}"),"index.jsp");
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
                .addClasses(io.vidocq.ibarra.arquillian.fixtures.PropertyBean.class, io.vidocq.ibarra.arquillian.fixtures.PropertyParent.class, io.vidocq.ibarra.arquillian.fixtures.PropertyView.class)
                .addAsWebResource(new StringAsset("<%@ page session='false' %><jsp:useBean id='bean' class='io.vidocq.ibarra.arquillian.fixtures.PropertyBean'/>"
                        + "${bean.name}|${bean.count = '7'}|${bean.count}|${bean.active}|${bean.readOnly}|${bean['empty'] == null}"), "index.jsp")
                .addAsWebResource(new StringAsset("<%@ page session='false' %><jsp:useBean id='bean' class='io.vidocq.ibarra.arquillian.fixtures.PropertyBean'/>"
                        + "<% var module=bean.getClass().getModule(); var packageName=bean.getClass().getPackageName();"
                        + "var elModule=jakarta.el.BeanELResolver.class.getModule();"
                        + "if (!module.isExported(packageName,elModule) || module.isExported(packageName) || module.isOpen(packageName))"
                        + "throw new AssertionError(\"Bean package must be qualified-exported only to Jakarta EL\");"
                        + "var resolver=new jakarta.el.BeanELResolver(); var el=pageContext.getELContext();"
                        + "out.print(resolver.getValue(el,bean,\"name\")); out.print(\"|\");"
                        + "out.print(resolver.getType(el,bean,\"count\").getName());"
                        + "resolver.setValue(el,bean,\"count\",Integer.valueOf(9)); out.print(\"|\");"
                        + "out.print(resolver.getValue(el,bean,\"count\")); %>"), "bean-resolver.jsp")
                .addAsWebResource(new StringAsset("<%@ page session='false' %><jsp:useBean id='bean' class='io.vidocq.ibarra.arquillian.fixtures.PropertyBean'/>"
                        + "<% var el=pageContext.getELContext(); var base=pageContext.getAttribute(\"bean\");"
                        + "out.print(el.getELResolver().isReadOnly(el,base,\"readOnly\")); out.print(\"|\");"
                        + "out.print(el.getELResolver().getType(el,base,\"readOnly\")==null); %>"), "readonly.jsp");
        var container = new PagesContainer(); container.setup(new PagesConfiguration());
        try {
            var http = container.deploy(war).getContext(org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext.class);
            try (var client = HttpClient.newHttpClient()) {
                var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + http.getPort() + "/beans/index.jsp")).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), response.body()); assertEquals("private|7|7|true|parent|true", response.body());
                response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + http.getPort() + "/beans/bean-resolver.jsp")).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), response.body()); assertEquals("private|int|9", response.body());
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
    void prefixPropertyGroupMappingResolvesTheFullPagePath() throws Exception {
        var war = ShrinkWrap.create(WebArchive.class, "prefix-group.war")
                .setWebXML(new StringAsset("<web-app xmlns='https://jakarta.ee/xml/ns/jakartaee' version='6.1'><jsp-config><jsp-property-group><url-pattern>/pages/*</url-pattern></jsp-property-group></jsp-config></web-app>"))
                .addAsWebResource(new StringAsset("<%@ page contentType='text/plain' %>prefix page"), "pages/index.jsp")
                .addAsWebResource(new StringAsset("<%@ page contentType='text/plain' %><jsp:include page='/pages/included.jsp'/>"), "include.jsp")
                .addAsWebResource(new StringAsset("<%@ page contentType='text/plain' %>included page"), "pages/included.jsp");
        var container = new PagesContainer(); container.setup(new PagesConfiguration());
        try {
            var http = container.deploy(war).getContext(org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext.class);
            try (var client = HttpClient.newHttpClient()) {
                for (var request : java.util.List.of(java.util.Map.entry("pages/index.jsp", "prefix page"),
                        java.util.Map.entry("include.jsp", "included page"))) {
                    var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + http.getPort()
                            + "/prefix-group/" + request.getKey())).GET().build(), HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, response.statusCode(), response.body());
                    assertEquals(request.getValue(), response.body());
                }
            }
            container.undeploy(war);
        } finally { container.stop(); }
    }

    @Test
    void nestedRelativeIncludesResolvePrefixPropertyGroupPaths() throws Exception {
        var war = ShrinkWrap.create(WebArchive.class, "nested-prefix-group.war")
                .setWebXML(new StringAsset("<web-app xmlns='https://jakarta.ee/xml/ns/jakartaee' version='6.1'><jsp-config><jsp-property-group><url-pattern>/include/*</url-pattern></jsp-property-group></jsp-config></web-app>"))
                .addAsWebResource(new StringAsset("<%@ page contentType='text/plain' %>A:<jsp:include page='include/dynamic.jsp'/>"), "dynamic.jsp")
                .addAsWebResource(new StringAsset("<%@ page contentType='text/plain' %>B:<jsp:include page='C.jsp'/>"), "include/dynamic.jsp")
                .addAsWebResource(new StringAsset("C"), "include/C.jsp");
        var container = new PagesContainer(); container.setup(new PagesConfiguration());
        try {
            var http = container.deploy(war).getContext(org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext.class);
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + http.getPort()
                    + "/nested-prefix-group/dynamic.jsp")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            assertEquals("A:B:C", response.body());
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
