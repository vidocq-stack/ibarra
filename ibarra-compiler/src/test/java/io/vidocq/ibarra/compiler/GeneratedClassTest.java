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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GeneratedClassTest {
    @TempDir Path directory;
    @Test void bytecodeCopiesProtectPublishedMetadataAndEqualityUsesContent() {
        byte[] bytes = io.vidocq.peano.compiler.ExpressionCompiler.compile("example.Expression", "${1+2}");
        byte[] original = bytes.clone();
        var generated = new GeneratedClass("example.Expression", bytes);
        bytes[bytes.length - 1] ^= 1;
        byte[] returned = generated.bytes(); returned[returned.length - 1] ^= 1;
        assertArrayEquals(original, generated.bytes());
        var equivalent = new GeneratedClass("example.Expression", original);
        assertEquals(equivalent, generated); assertEquals(equivalent.hashCode(), generated.hashCode());
    }
    @Test void textualFormDescribesTheBytecodeContentRatherThanTheArrayIdentity() {
        byte[] bytes = io.vidocq.peano.compiler.ExpressionCompiler.compile("example.Expression", "${1+2}");
        var generated = new GeneratedClass("example.Expression", bytes);
        var equivalent = new GeneratedClass("example.Expression", bytes.clone());
        assertEquals(equivalent.toString(), generated.toString());
        assertTrue(generated.toString().contains("example.Expression"));
        assertTrue(generated.toString().contains(bytes.length + " bytes"));
    }
    @Test void mismatchedClassNamesAreRejected() {
        byte[] bytes = io.vidocq.peano.compiler.ExpressionCompiler.compile("example.Expression", "${1+2}");
        assertThrows(IllegalArgumentException.class, () -> new GeneratedClass("example.Other", bytes));
    }
    @Test void sourceAndAuxiliaryCollisionsFailBeforeWritingOutput() {
        byte[] bytes = io.vidocq.peano.compiler.ExpressionCompiler.compile("example.Expression", "${1+2}");
        var source = new GeneratedPage("example.Expression", "package example; public class Expression {}",
                Map.of(), List.of(new GeneratedClass("example.Expression", bytes)));
        Path output = directory.resolve("output");
        var compiler = new PageCompiler();
        var sources = List.of(source);
        List<String> compilerOptions = List.of();
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(sources, output, compilerOptions));
        assertFalse(Files.exists(output));
    }
    @Test void javacNestedClassesCannotReplaceAuxiliaryBytecode() {
        byte[] bytes = io.vidocq.peano.compiler.ExpressionCompiler.compile("example.Page$Expression0", "${1+2}");
        var source = new GeneratedPage("example.Page", "package example; public class Page { public static class Expression0 {} }",
                Map.of(), List.of(new GeneratedClass("example.Page$Expression0", bytes)));
        var compiler = new PageCompiler();
        var sources = List.of(source);
        List<String> compilerOptions = List.of();
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(sources, directory, compilerOptions));
    }
}
