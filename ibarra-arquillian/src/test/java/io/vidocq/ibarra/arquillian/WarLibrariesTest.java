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
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WarLibrariesTest {
    @TempDir Path work;

    @Test
    void distinctLibraryTldsRemainDiscoverableAndOriginalJarsRemainUnchanged() throws Exception {
        Path web = Files.createDirectories(work.resolve("web"));
        Path classes = Files.createDirectories(work.resolve("classes"));
        Path first = jar(web, "a.jar", Map.of("META-INF/taglib.tld", "first"));
        Path second = jar(web, "b.jar", Map.of("META-INF/taglib.tld", "second"));
        byte[] originalFirst = Files.readAllBytes(first), originalSecond = Files.readAllBytes(second);
        WarLibraries.assemble(web, classes);
        assertEquals("first", Files.readString(classes.resolve("META-INF/ibarra-war-libraries/0000/META-INF/taglib.tld")));
        assertEquals("second", Files.readString(classes.resolve("META-INF/ibarra-war-libraries/0001/META-INF/taglib.tld")));
        assertArrayEquals(originalFirst, Files.readAllBytes(first)); assertArrayEquals(originalSecond, Files.readAllBytes(second));
    }

    @Test
    void equalOutputsAreDeduplicatedAndConflictingApplicationOutputsArePreserved() throws Exception {
        Path web = Files.createDirectories(work.resolve("web"));
        Path classes = Files.createDirectories(work.resolve("classes"));
        jar(web, "a.jar", Map.of("app/Helper.class", "same"));
        Path output = classes.resolve("app/Helper.class");
        Files.createDirectories(output.getParent()); Files.writeString(output, "same");
        WarLibraries.assemble(web, classes); assertEquals("same", Files.readString(output));
        jar(web, "b.jar", Map.of("app/Helper.class", "different"));
        var failure = assertThrows(IOException.class, () -> WarLibraries.assemble(web, classes));
        assertTrue(failure.getMessage().contains("Conflicting")); assertEquals("same", Files.readString(output));
    }

    @Test
    void unsafeLibraryPathsCannotEscapeTheApplicationRoot() throws Exception {
        for (String name : java.util.List.of("../escape.txt", "/absolute.txt", "app/../../escape.txt", "app\\escape.txt",
                "./module-info.class", "META-INF/./web-fragment.xml", "META-INF//services/example.Service")) {
            Path web = Files.createTempDirectory(work, "web");
            Path classes = Files.createTempDirectory(work, "classes");
            jar(web, "unsafe.jar", Map.of(name, "unsafe"));
            var failure = assertThrows(IOException.class, () -> WarLibraries.assemble(web, classes));
            assertTrue(failure.getMessage().contains("Unsafe"), name);
        }
        assertFalse(Files.exists(work.resolve("escape.txt")));
    }

    @Test
    void unadaptedModuleFragmentVersionAndServiceMetadataFailExplicitly() throws Exception {
        for (String name : java.util.List.of("module-info.class", "META-INF/versions/25/app/Probe.class", "META-INF/web-fragment.xml", "META-INF/services/jakarta.servlet.ServletContainerInitializer")) {
            Path web = Files.createTempDirectory(work, "web");
            Path classes = Files.createTempDirectory(work, "classes");
            jar(web, "metadata.jar", Map.of(name, "unsupported"));
            var failure = assertThrows(IOException.class, () -> WarLibraries.assemble(web, classes));
            assertTrue(failure.getMessage().contains("explicit adaptation"), name);
        }
    }

    private Path jar(Path web, String name, Map<String, String> entries) throws IOException {
        Path path = Files.createDirectories(web.resolve("WEB-INF/lib")).resolve(name);
        try (var output = new JarOutputStream(Files.newOutputStream(path))) {
            for (var entry : new java.util.TreeMap<>(entries).entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8)); output.closeEntry();
            }
        }
        return path;
    }
}
