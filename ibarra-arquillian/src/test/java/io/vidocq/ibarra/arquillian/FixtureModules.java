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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;

/**
 * Builds library jars for the tests: an explicit Java module (with a {@code module-info}) or an
 * automatic module (a plain jar, named after its file). Both compile with the system compiler
 * against the modular entries of the test class path the adapter keeps.
 */
final class FixtureModules {
    private FixtureModules() { }

    /** A jar holding the named module {@code name}, whose descriptor body is {@code descriptorBody}. */
    static Path explicitModule(Path dir, String name, String descriptorBody, Map<String, String> sources,
            Map<String, String> resources) throws IOException {
        var allSources = new LinkedHashMap<>(sources);
        allSources.put("module-info.java", "module " + name + " { " + descriptorBody + " }");
        return build(dir.resolve(name), name + ".jar", "--module-path", allSources, resources);
    }

    /** A jar with no {@code module-info}: on a module path it is the automatic module named after {@code jarFileName}. */
    static Path automaticModule(Path dir, String jarFileName, Map<String, String> sources, Map<String, String> resources)
            throws IOException {
        return build(dir.resolve(jarFileName.replaceFirst("\\.jar$", "")), jarFileName, "--class-path", sources, resources);
    }

    private static Path build(Path work, String jarFileName, String pathOption, Map<String, String> sources,
            Map<String, String> resources) throws IOException {
        Path source = Files.createDirectories(work.resolve("src"));
        Path classes = Files.createDirectories(work.resolve("classes"));
        var arguments = new ArrayList<>(List.of(pathOption, modularEntries(), "-d", classes.toString()));
        for (var file : sources.entrySet()) {
            Path target = source.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue());
            arguments.add(target.toString());
        }
        var diagnostics = new ByteArrayOutputStream();
        if (ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics, arguments.toArray(String[]::new)) != 0) {
            throw new IOException("Fixture compilation failed: " + diagnostics);
        }
        for (var resource : resources.entrySet()) {
            Path target = classes.resolve(resource.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, resource.getValue());
        }
        Path jar = work.getParent().resolve(jarFileName);
        writeJar(classes, jar);
        return jar;
    }

    private static String modularEntries() throws IOException {
        return String.join(File.pathSeparator, PagesContainer.modulePaths(true).stream().map(Path::toString).toList());
    }

    private static void writeJar(Path classes, Path jar) throws IOException {
        try (var output = new JarOutputStream(Files.newOutputStream(jar)); var files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                output.putNextEntry(new JarEntry(classes.relativize(file).toString().replace(File.separatorChar, '/')));
                Files.copy(file, output);
                output.closeEntry();
            }
        }
    }
}
