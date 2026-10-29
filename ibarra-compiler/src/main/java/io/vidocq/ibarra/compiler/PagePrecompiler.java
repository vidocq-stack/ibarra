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
import io.vidocq.ibarra.compiler.internal.TldReferences;
import io.vidocq.ibarra.compiler.internal.TagFileCompiler;
import io.vidocq.ibarra.compiler.internal.TranslationUnit;
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
    private static final String MODULE_INFO = "module-info.class";
    private static final String CLASS_SUFFIX = ".class";
    private static final String SOURCES = ".ibarra-sources";
    private static final String SUPPLIED_TWICE = "Resource supplied more than once: ";
    public record Result(String moduleName, String indexClass, Set<String> pagePaths) {
        public Result { pagePaths = Set.copyOf(pagePaths); }
    }
    /** Explicit deployment report; failed page paths stay routable as translation errors. */
    public record DeploymentResult(Result pages, Map<String, String> failures) {
        public DeploymentResult { Objects.requireNonNull(pages); failures = Map.copyOf(failures); }
    }
    private record Owned(String provider, Map<String, String> files) { }

    public Result precompile(Path webRoot, Path output, List<Path> modulePath, String packageName,
            GenerationOptions options) throws IOException {
        return precompile(webRoot, output, modulePath, packageName, options, false).pages();
    }

    /**
     * Compiles valid pages and emits request-time error servlets for individual translation
     * failures. Global deployment metadata, module and infrastructure failures still abort.
     * Production packaging should use the fail-fast {@link #precompile} entry point.
     */
    public DeploymentResult precompileDeployment(Path webRoot, Path output, List<Path> modulePath, String packageName,
            GenerationOptions options) throws IOException {
        return precompile(webRoot, output, modulePath, packageName, options, true);
    }

    private DeploymentResult precompile(Path webRoot, Path output, List<Path> modulePath, String packageName,
            GenerationOptions options, boolean retainFailures) throws IOException {
        if (options.allowStartupEl()) throw new IllegalArgumentException("Packaging cannot enable startup EL interpretation");
        TranslationUnit.validateConfiguration(options, new SourceLocation("/WEB-INF/web.xml", 1, 1));
        return new Packaging(output, modulePath, packageName, options, retainFailures).run(webRoot);
    }

    /** One packaging run: the validated application module and the per-page failures retained so far. */
    private static final class Packaging {
        private static boolean requiresNonStatic(ModuleDescriptor descriptor, String module) {
            return descriptor.requires().stream().anyMatch(value -> value.name().equals(module)
                    && !value.modifiers().contains(ModuleDescriptor.Requires.Modifier.STATIC));
        }

        private static List<Path> classFiles(java.util.stream.Stream<Path> files) {
            return files.filter(Files::isRegularFile).filter(file -> file.toString().endsWith(CLASS_SUFFIX)).toList();
        }

        private final Path output;
        private final List<Path> modulePath;
        private final List<Path> paths;
        private final GenerationOptions options;
        private final boolean retainFailures;
        private final Path moduleFile;
        private final byte[] originalModule;
        private final ModuleDescriptor descriptor;
        private final String packageName;
        private final Path manifest;
        private final Owned previous;
        private final Map<String, String> failures = new TreeMap<>();
        private final Set<String> pagePaths = new TreeSet<>();
        private final Map<String, TranslationUnit.Result> units = new TreeMap<>();

        Packaging(Path output, List<Path> modulePath, String packageName, GenerationOptions options, boolean retainFailures)
                throws IOException {
            this.output = output.toAbsolutePath().normalize();
            this.modulePath = modulePath;
            this.options = options;
            this.retainFailures = retainFailures;
            moduleFile = this.output.resolve(MODULE_INFO);
            if (!Files.isRegularFile(moduleFile)) throw new IllegalArgumentException("Precompilation requires compiled module-info.class");
            originalModule = Files.readAllBytes(moduleFile);
            descriptor = ModuleDescriptor.read(java.nio.ByteBuffer.wrap(originalModule));
            this.packageName = packageName == null ? descriptor.name() + ".ibarra_generated" : packageName;
            if (!SourceVersion.isName(this.packageName)) throw new IllegalArgumentException("Invalid generated package: " + this.packageName);
            if (!requiresNonStatic(descriptor, "io.vidocq.ibarra.api"))
                throw new IllegalArgumentException("Application module must require io.vidocq.ibarra.api");
            var allPaths = new ArrayList<>(modulePath);
            allPaths.add(this.output);
            paths = allPaths;
            for (var reference : ModuleFinder.of(modulePath.toArray(Path[]::new)).findAll()) {
                if (reference.descriptor().isAutomatic())
                    throw new IllegalArgumentException("Automatic dependency modules are unsupported: " + reference.descriptor().name());
            }
            manifest = this.output.resolveSibling(this.output.getFileName() + ".ibarra-manifest");
            previous = owned(manifest, this.output);
        }

        DeploymentResult run(Path webRoot) throws IOException {
            var resources = resources(webRoot);
            var bytes = sourceBytes(resources);
            var catalog = TldListeners.catalog(resources, modulePath, output, descriptor);
            var libraries = suppliedLibraries(catalog.libraries());
            var initial = new GenerationOptions(options.propertyGroups(), options.sources(), false, bytes, libraries);
            translate(resources, initial);
            var tagFiles = compileTagFiles(initial);
            mergeTagFileLibraries(libraries, tagFiles.libraries());
            var configured = new GenerationOptions(options.propertyGroups(), options.sources(), false, bytes, libraries);
            var resolved = resolve(configured);
            var referencedLibraries = new ArrayList<>(configured.tagLibraries().values());
            referencedLibraries.addAll(resolved.resources().values());
            var listeners = TldListeners.referenced(catalog, referencedLibraries, paths);
            var pages = new TreeMap<String, String>();
            var sources = generate(new PageGenerator(paths, descriptor), configured, resolved, pages);
            String provider = pages.isEmpty() && listeners.isEmpty() ? null : packageName + ".GeneratedPageIndex";
            if (sources.stream().anyMatch(source -> !source.auxiliaryClasses().isEmpty())
                    && !requiresNonStatic(descriptor, "io.vidocq.peano.api"))
                throw new IllegalArgumentException("Compiled EL pages require io.vidocq.peano.api in the application module");
            if (provider != null) sources.add(index(provider, pages, listeners));
            var originalPackages = originalPackages();
            Path stage = Files.createTempDirectory(output.getParent(), "ibarra-precompile-");
            try {
                Path compiledOutput = stage.resolve("compiled");
                Files.createDirectories(compiledOutput);
                if (!sources.isEmpty() || !tagFiles.sources().isEmpty())
                    compile(stage, compiledOutput, sources, tagFiles.sources(), pages, originalPackages);
                var compiled = compiledClasses(compiledOutput);
                install(compiledOutput, compiled, provider, originalPackages);
                return new DeploymentResult(new Result(descriptor.name(), provider, pages.keySet()), failures);
            } finally {
                remove(stage);
            }
        }

        /** Supplied sources, web resources and the tag files already present in the output, each path once. */
        private Map<String, byte[]> sourceBytes(Map<String, byte[]> resources) throws IOException {
            var bytes = new LinkedHashMap<>(options.byteSources());
            for (var entry : resources.entrySet()) {
                if (options.sources().containsKey(entry.getKey()) || bytes.putIfAbsent(entry.getKey(), entry.getValue()) != null)
                    throw new IllegalArgumentException(SUPPLIED_TWICE + entry.getKey());
            }
            try (var files = Files.walk(output)) {
                for (Path file : files.filter(Files::isRegularFile)
                        .filter(file -> file.toString().endsWith(".tag") || file.toString().endsWith(".tagx")).sorted().toList()) {
                    String path = '/' + relative(output, file);
                    if (bytes.putIfAbsent(path, Files.readAllBytes(file)) != null)
                        throw new IllegalArgumentException(SUPPLIED_TWICE + path);
                }
            }
            return bytes;
        }

        private Map<String, TagLibraryDescriptor> suppliedLibraries(Map<String, TagLibraryDescriptor> discovered) {
            var libraries = new LinkedHashMap<>(discovered);
            for (var entry : options.tagLibraries().entrySet()) {
                var previousLibrary = libraries.putIfAbsent(entry.getKey(), entry.getValue());
                if (previousLibrary != null && !previousLibrary.equals(entry.getValue()))
                    throw new IllegalArgumentException("Conflicting supplied tag library: " + entry.getKey());
            }
            return libraries;
        }

        private boolean isPage(String path) {
            return path.endsWith(".jsp") || path.endsWith(".jspx")
                    || options.propertyGroups().stream().anyMatch(group -> TranslationUnit.matches(group, path));
        }

        private void translate(Map<String, byte[]> resources, GenerationOptions initial) {
            for (var entry : resources.entrySet()) {
                if (!isPage(entry.getKey())) continue;
                PageDescriptor.validatePath(entry.getKey());
                pagePaths.add(entry.getKey());
                try {
                    units.put(entry.getKey(), new TranslationUnit(initial).read(entry.getKey(), entry.getValue()));
                } catch (PageParseException failure) {
                    retain(entry.getKey(), failure);
                }
            }
        }

        /** Records a page failure when failures are retained; otherwise rethrows it. */
        private void retain(String pagePath, PageParseException failure) {
            if (!retainFailures) throw failure;
            failures.put(pagePath, failure.getMessage());
        }

        /** Compiles the tag files, dropping the pages that depend on a failing tag file when failures are retained. */
        private TagFileCompiler.Result compileTagFiles(GenerationOptions initial) {
            while (true) {
                try {
                    return TagFileCompiler.compile(units.values(), initial, packageName, paths);
                } catch (PageParseException failure) {
                    if (!retainFailures) throw failure;
                    var affected = affectedPages(failure);
                    if (affected.isEmpty()) throw failure;
                    for (String page : affected) {
                        failures.put(page, failure.getMessage());
                        units.remove(page);
                    }
                }
            }
        }

        private List<String> affectedPages(PageParseException failure) {
            String source = failure.location().source();
            int slash = source.lastIndexOf('/');
            String failedDirectory = slash <= 0 ? null : source.substring(0, slash);
            return units.entrySet().stream().filter(entry -> entry.getKey().equals(source)
                    || failedDirectory != null && TagFileCompiler.referencesDirectory(entry.getValue(), failedDirectory))
                    .map(Map.Entry::getKey).toList();
        }

        private static void mergeTagFileLibraries(Map<String, TagLibraryDescriptor> libraries,
                Map<String, TagLibraryDescriptor> tagFileLibraries) {
            for (var entry : tagFileLibraries.entrySet()) {
                var previousLibrary = libraries.get(entry.getKey());
                if (previousLibrary == null || compiledTldTagFiles(previousLibrary, entry.getValue()))
                    libraries.put(entry.getKey(), entry.getValue());
                else if (!previousLibrary.equals(entry.getValue()))
                    throw new IllegalArgumentException("Conflicting tagdir library: " + entry.getKey());
            }
        }

        /** Whether {@code library} is {@code previous} with its TLD tag files now compiled. */
        private static boolean compiledTldTagFiles(TagLibraryDescriptor previous, TagLibraryDescriptor library) {
            return !previous.equals(library) && previous.resourcePath().equals(library.resourcePath())
                    && library.tags().values().stream().anyMatch(tag -> tag.tagFilePath() != null
                            && tag.handlerClass() != null && !tag.unsupportedMetadata());
        }

        private TldReferences.Resolved resolve(GenerationOptions configured) {
            if (!retainFailures) return TldReferences.discoverStrict(units.values(), configured, options.tagLibraries());
            var resolvedResources = new LinkedHashMap<String, TagLibraryDescriptor>();
            var iterator = units.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                try {
                    resolvedResources.putAll(TldReferences.discoverStrict(List.of(entry.getValue()), configured,
                            options.tagLibraries()).resources());
                } catch (PageParseException failure) {
                    failures.put(entry.getKey(), failure.getMessage());
                    iterator.remove();
                }
            }
            return new TldReferences.Resolved(configured.tagLibraries(), resolvedResources);
        }

        /** One generated servlet per page path, a translation-error servlet for each failed page. */
        private List<GeneratedPage> generate(PageGenerator generator, GenerationOptions configured, TldReferences.Resolved resolved,
                Map<String, String> pages) {
            var sources = new ArrayList<GeneratedPage>();
            for (String pagePath : pagePaths) {
                String name = packageName + ".Page_" + hash(pagePath);
                pages.put(pagePath, name);
                var source = failures.containsKey(pagePath) ? null : generatePage(generator, pagePath, name, configured, resolved);
                sources.add(source != null ? source : translationError(name, failures.get(pagePath)));
            }
            return sources;
        }

        private GeneratedPage generatePage(PageGenerator generator, String pagePath, String name, GenerationOptions configured,
                TldReferences.Resolved resolved) {
            try {
                return generator.generate(units.get(pagePath), name, configured, resolved);
            } catch (PageParseException failure) {
                retain(pagePath, failure);
                return null;
            }
        }

        /** Packages of the application's own classes, excluding classes owned by a previous run. */
        private Set<String> originalPackages() throws IOException {
            var originalPackages = new TreeSet<String>();
            try (var files = Files.walk(output)) {
                for (Path file : classFiles(files)) {
                    String relative = relative(output, file);
                    if (!previous.files().containsKey(relative)) addPackage(originalPackages, relative);
                }
            }
            return originalPackages;
        }

        private void compile(Path stage, Path compiledOutput, List<GeneratedPage> sources, List<GeneratedPage> tagSources,
                Map<String, String> pages, Set<String> originalPackages) throws IOException {
            Path application = stage.resolve("application");
            Files.createDirectories(application);
            writeBootstrap(application, originalPackages);
            if (retainFailures) copyApplicationClasses(application);
            var compilePath = new ArrayList<>(modulePath);
            compilePath.add(application);
            String path = compilePath.stream().map(Path::toString).collect(java.util.stream.Collectors.joining(File.pathSeparator));
            if (!retainFailures) {
                var allSources = new ArrayList<>(tagSources);
                allSources.addAll(sources);
                new PageCompiler().compile(allSources, compiledOutput,
                        compilerArguments(path, output, compiledOutput, compiledOutput.resolve(SOURCES)));
                return;
            }
            if (!tagSources.isEmpty()) compileTagFiles(tagSources, application, compiledOutput, path);
            var sourcePaths = new HashMap<String, String>();
            pages.forEach((key, value) -> sourcePaths.put(value, key));
            for (var source : sources) compileIsolated(stage, source, application, compiledOutput, path, sourcePaths);
        }

        private void writeBootstrap(Path application, Set<String> originalPackages) throws IOException {
            byte[] bootstrap = module(originalModule, previous.provider(), null, originalPackages);
            var cf = ClassFile.of();
            bootstrap = cf.transformClass(cf.parse(bootstrap), ClassTransform.dropping(ModulePackagesAttribute.class::isInstance)
                    .andThen(ClassTransform.endHandler(builder -> builder.with(ModulePackagesAttribute.ofNames(
                            originalPackages.stream().map(PackageDesc::of).toList())))));
            Files.write(application.resolve(MODULE_INFO), bootstrap);
        }

        private void copyApplicationClasses(Path application) throws IOException {
            try (var files = Files.walk(output)) {
                for (Path file : classFiles(files)) {
                    String relative = relative(output, file);
                    if (relative.equals(MODULE_INFO) || previous.files().containsKey(relative)) continue;
                    Path target = application.resolve(relative);
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target);
                }
            }
        }

        private void compileTagFiles(List<GeneratedPage> tagSources, Path application, Path compiledOutput, String path)
                throws IOException {
            new PageCompiler().compile(tagSources, compiledOutput,
                    compilerArguments(path, application, compiledOutput, compiledOutput.resolve(SOURCES)));
            try (var files = Files.walk(compiledOutput)) {
                for (Path file : classFiles(files)) {
                    Path target = application.resolve(compiledOutput.relativize(file));
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        /** Compiles one page alone, replacing it by a translation-error servlet when javac rejects it. */
        private void compileIsolated(Path stage, GeneratedPage source, Path application, Path compiledOutput, String path,
                Map<String, String> sourcePaths) throws IOException {
            Path isolated = Files.createTempDirectory(stage, "page-");
            var arguments = compilerArguments(path, application, compiledOutput, isolated, isolated.resolve(SOURCES));
            try {
                new PageCompiler().compile(List.of(source), isolated, arguments);
            } catch (PageCompilationException failure) {
                String pagePath = sourcePaths.get(source.className());
                if (pagePath == null || failures.containsKey(pagePath)) throw failure;
                failures.put(pagePath, failure.getMessage());
                remove(isolated);
                Files.createDirectories(isolated);
                new PageCompiler().compile(List.of(translationError(source.className(), failure.getMessage())), isolated, arguments);
            }
            try (var files = Files.walk(isolated)) {
                for (Path file : classFiles(files)) {
                    Path target = compiledOutput.resolve(isolated.relativize(file));
                    if (Files.exists(target) && !Arrays.equals(Files.readAllBytes(target), Files.readAllBytes(file)))
                        throw new IllegalArgumentException("Conflicting generated class: " + target);
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            remove(isolated);
        }

        private List<String> compilerArguments(String path, Path... patches) {
            String patch = Arrays.stream(patches).map(Path::toString).collect(java.util.stream.Collectors.joining(File.pathSeparator));
            return List.of("--module-path", path, "--add-modules", descriptor.name(), "--patch-module", descriptor.name() + "=" + patch);
        }

        private Set<String> compiledClasses(Path compiledOutput) throws IOException {
            var compiled = new TreeSet<String>();
            try (var files = Files.walk(compiledOutput)) {
                for (Path file : classFiles(files)) {
                    String relative = relative(compiledOutput, file);
                    if (relative.equals(MODULE_INFO)) throw new IllegalArgumentException("Unexpected generated module descriptor");
                    if (Files.exists(output.resolve(relative)) && !previous.files().containsKey(relative))
                        throw new IllegalArgumentException("Generated class would overwrite an application class: " + relative);
                    compiled.add(relative);
                }
            }
            return compiled;
        }

        /** Copies the compiled classes, removes stale owned classes, patches the module and rewrites the manifest. */
        private void install(Path compiledOutput, Set<String> compiled, String provider, Set<String> originalPackages)
                throws IOException {
            var packages = new TreeSet<>(originalPackages);
            for (String relative : compiled) addPackage(packages, relative);
            byte[] patched = module(originalModule, previous.provider(), provider, packages);
            for (String relative : compiled) {
                Path target = output.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.copy(compiledOutput.resolve(relative), target, StandardCopyOption.REPLACE_EXISTING);
            }
            for (String relative : previous.files().keySet()) {
                if (compiled.contains(relative)) continue;
                Path target = output.resolve(relative);
                Files.deleteIfExists(target);
                emptyParents(target.getParent(), output);
            }
            Files.write(moduleFile, patched);
            var lines = new ArrayList<String>();
            lines.add(provider == null ? "" : provider);
            for (String relative : compiled) lines.add(relative + "\t" + digest(Files.readAllBytes(output.resolve(relative))));
            Files.write(manifest, lines, StandardCharsets.UTF_8);
        }
    }

    /** {@code file} relative to {@code root}, with '/' separators. */
    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static GeneratedPage translationError(String name, String diagnostic) {
        int dot = name.lastIndexOf('.');
        int end = Math.min(diagnostic.length(), 8_192);
        if (end < diagnostic.length() && end > 0 && Character.isHighSurrogate(diagnostic.charAt(end - 1))) end--;
        String message = "Page translation failed: " + diagnostic.substring(0, end);
        String source = "package " + name.substring(0, dot) + "; public final class " + name.substring(dot + 1)
                + " extends jakarta.servlet.http.HttpServlet implements jakarta.servlet.jsp.HttpJspPage {"
                + "public void jspInit(){} public void jspDestroy(){}"
                + "public void service(jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response) throws jakarta.servlet.ServletException { _jspService(request,response); }"
                + "public void _jspService(jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response) throws jakarta.servlet.ServletException { throw new jakarta.servlet.ServletException("
                + JavaSource.quote(message) + "); }}";
        return new GeneratedPage(name, source, Map.of());
    }

    private static Map<String, byte[]> resources(Path root) throws IOException {
        var result = new TreeMap<String, byte[]>();
        if (!Files.exists(root)) return result;
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("Web source root is not a directory: " + root);
        try (var files = Files.walk(root)) {
            for (Path file : files.sorted().toList()) {
                if (Files.isSymbolicLink(file)) throw new IllegalArgumentException("Symbolic web resources are unsupported: " + file);
                if (Files.isRegularFile(file)) result.put('/' + relative(root, file), Files.readAllBytes(file));
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
                builder.with(withPageIndexProvider(attribute, previous, provider));
            } else if (element instanceof ModulePackagesAttribute) {
                builder.with(ModulePackagesAttribute.ofNames(packages.stream().map(PackageDesc::of).toList()));
            } else builder.with(element);
        });
    }

    /** The module attribute with {@code previous} replaced by {@code provider} among the page index providers. */
    private static ModuleAttribute withPageIndexProvider(ModuleAttribute attribute, String previous, String provider) {
        var entries = new ArrayList<ModuleProvideInfo>();
        var implementations = new ArrayList<ClassDesc>();
        for (var entry : attribute.provides()) {
            if (!entry.provides().asSymbol().equals(ClassDesc.of(SERVICE))) entries.add(entry);
            else implementations.addAll(retainedImplementations(entry, previous));
        }
        if (provider != null && !implementations.contains(ClassDesc.of(provider))) implementations.add(ClassDesc.of(provider));
        if (!implementations.isEmpty()) entries.add(ModuleProvideInfo.of(ClassDesc.of(SERVICE), implementations));
        return ModuleAttribute.of(attribute.moduleName(), attribute.moduleFlagsMask(), attribute.moduleVersion().orElse(null),
                attribute.requires(), attribute.exports(), attribute.opens(), attribute.uses(), entries);
    }

    private static List<ClassDesc> retainedImplementations(ModuleProvideInfo entry, String previous) {
        var implementations = new ArrayList<ClassDesc>();
        for (var implementation : entry.providesWith()) {
            var symbol = implementation.asSymbol();
            if (previous == null || !symbol.equals(ClassDesc.of(previous))) implementations.add(symbol);
        }
        return implementations;
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
            requireOwnedClass(output, relative, provider != null && relative.startsWith(prefix));
            Path target = output.resolve(relative).normalize();
            if (Files.exists(target) && !digest(Files.readAllBytes(target)).equals(parts[1]))
                throw new IllegalArgumentException("Previously generated class was replaced: " + relative);
            files.put(relative, parts[1]);
        }
        return new Owned(provider, Map.copyOf(files));
    }

    /** Fails unless {@code relative} names a class file of the provider's package inside {@code output}. */
    private static void requireOwnedClass(Path output, String relative, boolean inProviderPackage) {
        Path target = output.resolve(relative).normalize();
        if (!inProviderPackage || !relative.endsWith(CLASS_SUFFIX)
                || !target.startsWith(output) || Path.of(relative).isAbsolute() || relative.contains(".."))
            throw new IllegalArgumentException("Invalid manifest class: " + relative);
    }

    private static void emptyParents(Path directory, Path output) throws IOException {
        while (directory != null && !directory.equals(output) && directory.startsWith(output)) {
            try { Files.deleteIfExists(directory); } catch (DirectoryNotEmptyException _) { return; }
            directory = directory.getParent();
        }
    }
    private static void remove(Path directory) throws IOException {
        try (var files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }
}
