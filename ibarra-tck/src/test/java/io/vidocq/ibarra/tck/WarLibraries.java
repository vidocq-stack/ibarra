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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.jar.JarFile;

/** Build-only adaptation of legacy WAR libraries into the private application module. */
final class WarLibraries {
    private WarLibraries() { }

    static void assemble(Path web, Path classes) throws IOException {
        Path libraries = web.resolve("WEB-INF/lib");
        if (!Files.isDirectory(libraries)) return;
        try (var paths = Files.list(libraries)) {
            int index = 0;
            for (Path path : paths.sorted().toList()) {
                if (!Files.isRegularFile(path) || !path.getFileName().toString().endsWith(".jar"))
                    throw new IOException("Unsupported WAR library entry: " + path.getFileName());
                try (var jar = new JarFile(path.toFile())) {
                    var entries = jar.stream().sorted(java.util.Comparator.comparing(java.util.jar.JarEntry::getName)).toList();
                    for (var entry : entries) {
                        String name = entry.getName();
                        Path original = classes.resolve(name).normalize();
                        if (!original.startsWith(classes) || name.startsWith("/") || name.indexOf('\\') >= 0
                                || Arrays.asList(name.split("/")).stream().anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals("..")))
                            throw new IOException("Unsafe WAR library path: " + name);
                        if (entry.isDirectory()) continue;
                        if (name.equals("module-info.class") || name.startsWith("META-INF/versions/")
                                || name.equals("META-INF/web-fragment.xml") || name.startsWith("META-INF/services/"))
                            throw new IOException("WAR library metadata needs explicit adaptation: " + path.getFileName() + "!/" + name);
                        // Retain each library's TLD identity without overwriting another library's META-INF/taglib.tld.
                        String targetName = name.startsWith("META-INF/") && name.endsWith(".tld")
                                ? "META-INF/ibarra-war-libraries/" + String.format(java.util.Locale.ROOT, "%04d", index) + "/" + name
                                : name;
                        Path target = classes.resolve(targetName);
                        byte[] bytes;
                        try (var input = jar.getInputStream(entry)) { bytes = input.readAllBytes(); }
                        if (Files.exists(target)) {
                            if (!Files.isRegularFile(target) || !Arrays.equals(bytes, Files.readAllBytes(target)))
                                throw new IOException("Conflicting WAR library output: " + path.getFileName() + "!/" + name);
                        } else {
                            Files.createDirectories(target.getParent()); Files.write(target, bytes);
                        }
                    }
                }
                index++;
            }
        }
    }
}
