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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApplicationDescriptorTest {
    @Test
    void baseRequiresTheFiveRuntimeModules() {
        assertEquals("module tck.application { requires io.vidocq.foy.api; requires io.vidocq.ibarra.api; "
                + "requires io.vidocq.peano.api; requires jakarta.el; requires java.desktop; }",
                ApplicationDescriptor.base().source());
    }

    @Test
    void statementsMergeTargetsAndAppendProviders() {
        var descriptor = ApplicationDescriptor.base()
                .requires("jakarta.el")
                .exportsTo("app.beans", "z.reader")
                .exportsTo("app.beans", "jakarta.el")
                .opens("app.bundles")
                .provides("app.Service", List.of("app.B"))
                .provides("app.Service", List.of("app.A"))
                .provides("app.Empty", List.of());
        assertEquals("module tck.application { requires io.vidocq.foy.api; requires io.vidocq.ibarra.api; "
                + "requires io.vidocq.peano.api; requires jakarta.el; requires java.desktop; "
                + "exports app.beans to jakarta.el,z.reader; opens app.bundles; provides app.Service with app.B,app.A; }",
                descriptor.source());
    }

    @Test
    void readRoundTripsOpensQualifiedExportsAndProviderOrder(@TempDir Path work) throws Exception {
        Path sources = Files.createDirectories(work.resolve("sources"));
        Path classes = Files.createDirectories(work.resolve("classes"));
        var types = Map.of(
                "app/Service.java", "package app; public interface Service { }",
                "app/A.java", "package app; public final class A implements Service { }",
                "app/B.java", "package app; public final class B implements Service { }",
                "app/beans/Bean.java", "package app.beans; public final class Bean { }");
        var arguments = new ArrayList<>(List.of("-d", classes.toString()));
        for (var type : types.entrySet()) {
            Path source = sources.resolve(type.getKey());
            Files.createDirectories(source.getParent());
            Files.writeString(source, type.getValue());
            arguments.add(source.toString());
        }
        compile(arguments);
        Path bundle = classes.resolve("app/bundles/m.properties");
        Files.createDirectories(bundle.getParent());
        Files.writeString(bundle, "greeting=hello\n");
        Path descriptor = Files.createDirectories(work.resolve("descriptor")).resolve("module-info.java");
        Files.writeString(descriptor, "module tck.application { requires transitive jakarta.el; requires static java.desktop; "
                + "exports app.beans to jakarta.el; opens app.bundles; provides app.Service with app.B, app.A; }");
        String modulePath = String.join(File.pathSeparator, PagesContainer.modulePaths(true).stream().map(Path::toString).toList());
        compile(List.of("--module-path", modulePath, "--patch-module", "tck.application=" + classes,
                "-d", classes.toString(), descriptor.toString()));

        assertEquals("module tck.application { requires transitive jakarta.el; requires static java.desktop; "
                + "exports app.beans to jakarta.el; opens app.bundles; provides app.Service with app.B,app.A; }",
                ApplicationDescriptor.read(classes.resolve("module-info.class")).source());
    }

    private static void compile(List<String> arguments) {
        var diagnostics = new ByteArrayOutputStream();
        int status = ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics, arguments.toArray(String[]::new));
        assertEquals(0, status, diagnostics::toString);
    }
}
