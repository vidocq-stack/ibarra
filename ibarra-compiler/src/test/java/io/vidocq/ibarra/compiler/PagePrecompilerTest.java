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

import java.io.File;
import java.lang.module.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PagePrecompilerTest {
    @TempDir Path directory;
    private Path classes;
    private Path web;

    @Test
    void packagedApplicationIsDiscoveredAndRendersWithoutCompilerAtRuntime() throws Exception {
        prepare(false);
        Files.writeString(web.resolve("index.jsp"), "A<jsp:useBean id='b' class='demo.Bean'/><jsp:getProperty name='b' property='value'/><%@ include file='part.jspf' %>");
        Files.writeString(web.resolve("part.jspf"), "<%@ page pageEncoding='UTF-8' %>é");
        var result = precompile();
        assertEquals(Set.of("/index.jsp"), result.pagePaths());
        var descriptor = ModuleFinder.of(classes).find("demo.web").orElseThrow().descriptor();
        assertTrue(descriptor.exports().isEmpty());
        assertEquals(1, descriptor.provides().size());
        Path jar = directory.resolve("application.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar)); var files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String name = classes.relativize(file).toString().replace('\\', '/');
                assertFalse(name.endsWith(".java"));
                assertFalse(name.startsWith("META-INF/services/"));
                output.putNextEntry(new JarEntry(name)); Files.copy(file, output); output.closeEntry();
            }
        }
        assertEquals("ABé", render(jar));
    }

    @Test
    void incrementalRunsRemoveOnlyOwnedClassesAndPreserveOtherServiceProviders() throws Exception {
        prepare(true);
        Files.writeString(web.resolve("old.jsp"), "old");
        precompile();
        var original = Files.readAllBytes(classes.resolve("demo/Bean.class"));
        Files.delete(web.resolve("old.jsp"));
        Files.writeString(web.resolve("new.jsp"), "new");
        var result = new PagePrecompiler().precompile(web, classes, modulePaths(), "demo.changed", GenerationOptions.DEFAULT);
        assertEquals(Set.of("/new.jsp"), result.pagePaths());
        assertArrayEquals(original, Files.readAllBytes(classes.resolve("demo/Bean.class")));
        assertFalse(Files.exists(classes.resolve("demo/generated")));
        var provided = ModuleFinder.of(classes).find("demo.web").orElseThrow().descriptor().provides().iterator().next();
        assertEquals(Set.of("demo.ManualIndex", "demo.changed.GeneratedPageIndex"), Set.copyOf(provided.providers()));
    }

    @Test
    void generationFailureKeepsThePreviousModuleAndPages() throws Exception {
        prepare(false); Files.writeString(web.resolve("index.jsp"), "good"); precompile();
        byte[] module = Files.readAllBytes(classes.resolve("module-info.class"));
        var before = classFiles();
        Files.writeString(web.resolve("index.jsp"), "$" + "{missing}");
        assertThrows(PageParseException.class, this::precompile);
        assertArrayEquals(module, Files.readAllBytes(classes.resolve("module-info.class")));
        assertEquals(before, classFiles());
        assertThrows(IllegalArgumentException.class, () -> new PagePrecompiler().precompile(web, classes,
                modulePaths(), "demo.generated", GenerationOptions.DEFAULT.withStartupEl(true)));
    }

    @Test
    void removingAllPagesRemovesOnlyTheGeneratedProvider() throws Exception {
        prepare(true); Files.writeString(web.resolve("index.jsp"), "page"); precompile();
        Files.delete(web.resolve("index.jsp")); assertTrue(precompile().pagePaths().isEmpty());
        var descriptor = ModuleFinder.of(classes).find("demo.web").orElseThrow().descriptor();
        assertEquals(List.of("demo.ManualIndex"), descriptor.provides().iterator().next().providers());
        assertFalse(Files.exists(classes.resolve("demo/generated")));
    }

    @Test
    void missingModuleMetadataAndEscapingSymbolicResourcesAreRejected() throws Exception {
        prepare(false);
        Files.delete(classes.resolve("module-info.class"));
        assertThrows(IllegalArgumentException.class, this::precompile);
        prepare(false);
        Path outside = directory.resolve("outside.jsp"); Files.writeString(outside, "outside");
        Files.createSymbolicLink(web.resolve("escape.jsp"), outside);
        assertThrows(IllegalArgumentException.class, this::precompile);
    }

    @Test
    void invalidPagePathsFailBeforePublishingTheIndex() throws Exception {
        prepare(false); Files.writeString(web.resolve("bad#name.jsp"), "page");
        byte[] module = Files.readAllBytes(classes.resolve("module-info.class"));
        assertThrows(IllegalArgumentException.class, this::precompile);
        assertArrayEquals(module, Files.readAllBytes(classes.resolve("module-info.class")));
    }

    @Test
    void replacedOwnedClassesAreNeitherOverwrittenNorDeleted() throws Exception {
        prepare(false); Files.writeString(web.resolve("index.jsp"), "page"); precompile();
        Path generated;
        try (var files = Files.walk(classes.resolve("demo/generated"))) {
            generated = files.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
        byte[] replacement = "application-owned replacement".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(generated, replacement);
        assertThrows(IllegalArgumentException.class, this::precompile);
        Files.delete(web.resolve("index.jsp"));
        assertThrows(IllegalArgumentException.class, this::precompile);
        assertArrayEquals(replacement, Files.readAllBytes(generated));
    }

    @Test
    void existingModulePackageMetadataDropsRemovedGeneratedPackages() throws Exception {
        prepare(true); Files.writeString(web.resolve("index.jsp"), "page"); precompile();
        Path info = classes.resolve("module-info.class");
        var cf = java.lang.classfile.ClassFile.of();
        Files.write(info, cf.transformClass(cf.parse(info), java.lang.classfile.ClassTransform.endHandler(builder ->
                builder.with(java.lang.classfile.attribute.ModulePackagesAttribute.ofNames(
                        java.lang.constant.PackageDesc.of("demo"), java.lang.constant.PackageDesc.of("demo.generated"))))));
        Files.delete(web.resolve("index.jsp")); precompile();
        assertEquals(Set.of("demo"), ModuleFinder.of(classes).find("demo.web").orElseThrow().descriptor().packages());
    }

    @Test
    void missingOwnedDirectoriesDoNotPreventRemovingPages() throws Exception {
        prepare(false); Files.writeString(web.resolve("index.jsp"), "page"); precompile();
        try (var files = Files.walk(classes.resolve("demo/generated"))) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
        Files.delete(web.resolve("index.jsp"));
        assertTrue(precompile().pagePaths().isEmpty());
        assertTrue(ModuleFinder.of(classes).find("demo.web").orElseThrow().descriptor().provides().isEmpty());
    }

    @Test
    void missingOwnedDirectoriesCanBeRebuiltWithoutChangingApplicationMetadata() throws Exception {
        prepare(true); Files.writeString(web.resolve("index.jsp"), "page"); precompile();
        try (var files = Files.walk(classes.resolve("demo/generated"))) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
        assertEquals(Set.of("/index.jsp"), precompile().pagePaths());
        assertTrue(Files.isRegularFile(classes.resolve("demo/generated/GeneratedPageIndex.class")));
    }

    @Test
    void defaultGeneratedPackageBelongsToTheApplicationModule() throws Exception {
        prepare(false); Files.writeString(web.resolve("index.jsp"), "page");
        var result = new PagePrecompiler().precompile(web, classes, modulePaths(), null, GenerationOptions.DEFAULT);
        assertEquals("demo.web.ibarra_generated.GeneratedPageIndex", result.indexClass());
    }

    private PagePrecompiler.Result precompile() throws Exception {
        return new PagePrecompiler().precompile(web, classes, modulePaths(), "demo.generated", GenerationOptions.DEFAULT);
    }

    private void prepare(boolean manual) throws Exception {
        classes = directory.resolve("classes"); web = directory.resolve("web");
        Files.createDirectories(classes); Files.createDirectories(web);
        Path sources = directory.resolve("original"); Files.createDirectories(sources.resolve("demo"));
        Files.writeString(sources.resolve("module-info.java"), "module demo.web { requires io.vidocq.ibarra.api;"
                + (manual ? " provides io.vidocq.ibarra.spi.PageIndex with demo.ManualIndex;" : "") + " }");
        Files.writeString(sources.resolve("demo/Bean.java"), "package demo; public class Bean { public String getValue() { return \"B\"; } }");
        var inputs = new ArrayList<String>(List.of("--release", "25", "-proc:none", "--module-path", modulePath(), "-d", classes.toString(),
                sources.resolve("module-info.java").toString(), sources.resolve("demo/Bean.java").toString()));
        if (manual) {
            Path index = sources.resolve("demo/ManualIndex.java");
            Files.writeString(index, "package demo; public class ManualIndex implements io.vidocq.ibarra.spi.PageIndex { public java.util.List<io.vidocq.ibarra.spi.PageDescriptor> pages() { return java.util.List.of(); } }");
            inputs.add(index.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, inputs.toArray(String[]::new)));
    }

    private Set<String> classFiles() throws Exception {
        try (var files = Files.walk(classes)) {
            return files.filter(Files::isRegularFile).map(classes::relativize).map(Path::toString).collect(java.util.stream.Collectors.toSet());
        }
    }

    private List<Path> modulePaths() { return Arrays.stream(modulePath().split(java.util.regex.Pattern.quote(File.pathSeparator))).map(Path::of).toList(); }
    private String modulePath() {
        var names = Set.of("io.vidocq.ibarra.api", "io.vidocq.ibarra.core", "jakarta.servlet", "jakarta.servlet.jsp", "jakarta.el");
        String available = System.getProperty("jdk.module.path", "") + File.pathSeparator + System.getProperty("surefire.test.class.path", "");
        var paths = Arrays.stream(available.split(java.util.regex.Pattern.quote(File.pathSeparator)))
                .filter(path -> !path.isEmpty()).map(Path::of)
                .filter(path -> path.toString().endsWith(".jar") || Files.isRegularFile(path.resolve("module-info.class")))
                .distinct().toArray(Path[]::new);
        return ModuleFinder.of(paths).findAll().stream().filter(module -> names.contains(module.descriptor().name()))
                .map(module -> Path.of(module.location().orElseThrow()).toString()).collect(java.util.stream.Collectors.joining(File.pathSeparator));
    }

    private String render(Path jar) throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (!Files.isDirectory(root.resolve("ibarra-core"))) root = root.getParent();
        String doubles = Files.readString(root.resolve("ibarra-core/src/test/java/io/vidocq/ibarra/core/internal/ServletDoubles.java"));
        String main = """
                package io.vidocq.ibarra.core.internal;
                import jakarta.servlet.*;
                import jakarta.servlet.jsp.*;
                public class PackagedMain {
                    public static void main(String[] args) throws Exception {
                        if (ModuleLayer.boot().findModule("io.vidocq.ibarra.compiler").isPresent()
                                || ModuleLayer.boot().findModule("java.compiler").isPresent()) throw new AssertionError("Compiler resolved at runtime");
                        JspFactory.setDefaultFactory(new IbarraJspFactory());
                        var catalogue = PrecompiledPages.discover(ModuleLayer.boot());
                        var descriptor = catalogue.find("/index.jsp");
                        if (descriptor == null) throw new AssertionError("No precompiled page");
                        var page = descriptor.createPage();
                        var context = new ServletDoubles.Context();
                        ServletConfig config = new ServletConfig() {
                            public String getServletName() { return "index"; }
                            public ServletContext getServletContext() { return context; }
                            public String getInitParameter(String name) { return null; }
                            public java.util.Enumeration<String> getInitParameterNames() { return java.util.Collections.emptyEnumeration(); }
                        };
                        page.init(config);
                        var response = new ServletDoubles.Response();
                        page.service(new ServletDoubles.Request(), response);
                        System.out.print("RESULT=" + java.util.Base64.getEncoder().encodeToString(response.output.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    }
                }
                """;
        Path harness = directory.resolve("harness");
        new PageCompiler().compile(List.of(new GeneratedPage("io.vidocq.ibarra.core.internal.PackagedMain", main, Map.of()),
                new GeneratedPage("io.vidocq.ibarra.core.internal.ServletDoubles", doubles, Map.of())), harness,
                List.of("--module-path", modulePath(), "--add-modules", "io.vidocq.ibarra.core",
                        "--patch-module", "io.vidocq.ibarra.core=" + harness.resolve(".ibarra-sources")));
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "--limit-modules", "io.vidocq.ibarra.core,demo.web",
                "--module-path", modulePath() + File.pathSeparator + jar, "--add-modules", "demo.web",
                "--patch-module", "io.vidocq.ibarra.core=" + harness,
                "-m", "io.vidocq.ibarra.core/io.vidocq.ibarra.core.internal.PackagedMain")
                .redirectErrorStream(true).redirectOutput(directory.resolve("render.log").toFile()).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        String output = Files.readString(directory.resolve("render.log"));
        assertEquals(0, process.exitValue(), output);
        return new String(Base64.getDecoder().decode(output.substring(output.indexOf("RESULT=") + 7).trim()), java.nio.charset.StandardCharsets.UTF_8);
    }
}
