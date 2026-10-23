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

import io.vidocq.ibarra.arquillian.fixtures.LibraryFilter;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jboss.arquillian.container.impl.MapObject;
import org.jboss.arquillian.container.spi.ConfigurationException;
import org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code applicationLibraries} hook: library modules deployed in the application layer of every WAR. */
class ApplicationLibrariesTest {
    private static final String TEST_CLASS_PATH = System.getProperty("jdk.module.path", "") + File.pathSeparator
            + System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));

    @TempDir
    Path temp;

    @Test
    void explicitLibraryTagsAreDiscoveredOutsideTheWar() throws Exception {
        Path jar = FixtureModules.explicitModule(temp, "fixture.tags", "requires jakarta.servlet.jsp; exports fixture.tags;",
                Map.of("fixture/tags/ModuleTag.java", """
                        package fixture.tags;
                        public class ModuleTag extends jakarta.servlet.jsp.tagext.SimpleTagSupport {
                            @Override public void doTag() throws java.io.IOException {
                                getJspContext().getOut().write(getClass().getModule().getName());
                            }
                        }
                        """),
                Map.of("META-INF/fixture.tld", "<taglib xmlns='https://jakarta.ee/xml/ns/jakartaee' version='3.0'>"
                        + "<tlib-version>1.0</tlib-version><short-name>f</short-name><uri>urn:fixture</uri>"
                        + "<tag><name>module</name><tag-class>fixture.tags.ModuleTag</tag-class>"
                        + "<body-content>empty</body-content></tag></taglib>"));
        var war = ShrinkWrap.create(WebArchive.class, "explicit-library.war")
                .addAsWebResource(new StringAsset("<%@ taglib prefix=\"f\" uri=\"urn:fixture\" %><f:module/>"), "index.jsp");

        assertEquals("fixture.tags", serve(jar, "fixture.tags", war));
    }

    @Test
    void automaticLibraryIsRuntimeOnlyAndVisibleToApplicationCode() throws Exception {
        Path jar = FixtureModules.automaticModule(temp, "fixture-driver.jar",
                Map.of("fixture/driver/Probe.java", "package fixture.driver; public class Probe { }"), Map.of());
        var war = ShrinkWrap.create(WebArchive.class, "automatic-library.war")
                .addAsWebResource(new StringAsset(
                        "<%= Class.forName(\"fixture.driver.Probe\").getModule().getName() %>"), "index.jsp");

        assertEquals("fixture.driver", serve(jar, "fixture.driver", war));
    }

    @Test
    void webXmlFilterFromAWarLibraryGetsADirectFactory() throws Exception {
        var library = ShrinkWrap.create(JavaArchive.class, "lib.jar").addClass(LibraryFilter.class);
        var war = ShrinkWrap.create(WebArchive.class, "library-filter.war").addAsLibrary(library)
                .setWebXML(new StringAsset("<web-app xmlns='https://jakarta.ee/xml/ns/jakartaee' version='6.1'>"
                        + "<filter><filter-name>lib</filter-name><filter-class>" + LibraryFilter.class.getName()
                        + "</filter-class></filter>"
                        + "<filter-mapping><filter-name>lib</filter-name><url-pattern>/*</url-pattern></filter-mapping>"
                        + "</web-app>"))
                .addAsWebResource(new StringAsset("page"), "index.jsp");

        assertEquals("lib|page", serve(null, "", war));
    }

    @Test
    void unknownOrHostLibraryModulesFailAtSetup() {
        var container = new PagesContainer(TEST_CLASS_PATH);

        var unknown = configuration("missing.module");
        var missing = assertThrows(ConfigurationException.class, () -> container.setup(unknown));
        assertTrue(missing.getMessage().contains("missing.module"), missing.getMessage());
        assertTrue(missing.getMessage().contains("not found"), missing.getMessage());

        var hostModule = configuration("io.vidocq.foy.core");
        var host = assertThrows(ConfigurationException.class, () -> container.setup(hostModule));
        assertTrue(host.getMessage().contains("io.vidocq.foy.core"), host.getMessage());
        assertTrue(host.getMessage().contains("host module"), host.getMessage());

        for (String tooling : List.of("io.vidocq.peano.compiler", "io.vidocq.ibarra.compiler", "java.compiler")) {
            var buildTooling = configuration(tooling);
            var refused = assertThrows(ConfigurationException.class, () -> container.setup(buildTooling));
            assertTrue(refused.getMessage().contains(tooling), refused.getMessage());
            assertTrue(refused.getMessage().contains("build tooling"), refused.getMessage());
        }

        var invalid = configuration("bad name!");
        var failure = assertThrows(ConfigurationException.class, invalid::validate);
        assertTrue(failure.getMessage().contains("bad name!"), failure.getMessage());
        var duplicate = configuration("a.b, a.b");
        assertThrows(ConfigurationException.class, duplicate::validate);
    }

    @Test
    void resolutionSkipsUnreadableEntriesAndKeepsTheConfiguredOrder() throws Exception {
        Path unreadable = FixtureModules.automaticModule(temp, "1bad.jar", Map.of("bad/P.java", "package bad; public class P { }"), Map.of());
        Path driver = FixtureModules.automaticModule(temp, "fixture-driver.jar",
                Map.of("fixture/driver/Probe.java", "package fixture.driver; public class Probe { }"), Map.of());
        Path tags = FixtureModules.explicitModule(temp, "fixture.tags", "exports fixture.tags;",
                Map.of("fixture/tags/Marker.java", "package fixture.tags; public class Marker { }"), Map.of());
        var searchPath = PagesContainer.searchPath(String.join(File.pathSeparator, "", unreadable.toString(),
                temp.resolve("missing.jar").toString(), tags.toString(), "", driver.toString()));

        assertEquals(List.of(unreadable, temp.resolve("missing.jar"), tags, driver), searchPath);
        assertEquals(List.of(new ApplicationLibraries.Library("fixture.driver", driver, true),
                        new ApplicationLibraries.Library("fixture.tags", tags, false)),
                ApplicationLibraries.resolve(List.of("fixture.driver", "fixture.tags"), searchPath));
    }

    @Test
    void arquillianPropertiesPopulateTheConfiguration() throws Exception {
        var configuration = new PagesConfiguration();
        MapObject.populate(configuration, Map.of("applicationLibraries", "a.b, c.d",
                "jdkModules", "java.sql, java.naming", "applicationOpens", "p.q"));
        assertEquals(List.of("a.b", "c.d"), configuration.libraryNames());
        assertEquals("a.b, c.d", configuration.getApplicationLibraries());
        assertEquals(List.of("java.sql", "java.naming"), configuration.jdkModuleNames());
        assertEquals("java.sql, java.naming", configuration.getJdkModules());
        assertEquals(List.of("p.q"), configuration.openedPackageNames());
        assertEquals("p.q", configuration.getApplicationOpens());
        var defaults = new PagesConfiguration();
        assertEquals(List.of(), defaults.libraryNames());
        assertEquals("", defaults.getApplicationLibraries());
        assertEquals(List.of(), defaults.jdkModuleNames());
        assertEquals("", defaults.getJdkModules());
        assertEquals(List.of(), defaults.openedPackageNames());
        assertEquals("", defaults.getApplicationOpens());
    }

    private static PagesConfiguration configuration(String applicationLibraries) {
        var configuration = new PagesConfiguration();
        configuration.setApplicationLibraries(applicationLibraries);
        return configuration;
    }

    /** Deploys {@code war} with {@code libraries} configured, {@code jar} added to the search path, and returns its index page. */
    private static String serve(Path jar, String libraries, WebArchive war) throws Exception {
        var container = new PagesContainer(jar == null ? TEST_CLASS_PATH : TEST_CLASS_PATH + File.pathSeparator + jar);
        container.setup(configuration(libraries));
        try {
            var http = container.deploy(war).getContext(HTTPContext.class);
            String context = war.getName().replaceFirst("\\.war$", "");
            try (var client = HttpClient.newHttpClient()) {
                var response = client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + http.getPort() + "/" + context + "/index.jsp")).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), response.body());
                container.undeploy(war);
                return response.body();
            }
        } finally {
            container.stop();
        }
    }
}
