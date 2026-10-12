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
import java.lang.classfile.ClassFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ComponentFactoriesTest {
    @TempDir Path classes;

    @Test
    void metadataDiscoveryDoesNotInitializeComponentsAndDeduplicatesTheirFactories() throws Exception {
        copy(MetadataOnlyServlet.class);
        var providers = ComponentFactories.generate(classes, List.of(MetadataOnlyServlet.class.getName(), MetadataOnlyServlet.class.getName()));
        assertEquals(1, providers.size());
        assertEquals(List.of(), ClassFile.of().verify(Files.readAllBytes(classes.resolve(providers.getFirst().replace('.', '/') + ".class"))));
    }

    @Test
    void factoryCollisionPreservesApplicationBytecode() throws Exception {
        copy(MetadataOnlyServlet.class);
        Path collision = classes.resolve("tck/generated/Component0.class");
        Files.createDirectories(collision.getParent());
        byte[] original = {1, 2, 3};
        Files.write(collision, original);
        var failure = assertThrows(IOException.class, () -> ComponentFactories.generate(classes, List.of(MetadataOnlyServlet.class.getName())));
        assertTrue(failure.getMessage().contains("collides"));
        assertArrayEquals(original, Files.readAllBytes(collision));
    }

    @Test
    void invalidBinaryNamesCannotEscapeTheClassRoot() {
        for (String name : List.of("../Escape", "app/Probe", "app..Probe", "Probe")) {
            assertThrows(IOException.class, () -> ComponentFactories.generate(classes, List.of(name)), name);
        }
    }

    @Test
    void checkedThrowableConstructorsAreWrappedWhileRuntimeFailuresKeepTheirType() throws Exception {
        for (Class<?> type : List.of(CheckedThrowableServlet.class, RuntimeFailureServlet.class, ErrorFailureServlet.class)) copy(type);
        var providers = ComponentFactories.generate(classes, List.of(CheckedThrowableServlet.class.getName(), RuntimeFailureServlet.class.getName(), ErrorFailureServlet.class.getName()));
        // This loader is a build-side test oracle; deployed applications use a named module layer.
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            for (String name : providers) {
                var factory = (io.vidocq.foy.spi.gen.WebComponent) loader.loadClass(name).getConstructor().newInstance();
                if (factory.type() == CheckedThrowableServlet.class) {
                    var failure = assertThrows(IllegalStateException.class, factory::newInstance);
                    assertEquals("checked", failure.getCause().getMessage());
                } else if (factory.type() == RuntimeFailureServlet.class) {
                    assertEquals("runtime", assertThrows(IllegalArgumentException.class, factory::newInstance).getMessage());
                } else {
                    assertEquals("error", assertThrows(AssertionError.class, factory::newInstance).getMessage());
                }
            }
        }
    }

    public static class CheckedThrowableServlet extends jakarta.servlet.http.HttpServlet {
        public CheckedThrowableServlet() throws Throwable { throw new Throwable("checked"); }
    }
    public static class RuntimeFailureServlet extends jakarta.servlet.http.HttpServlet {
        public RuntimeFailureServlet() { throw new IllegalArgumentException("runtime"); }
    }
    public static class ErrorFailureServlet extends jakarta.servlet.http.HttpServlet {
        public ErrorFailureServlet() { throw new AssertionError("error"); }
    }

    private void copy(Class<?> type) throws IOException {
        String name = type.getName().replace('.', '/') + ".class";
        Path target = classes.resolve(name);
        Files.createDirectories(target.getParent());
        try (var input = type.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(input); Files.copy(input, target);
        }
    }

    public static class MetadataOnlyServlet extends jakarta.servlet.http.HttpServlet {
        static {
            if (System.nanoTime() != 0) throw new IllegalStateException("Metadata discovery initialized a component");
        }
    }
}
