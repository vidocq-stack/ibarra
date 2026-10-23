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

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jboss.arquillian.container.spi.ConfigurationException;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code jdkModules} and {@code applicationOpens} hooks: JDK modules in the server boot layer, opened application packages. */
class ApplicationModuleOptionsTest {
    private static final String TEST_CLASS_PATH = System.getProperty("jdk.module.path", "") + File.pathSeparator
            + System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));

    @TempDir
    Path temp;

    @Test
    void jdkModulesJoinTheServerBootLayer() throws Exception {
        Path jar = FixtureModules.explicitModule(temp, "fixture.naming",
                "requires jakarta.servlet.jsp; requires java.naming; exports fixture.naming;",
                Map.of("fixture/naming/NamingTag.java", """
                        package fixture.naming;
                        public class NamingTag extends jakarta.servlet.jsp.tagext.SimpleTagSupport {
                            @Override public void doTag() throws java.io.IOException {
                                getJspContext().getOut().write(javax.naming.Context.class.getModule().getName());
                            }
                        }
                        """),
                Map.of("META-INF/naming.tld", "<taglib xmlns='https://jakarta.ee/xml/ns/jakartaee' version='3.0'>"
                        + "<tlib-version>1.0</tlib-version><short-name>n</short-name><uri>urn:naming</uri>"
                        + "<tag><name>module</name><tag-class>fixture.naming.NamingTag</tag-class>"
                        + "<body-content>empty</body-content></tag></taglib>"));
        String searchPath = TEST_CLASS_PATH + File.pathSeparator + jar;
        var war = ShrinkWrap.create(WebArchive.class, "naming.war")
                .addAsWebResource(new StringAsset("<%@ taglib prefix=\"n\" uri=\"urn:naming\" %><n:module/>"), "index.jsp");

        var withoutJdkModules = configuration();
        withoutJdkModules.setApplicationLibraries("fixture.naming");
        var limited = new PagesContainer(searchPath);
        limited.setup(withoutJdkModules);
        try {
            var failure = assertThrows(DeploymentException.class, () -> limited.deploy(war));
            assertTrue(failure.getCause().getMessage().contains("java.naming"), failure.getCause().getMessage());
        } finally {
            limited.stop();
        }

        var withJdkModules = configuration();
        withJdkModules.setApplicationLibraries("fixture.naming");
        withJdkModules.setJdkModules("java.naming");
        assertEquals("java.naming", serve(new PagesContainer(searchPath), withJdkModules, war));
    }

    @Test
    void openedPackageSurvivesCollectionTables() throws Exception {
        Path bundles = FixtureModules.automaticModule(temp, "bundles.jar",
                Map.of("fixture/bundles/Messages.java", "package fixture.bundles; public class Messages { }"),
                Map.of("fixture/bundles/messages.properties", "greeting=hello"));
        var war = ShrinkWrap.create(WebArchive.class, "opened.war")
                .merge(ShrinkWrap.createFromZipFile(JavaArchive.class, bundles.toFile()), "/WEB-INF/classes")
                .addAsWebResource(new StringAsset("<% java.util.List<String> items = new java.util.ArrayList<>(); %>"
                        + "<%= getClass().getModule().isOpen(\"fixture.bundles\") %>"), "index.jsp");
        var opened = configuration();
        opened.setApplicationOpens("fixture.bundles");

        assertEquals("true", serve(new PagesContainer(), opened, war));
    }

    @Test
    void absentPackageToOpenFailsTheDeploymentAndLeavesTheAdapterReusable() throws Exception {
        var container = new PagesContainer();
        var absent = configuration();
        absent.setApplicationOpens("fixture.absent");
        container.setup(absent);
        try {
            // A subpackage alone does not make fixture.absent a package of the application.
            var war = ShrinkWrap.create(WebArchive.class, "absent.war")
                    .addAsWebResource(new StringAsset("ready"), "index.jsp")
                    .addAsWebResource(new StringAsset("nested"), "WEB-INF/classes/fixture/absent/nested/note.txt");
            var failure = assertThrows(DeploymentException.class, () -> container.deploy(war));
            assertTrue(failure.getCause().getMessage().contains("fixture.absent"), failure.getCause().getMessage());

            container.setup(configuration());
            var ready = ShrinkWrap.create(WebArchive.class, "ready.war").addAsWebResource(new StringAsset("ready"), "index.jsp");
            assertEquals("ready", get(container, ready));
            container.undeploy(ready);
        } finally {
            container.stop();
        }
    }

    @Test
    void nonJdkModuleFailsValidation() {
        var configuration = configuration();
        configuration.setJdkModules("io.vidocq.foy.core");

        var failure = assertThrows(ConfigurationException.class, configuration::validate);
        assertTrue(failure.getMessage().contains("io.vidocq.foy.core"), failure.getMessage());
        assertTrue(failure.getMessage().contains("is not a JDK module"), failure.getMessage());

        // java.compiler is refused by the host guard; jdk.compiler would bring it into the boot layer.
        for (String tooling : List.of("java.compiler", "jdk.compiler")) {
            var buildTooling = configuration();
            buildTooling.setJdkModules("java.naming, " + tooling);
            var refused = assertThrows(ConfigurationException.class, buildTooling::validate);
            assertTrue(refused.getMessage().contains(tooling), refused.getMessage());
            assertTrue(refused.getMessage().contains("build tooling"), refused.getMessage());
        }
        var jdkModule = configuration();
        jdkModule.setJdkModules("java.naming, jdk.localedata");
        assertDoesNotThrow(jdkModule::validate);

        var invalid = configuration();
        invalid.setApplicationOpens("not a package");
        var badPackage = assertThrows(ConfigurationException.class, invalid::validate);
        assertTrue(badPackage.getMessage().contains("not a package"), badPackage.getMessage());
    }

    private static PagesConfiguration configuration() {
        return new PagesConfiguration();
    }

    /** Sets {@code container} up with {@code configuration}, deploys {@code war} and returns its index page. */
    private static String serve(PagesContainer container, PagesConfiguration configuration, WebArchive war) throws Exception {
        container.setup(configuration);
        try {
            String body = get(container, war);
            container.undeploy(war);
            return body;
        } finally {
            container.stop();
        }
    }

    private static String get(PagesContainer container, WebArchive war) throws Exception {
        var http = container.deploy(war).getContexts(HTTPContext.class).iterator().next();
        String context = war.getName().replaceFirst("\\.war$", "");
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + http.getPort() + "/" + context + "/index.jsp")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            return response.body();
        }
    }
}
