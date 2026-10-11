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

import io.vidocq.ibarra.compiler.internal.JavaSource;
import io.vidocq.ibarra.compiler.internal.TldListeners;
import io.vidocq.ibarra.spi.PageDescriptor;
import java.io.*;
import java.lang.classfile.*;
import java.lang.classfile.attribute.*;
import java.lang.constant.*;
import java.lang.module.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.lang.model.SourceVersion;

/** Build-only modular packaging. Generated servlet constructors are never executed here. */
public final class PagePrecompiler {
    private static final String SERVICE = "io.vidocq.ibarra.spi.PageIndex";
    public record Result(String moduleName, String indexClass, Set<String> pagePaths) {
        public Result { pagePaths = Set.copyOf(pagePaths); }
    }
    private record Owned(String provider, Map<String, String> files) { }

    public Result precompile(Path webRoot, Path output, List<Path> modulePath, String packageName,
            GenerationOptions options) throws IOException {
        if (options.allowStartupEl()) throw new IllegalArgumentException("Packaging cannot enable startup EL interpretation");
        output = output.toAbsolutePath().normalize();
        Path moduleFile = output.resolve("module-info.class");
        if (!Files.isRegularFile(moduleFile)) throw new IllegalArgumentException("Precompilation requires compiled module-info.class");
        byte[] originalModule = Files.readAllBytes(moduleFile);
        ModuleDescriptor descriptor = ModuleDescriptor.read(java.nio.ByteBuffer.wrap(originalModule));
        if (packageName == null) packageName = descriptor.name() + ".ibarra_generated";
        if (!SourceVersion.isName(packageName)) throw new IllegalArgumentException("Invalid generated package: " + packageName);
        if (descriptor.requires().stream().noneMatch(value -> value.name().equals("io.vidocq.ibarra.api")
                && !value.modifiers().contains(ModuleDescriptor.Requires.Modifier.STATIC)))
            throw new IllegalArgumentException("Application module must require io.vidocq.ibarra.api");
        var paths = new ArrayList<>(modulePath); paths.add(output);
        for (var reference : ModuleFinder.of(modulePath.toArray(Path[]::new)).findAll())
            if (reference.descriptor().isAutomatic()) throw new IllegalArgumentException("Automatic dependency modules are unsupported: " + reference.descriptor().name());
        Path manifest = output.resolveSibling(output.getFileName() + ".ibarra-manifest");
        Owned previous = owned(manifest, output);
        var resources = resources(webRoot);
        var bytes = new LinkedHashMap<>(options.byteSources());
        for (var entry : resources.entrySet())
            if (options.sources().containsKey(entry.getKey()) || bytes.putIfAbsent(entry.getKey(), entry.getValue()) != null)
                throw new IllegalArgumentException("Resource supplied more than once: " + entry.getKey());
        var catalog = TldListeners.catalog(resources, modulePath, output, descriptor);
        var libraries = new LinkedHashMap<>(catalog.libraries());
        for (var entry : options.tagLibraries().entrySet()) {
            var previousLibrary = libraries.putIfAbsent(entry.getKey(), entry.getValue());
            if (previousLibrary != null && !previousLibrary.equals(entry.getValue()))
                throw new IllegalArgumentException("Conflicting supplied tag library: " + entry.getKey());
        }
        var configured = new GenerationOptions(options.propertyGroups(), options.sources(), false, bytes, libraries);
        var generator = new PageGenerator(paths, descriptor);
        var sources = new ArrayList<GeneratedPage>();
        var pages = new TreeMap<String, String>();
        for (var entry : resources.entrySet()) if (entry.getKey().endsWith(".jsp") || entry.getKey().endsWith(".jspx")) {
            PageDescriptor.validatePath(entry.getKey());
            String name = packageName + ".Page_" + hash(entry.getKey());
            pages.put(entry.getKey(), name);
            sources.add(generator.generate(entry.getKey(), entry.getValue(), name, configured));
        }
        var listeners = catalog.listeners();
        String provider = pages.isEmpty() && listeners.isEmpty() ? null : packageName + ".GeneratedPageIndex";
        if (sources.stream().anyMatch(source -> !source.auxiliaryClasses().isEmpty())
                && descriptor.requires().stream().noneMatch(value -> value.name().equals("io.vidocq.peano.api")
                && !value.modifiers().contains(ModuleDescriptor.Requires.Modifier.STATIC)))
            throw new IllegalArgumentException("Compiled EL pages require io.vidocq.peano.api in the application module");
        if (provider != null) sources.add(index(provider, pages, listeners));
        var originalPackages = new TreeSet<String>();
        try (var files = Files.walk(output)) {
            for (Path file : files.filter(Files::isRegularFile).filter(file -> file.toString().endsWith(".class")).toList()) {
                String relative = output.relativize(file).toString().replace('\\', '/');
                if (!previous.files().containsKey(relative)) addPackage(originalPackages, relative);
            }
        }
        Path stage = Files.createTempDirectory(output.getParent(), "ibarra-precompile-");
        try {
            Path compiledOutput = stage.resolve("compiled"); Files.createDirectories(compiledOutput);
            if (!sources.isEmpty()) {
                Path application = stage.resolve("application"); Files.createDirectories(application);
                byte[] bootstrap = module(originalModule, previous.provider(), null, originalPackages);
                var cf = ClassFile.of();
                bootstrap = cf.transformClass(cf.parse(bootstrap), ClassTransform.dropping(element -> element instanceof ModulePackagesAttribute)
                        .andThen(ClassTransform.endHandler(builder -> builder.with(ModulePackagesAttribute.ofNames(
                                originalPackages.stream().map(PackageDesc::of).toList())))));
                Files.write(application.resolve("module-info.class"), bootstrap);
                var compilePath = new ArrayList<>(modulePath); compilePath.add(application);
                String path = compilePath.stream().map(Path::toString).collect(java.util.stream.Collectors.joining(File.pathSeparator));
                new PageCompiler().compile(sources, compiledOutput, List.of("--module-path", path, "--add-modules", descriptor.name(),
                        "--patch-module", descriptor.name() + "=" + output + File.pathSeparator + compiledOutput + File.pathSeparator + compiledOutput.resolve(".ibarra-sources")));
            }
            var compiled = new TreeSet<String>();
            try (var files = Files.walk(compiledOutput)) {
                for (Path file : files.filter(Files::isRegularFile).filter(file -> file.toString().endsWith(".class")).toList()) {
                    String relative = compiledOutput.relativize(file).toString().replace('\\', '/');
                    if (relative.equals("module-info.class")) throw new IllegalArgumentException("Unexpected generated module descriptor");
                    if (Files.exists(output.resolve(relative)) && !previous.files().containsKey(relative))
                        throw new IllegalArgumentException("Generated class would overwrite an application class: " + relative);
                    compiled.add(relative);
                }
            }
            var packages = new TreeSet<>(originalPackages);
            for (String relative : compiled) addPackage(packages, relative);
            byte[] patched = module(originalModule, previous.provider(), provider, packages);
            for (String relative : compiled) {
                Path target = output.resolve(relative); Files.createDirectories(target.getParent());
                Files.copy(compiledOutput.resolve(relative), target, StandardCopyOption.REPLACE_EXISTING);
            }
            for (String relative : previous.files().keySet()) if (!compiled.contains(relative)) {
                Path target = output.resolve(relative); Files.deleteIfExists(target); emptyParents(target.getParent(), output);
            }
            Files.write(moduleFile, patched);
            var lines = new ArrayList<String>(); lines.add(provider == null ? "" : provider);
            for (String relative : compiled) lines.add(relative + "\t" + digest(Files.readAllBytes(output.resolve(relative))));
            Files.write(manifest, lines, StandardCharsets.UTF_8);
            return new Result(descriptor.name(), provider, pages.keySet());
        } finally { remove(stage); }
    }

