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
package io.vidocq.ibarra.maven;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import static org.junit.jupiter.api.Assertions.*;

class PrecompileMojoTest {
    @TempDir Path directory;

    @Test
    void webDescriptorGroupsPreservePatternsDefaultsAndIncludeOrder() throws Exception {
        Path descriptor = directory.resolve("web.xml");
        Files.writeString(descriptor, """
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.1">
                  <jsp-config><jsp-property-group>
                    <url-pattern>*.jsp</url-pattern><url-pattern>/private/*</url-pattern>
                    <page-encoding>UTF-8</page-encoding><el-ignored>true</el-ignored>
                    <include-prelude>/first.jspf</include-prelude><include-prelude>/second.jspf</include-prelude>
                    <include-coda>/last.jspf</include-coda>
                  </jsp-property-group></jsp-config>
                </web-app>
                """);
        var groups = WebXmlProperties.read(descriptor);
        assertEquals(java.util.List.of("*.jsp", "/private/*"), groups.getFirst().patterns());
        assertEquals("UTF-8", groups.getFirst().properties().get("page-encoding"));
        assertEquals(java.util.List.of("/first.jspf", "/second.jspf"), groups.getFirst().preludes());
        assertEquals(java.util.List.of("/last.jspf"), groups.getFirst().codas());
    }

    @Test
    void descriptorRejectsExternalEntitiesAndUnknownJspProperties() throws Exception {
        Path descriptor = directory.resolve("web.xml");
        Files.writeString(descriptor, "<!DOCTYPE web-app [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><web-app>&x;</web-app>");
        assertThrows(Exception.class, () -> WebXmlProperties.read(descriptor));
        Files.writeString(descriptor, "<web-app><jsp-config><jsp-property-group><url-pattern>*.jsp</url-pattern><el-ignord>true</el-ignord></jsp-property-group></jsp-config></web-app>");
        assertThrows(IllegalArgumentException.class, () -> WebXmlProperties.read(descriptor));
    }

    @Test
    void skipDoesNotTouchTheApplicationAndMissingModuleHasActionableFailure() throws Exception {
        var mojo = new PrecompileMojo();
        mojo.skip = true; mojo.execute();
        mojo.skip = false; mojo.project = new MavenProject();
        mojo.outputDirectory = directory.resolve("classes").toFile();
        mojo.webSourceDirectory = directory.resolve("web").toFile();
        mojo.generatedPackage = "demo.generated";
        var error = assertThrows(MojoFailureException.class, mojo::execute);
        assertTrue(error.getMessage().contains("module-info.class"));
    }

    @Test
    void foreignNamespacesCannotPretendToBeJspConfiguration() throws Exception {
        Path descriptor = directory.resolve("web.xml");
        Files.writeString(descriptor, "<web-app xmlns='https://jakarta.ee/xml/ns/jakartaee' xmlns:f='urn:foreign'>"
                + "<f:jsp-config><f:jsp-property-group><f:url-pattern>*.jsp</f:url-pattern></f:jsp-property-group></f:jsp-config></web-app>");
        assertThrows(IllegalArgumentException.class, () -> WebXmlProperties.read(descriptor));
    }
}

