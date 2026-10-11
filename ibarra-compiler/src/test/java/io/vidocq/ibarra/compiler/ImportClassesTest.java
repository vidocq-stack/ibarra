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

import io.vidocq.ibarra.compiler.internal.ImportClasses;
import java.lang.module.ModuleDescriptor;
import java.nio.file.*;
import java.util.List;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ImportClassesTest {
    @TempDir Path directory;
    @Test void wildcardCandidatesRespectReadsAndQualifiedExports() throws Exception {
        Path source = directory.resolve("source"), output = directory.resolve("classes");
        Files.createDirectories(source.resolve("visible")); Files.createDirectories(source.resolve("hidden"));
        Files.writeString(source.resolve("module-info.java"), "module library { exports visible to imports.app; }");
        Files.writeString(source.resolve("visible/Open.java"), "package visible; public class Open {}");
        Files.writeString(source.resolve("hidden/Hidden.java"), "package hidden; public class Hidden {}");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "25", "-d", output.toString(),
                source.resolve("module-info.java").toString(), source.resolve("visible/Open.java").toString(), source.resolve("hidden/Hidden.java").toString()));
        var target = ModuleDescriptor.newModule("imports.app").requires("java.base").requires("library").build();
        var imports = new ImportClasses(List.of(output), target).bindings(List.of("visible.*", "hidden.*"), new SourceLocation("/test.jsp", 1, 1));
        assertTrue(imports.containsKey("Open")); assertFalse(imports.containsKey("Hidden"));
        var other = ModuleDescriptor.newModule("other.app").requires("java.base").build();
        assertFalse(new ImportClasses(List.of(output), other).bindings(List.of("visible.*"), new SourceLocation("/test.jsp", 1, 1)).containsKey("Open"));
    }
}
