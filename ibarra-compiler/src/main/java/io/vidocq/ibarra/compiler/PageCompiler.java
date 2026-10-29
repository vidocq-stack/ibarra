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
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import javax.tools.*;

/** Build-only compiler. It emits classes without loading or executing them. */
public final class PageCompiler {
    private static final String CLASS_SUFFIX = ".class";

    public void compile(List<GeneratedPage> sources, Path output, List<String> compilerOptions) throws IOException {
        Objects.requireNonNull(output);
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("Page compilation requires a full JDK");
        var auxiliary = auxiliaryClasses(sources, classNames(sources));
        Files.createDirectories(output);
        for (var generated : auxiliary.values()) {
            Path file = classFile(output, generated);
            Files.createDirectories(file.getParent());
            Files.write(file, generated.bytes());
        }
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8)) {
            manager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(output));
            var originals = new LinkedHashMap<URI, GeneratedPage>();
            var inputs = writeSources(sources, output, originals);
            var files = manager.getJavaFileObjectsFromPaths(inputs);
            var options = new ArrayList<>(List.of("--release", "25", "-proc:none", "-encoding", "UTF-8"));
            options.addAll(compilerOptions);
            if (!Boolean.TRUE.equals(compiler.getTask(null, manager, diagnostics, options, null, files).call()))
                throw new PageCompilationException(messages(diagnostics, originals, output));
            for (var generated : auxiliary.values()) {
                Path file = classFile(output, generated);
                if (!Files.isRegularFile(file) || !Arrays.equals(generated.bytes(), Files.readAllBytes(file)))
                    throw new IllegalArgumentException("Java compilation replaced an auxiliary class: " + generated.className());
            }
        }
    }

    private static Set<String> classNames(List<GeneratedPage> sources) {
        var names = new HashSet<String>();
        for (var source : sources) {
            if (!javax.lang.model.SourceVersion.isName(source.className()) || !names.add(source.className()))
                throw new IllegalArgumentException("Invalid or duplicate generated class name: " + source.className());
        }
        return names;
    }

    private static Map<String, GeneratedClass> auxiliaryClasses(List<GeneratedPage> sources, Set<String> names) {
        var auxiliary = new LinkedHashMap<String, GeneratedClass>();
        for (var source : sources) {
            for (var generated : source.auxiliaryClasses()) {
                var previous = auxiliary.putIfAbsent(generated.className(), generated);
                if (names.contains(generated.className()) || previous != null && !previous.equals(generated))
                    throw new IllegalArgumentException("Conflicting generated class: " + generated.className());
            }
        }
        return auxiliary;
    }

    private static Path classFile(Path output, GeneratedClass generated) {
        return output.resolve(generated.className().replace('.', '/') + CLASS_SUFFIX);
    }

    private static List<Path> writeSources(List<GeneratedPage> sources, Path output, Map<URI, GeneratedPage> originals)
            throws IOException {
        var inputs = new ArrayList<Path>();
        for (var source : sources) {
            var input = output.resolve(".ibarra-sources").resolve(source.className().replace('.', '/') + ".java");
            Files.createDirectories(input.getParent());
            Files.writeString(input, source.source(), java.nio.charset.StandardCharsets.UTF_8);
            inputs.add(input);
            originals.put(input.toAbsolutePath().toUri(), source);
        }
        return inputs;
    }

    private static List<String> messages(DiagnosticCollector<JavaFileObject> diagnostics, Map<URI, GeneratedPage> originals,
            Path output) {
        var messages = new ArrayList<String>();
        for (var diagnostic : diagnostics.getDiagnostics()) {
            var file = diagnostic.getSource();
            var source = file == null ? null : originals.get(file.toUri());
            var original = source == null ? null : source.location(diagnostic.getLineNumber());
            messages.add(where(original, file, output, diagnostic.getLineNumber()) + ": " + diagnostic.getMessage(Locale.ROOT));
        }
        return messages;
    }

    private static String where(SourceLocation original, JavaFileObject file, Path output, long line) {
        if (original != null) return original.toString();
        return (file == null ? output.toString() : file.getName()) + ":" + line;
    }
}

