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
    public void compile(List<GeneratedPage> sources, Path output, List<String> compilerOptions) throws IOException {
        Objects.requireNonNull(output);
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("Page compilation requires a full JDK");
        Files.createDirectories(output);
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8)) {
            manager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(output));
            var inputs = new ArrayList<Path>();
            var originals = new HashMap<URI, GeneratedPage>();
            for (var source : sources) {
                if (!javax.lang.model.SourceVersion.isName(source.className()))
                    throw new IllegalArgumentException("Invalid generated class name: " + source.className());
                var input = output.resolve(".ibarra-sources").resolve(source.className().replace('.', '/') + ".java");
                Files.createDirectories(input.getParent());
                Files.writeString(input, source.source(), java.nio.charset.StandardCharsets.UTF_8);
                inputs.add(input);
                originals.put(input.toAbsolutePath().toUri(), source);
            }
            var files = manager.getJavaFileObjectsFromPaths(inputs);
            var options = new ArrayList<>(List.of("--release", "25", "-proc:none", "-encoding", "UTF-8"));
            options.addAll(compilerOptions);
            if (!Boolean.TRUE.equals(compiler.getTask(null, manager, diagnostics, options, null, files).call())) {
                var messages = new ArrayList<String>();
                for (var diagnostic : diagnostics.getDiagnostics()) {
                    var file = diagnostic.getSource();
                    var source = file == null ? null : originals.get(file.toUri());
                    var original = source == null ? null : source.location(diagnostic.getLineNumber());
                    String where = original == null ? String.valueOf(file == null ? output : file.getName()) + ":" + diagnostic.getLineNumber()
                            : original.toString();
                    messages.add(where + ": " + diagnostic.getMessage(Locale.ROOT));
                }
                throw new PageCompilationException(messages);
            }
        }
    }
}