    private static Map<String, byte[]> resources(Path root) throws IOException {
        var result = new TreeMap<String, byte[]>();
        if (!Files.exists(root)) return result;
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("Web source root is not a directory: " + root);
        try (var files = Files.walk(root)) {
            for (Path file : files.sorted().toList()) {
                if (Files.isSymbolicLink(file)) throw new IllegalArgumentException("Symbolic web resources are unsupported: " + file);
                if (Files.isRegularFile(file)) result.put("/" + root.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
            }
        }
        return result;
    }

    private static String hash(String path) {
        return digest(path.getBytes(StandardCharsets.UTF_8)).substring(0, 24);
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    private static GeneratedPage index(String name, Map<String, String> pages, List<String> listeners) {
        int dot = name.lastIndexOf('.');
        var source = new StringBuilder("package ").append(name.substring(0, dot)).append(";\npublic final class ")
                .append(name.substring(dot + 1)).append(" implements ").append(SERVICE).append(" {\n")
                .append("private static final java.util.List<io.vidocq.ibarra.spi.PageDescriptor> PAGES = java.util.List.of(");
        boolean comma = false;
        for (var entry : pages.entrySet()) {
            if (comma) source.append(',');
            source.append("new io.vidocq.ibarra.spi.PageDescriptor(").append(JavaSource.quote(entry.getKey())).append(',')
                    .append(entry.getValue()).append("::new)");
            comma = true;
        }
        source.append(");\npublic java.util.List<io.vidocq.ibarra.spi.PageDescriptor> pages() { return PAGES; }\n")
                .append("private static final java.util.List<io.vidocq.ibarra.spi.ListenerDescriptor> LISTENERS = java.util.List.of(");
        comma = false;
        for (String listener : listeners) {
            if (comma) source.append(',');
            source.append("new io.vidocq.ibarra.spi.ListenerDescriptor(").append(listener).append(".class, () -> { try { return new ")
                    .append(listener).append("(); } catch (java.lang.RuntimeException | java.lang.Error failure) { throw failure; }")
                    .append(" catch (java.lang.Throwable failure) { throw new java.lang.IllegalStateException(")
                    .append(JavaSource.quote("Cannot construct TLD listener " + listener)).append(", failure); } })");
            comma = true;
        }
        source.append(");\npublic java.util.List<io.vidocq.ibarra.spi.ListenerDescriptor> listeners() { return LISTENERS; }\n}\n");
        return new GeneratedPage(name, source.toString(), Map.of());
    }

    private static void addPackage(Set<String> packages, String relative) {
        int slash = relative.lastIndexOf('/');
        if (slash > 0) packages.add(relative.substring(0, slash).replace('/', '.'));
    }
    private static byte[] module(byte[] original, String previous, String provider, Set<String> packages) {
        var classFile = ClassFile.of();
        return classFile.transformClass(classFile.parse(original), (builder, element) -> {
            if (element instanceof ModuleAttribute attribute) {
                var entries = new ArrayList<ModuleProvideInfo>();
                var implementations = new ArrayList<ClassDesc>();
                for (var entry : attribute.provides()) {
                    if (!entry.provides().asSymbol().equals(ClassDesc.of(SERVICE))) entries.add(entry);
                    else for (var implementation : entry.providesWith()) {
                        var symbol = implementation.asSymbol();
                        if (previous == null || !symbol.equals(ClassDesc.of(previous))) implementations.add(symbol);
                    }
                }
                if (provider != null && !implementations.contains(ClassDesc.of(provider))) implementations.add(ClassDesc.of(provider));
                if (!implementations.isEmpty()) entries.add(ModuleProvideInfo.of(ClassDesc.of(SERVICE), implementations));
                builder.with(ModuleAttribute.of(attribute.moduleName(), attribute.moduleFlagsMask(), attribute.moduleVersion().orElse(null),
                        attribute.requires(), attribute.exports(), attribute.opens(), attribute.uses(), entries));
            } else if (element instanceof ModulePackagesAttribute) {
                builder.with(ModulePackagesAttribute.ofNames(packages.stream().map(PackageDesc::of).toList()));
            } else builder.with(element);
        });
    }

    private static Owned owned(Path manifest, Path output) throws IOException {
        if (!Files.exists(manifest)) return new Owned(null, Map.of());
        var lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        if (lines.isEmpty()) throw new IllegalArgumentException("Invalid precompiler ownership manifest");
        String provider = lines.getFirst().isEmpty() ? null : lines.getFirst();
        if (provider != null && (!SourceVersion.isName(provider) || !provider.contains(".")))
            throw new IllegalArgumentException("Invalid manifest provider");
        var files = new HashMap<String, String>();
        String prefix = provider == null ? "" : provider.substring(0, provider.lastIndexOf('.')).replace('.', '/') + "/";
        for (String line : lines.subList(1, lines.size())) {
            var parts = line.split("\t", -1);
            if (parts.length != 2 || !parts[1].matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid manifest digest");
            String relative = parts[0];
            Path target = output.resolve(relative).normalize();
            if (provider == null || !relative.startsWith(prefix) || !relative.endsWith(".class")
                    || !target.startsWith(output) || Path.of(relative).isAbsolute() || relative.contains(".."))
                throw new IllegalArgumentException("Invalid manifest class: " + relative);
            if (Files.exists(target) && !digest(Files.readAllBytes(target)).equals(parts[1]))
                throw new IllegalArgumentException("Previously generated class was replaced: " + relative);
            files.put(relative, parts[1]);
        }
        return new Owned(provider, Map.copyOf(files));
    }

    private static void emptyParents(Path directory, Path output) throws IOException {
        while (directory != null && !directory.equals(output) && directory.startsWith(output)) {
            try { Files.deleteIfExists(directory); } catch (DirectoryNotEmptyException failure) { return; }
            directory = directory.getParent();
        }
    }
    private static void remove(Path directory) throws IOException {
        try (var files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }
}
