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
            assertTrue(failure.getCause().getMessage().contains("factories"));
            var configured = ShrinkWrap.create(WebArchive.class, "configuration.war").setWebXML(new StringAsset("<web-app xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"6.1\"><jsp-config><jsp-property-group><url-pattern>*.jsp</url-pattern><el-ignored>true</el-ignored></jsp-property-group></jsp-config></web-app>"));
            var configurationFailure = assertThrows(org.jboss.arquillian.container.spi.client.container.DeploymentException.class, () -> container.deploy(configured));
            assertTrue(configurationFailure.getCause().getMessage().contains("property groups"));
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
