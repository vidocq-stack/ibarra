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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Build-only adaptation of legacy WAR libraries into the private application module. */
final class WarLibraries {
    private static final String META_INF = "META-INF/";

    private WarLibraries() { }

    static void assemble(Path web, Path classes) throws IOException {
        Path libraries = web.resolve("WEB-INF/lib");
        if (!Files.isDirectory(libraries)) {
            return;
        }
        try (var paths = Files.list(libraries)) {
            int index = 0;
            for (Path path : paths.sorted().toList()) {
                if (!Files.isRegularFile(path) || !path.getFileName().toString().endsWith(".jar")) {
                    throw new IOException("Unsupported WAR library entry: " + path.getFileName());
                }
                expand(path, index, classes);
                index++;
            }
        }
    }

    /** Copies the entries of library {@code index} into the application classes, in name order. */
    private static void expand(Path library, int index, Path classes) throws IOException {
        try (var jar = new JarFile(library.toFile())) {
            var entries = jar.stream().sorted(Comparator.comparing(JarEntry::getName)).toList();
            for (var entry : entries) {
                String name = entry.getName();
                checkSafe(name, classes);
                if (!entry.isDirectory()) {
                    checkAdapted(library, name);
                    byte[] bytes;
                    try (var input = jar.getInputStream(entry)) {
                        bytes = input.readAllBytes();
                    }
                    writeOrCompare(classes.resolve(targetName(name, index)), bytes, library, name);
                }
            }
        }
    }

    /** Refuses an entry name that is absolute, uses a backslash, has an empty, {@code .} or {@code ..} segment, or leaves the classes. */
    private static void checkSafe(String name, Path classes) throws IOException {
        Path original = classes.resolve(name).normalize();
        if (!original.startsWith(classes) || name.startsWith("/") || name.indexOf('\\') >= 0
                || Arrays.stream(name.split("/")).anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."))) {
            throw new IOException("Unsafe WAR library path: " + name);
        }
    }

    private static void checkAdapted(Path library, String name) throws IOException {
        if (name.equals("module-info.class") || name.startsWith(META_INF + "versions/")
                || name.equals(META_INF + "web-fragment.xml") || name.startsWith(META_INF + "services/")) {
            throw new IOException("WAR library metadata needs explicit adaptation: " + library.getFileName() + "!/" + name);
        }
    }

    /** Retains each library's TLD identity without overwriting another library's {@code META-INF/taglib.tld}. */
    private static String targetName(String name, int index) {
        return name.startsWith(META_INF) && name.endsWith(".tld")
                ? META_INF + "ibarra-war-libraries/" + String.format(Locale.ROOT, "%04d", index) + "/" + name
                : name;
    }

    /** Writes a new output; an existing one must already hold the same bytes. */
    private static void writeOrCompare(Path target, byte[] bytes, Path library, String name) throws IOException {
        if (Files.exists(target)) {
            if (!Files.isRegularFile(target) || !Arrays.equals(bytes, Files.readAllBytes(target))) {
                throw new IOException("Conflicting WAR library output: " + library.getFileName() + "!/" + name);
            }
        } else {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        }
    }
}
