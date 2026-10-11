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
    private final List<Path> extraModules = new ArrayList<>();

    @Test void compiledElRendersFromAnApplicationJarWithoutRuntimeCompilers() throws Exception {
        prepare(false, true);
        Files.writeString(web.resolve("index.jsp"), "<%@ page session='false' %>${1+2}|${requestScope.answer}|${[1,2,3][1]}|${((x)->x+1)(4)}");
        precompile();
        Path jar = directory.resolve("compiled-el.jar"); jar(classes, jar);
        assertEquals("3|42|2|5", render(jar, true));
    }

    @Test void removedElClassesAreCleanedUpWithoutDeletingApplicationClasses() throws Exception {
        prepare(false, true);
        Files.writeString(web.resolve("index.jsp"), "${1+2}"); precompile();
        Set<String> first = classFiles();
        assertTrue(first.stream().anyMatch(name -> name.contains("$Expression")));
        Files.writeString(web.resolve("index.jsp"), "plain"); precompile();
        assertTrue(classFiles().stream().noneMatch(name -> name.contains("$Expression")));
        assertTrue(Files.exists(classes.resolve("demo/Bean.class")));
    }
    @Test void compiledElRequiresAnExplicitRuntimeApiReadBeforePublishing() throws Exception {
        prepare(false);
        byte[] original = Files.readAllBytes(classes.resolve("module-info.class"));
        Files.writeString(web.resolve("index.jsp"), "${1+2}");
        var failure = assertThrows(IllegalArgumentException.class, this::precompile);
        assertTrue(failure.getMessage().contains("io.vidocq.peano.api"));
        assertArrayEquals(original, Files.readAllBytes(classes.resolve("module-info.class")));
    }

    @Test
    void relativeTaglibLocationsResolveAgainstWebInfWebXml() throws Exception {
        prepare(false); listenerClass();
        Files.createDirectories(web.resolve("WEB-INF/tlds")); Files.createDirectories(web.resolve("tlds"));
        Files.writeString(web.resolve("WEB-INF/tlds/relative.tld"), "<taglib><listener><listener-class>demo.Listener$Nested</listener-class></listener></taglib>");
        Files.writeString(web.resolve("tlds/relative.tld"), "<taglib><listener><listener-class>demo.Bean</listener-class></listener></taglib>");
        Files.writeString(web.resolve("WEB-INF/web.xml"), "<web-app><jsp-config><taglib><taglib-uri>urn:relative</taglib-uri><taglib-location>tlds/relative.tld</taglib-location></taglib></jsp-config></web-app>");
        precompile(); assertEquals("demo.Listener$Nested", listenerNames());
    }

    @Test
    void explicitTaglibLocationsDiscoverTldsOutsideWebInf() throws Exception {
        prepare(false); listenerClass();
        Files.createDirectories(web.resolve("WEB-INF"));
        Files.writeString(web.resolve("startup.tld"), "<taglib><listener><listener-class>demo.Listener</listener-class></listener></taglib>");
        Files.writeString(web.resolve("WEB-INF/web.xml"), "<web-app><jsp-config><taglib><taglib-uri>urn:startup</taglib-uri><taglib-location>/startup.tld</taglib-location></taglib></jsp-config></web-app>");
        assertNotNull(precompile().indexClass()); assertEquals("demo.Listener", listenerNames());
    }

    @Test
    void explicitJarLocationsUseMetaInfTaglibAndMissingLocationsFailTranslation() throws Exception {
        prepare(false); listenerClass();
        Files.createDirectories(web.resolve("WEB-INF"));
        Path jar = web.resolve("WEB-INF/metadata.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/taglib.tld"));
            out.write("<taglib><listener><listener-class>demo.Listener</listener-class></listener></taglib>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        Files.writeString(web.resolve("WEB-INF/web.xml"), "<web-app><jsp-config><taglib><taglib-uri>urn:startup</taglib-uri><taglib-location>metadata.jar</taglib-location></taglib></jsp-config></web-app>");
        assertNotNull(precompile().indexClass()); assertEquals("demo.Listener", listenerNames());
        Files.delete(jar);
        assertThrows(PageParseException.class, this::precompile);
    }

    @Test
    void nestedListenerClassesRetainBinaryIdentityAndUseLegalConstructorSourceNames() throws Exception {
        prepare(false); listenerClass();
        Files.createDirectories(web.resolve("WEB-INF"));
        Files.writeString(web.resolve("WEB-INF/nested.tld"), "<taglib><listener><listener-class>demo.Listener$Nested</listener-class></listener></taglib>");
        precompile(); assertEquals("demo.Listener$Nested", listenerNames());
    }

    @Test
    void namedLibraryTldsAreDiscoveredOnlyInsideTheApplicationDependencyGraph() throws Exception {
        prepare(false);
        Path source = directory.resolve("library-source"); Path library = directory.resolve("library");
        Files.createDirectories(source.resolve("library")); Files.createDirectories(library.resolve("META-INF/tags"));
        Files.writeString(source.resolve("module-info.java"), "module tag.library { requires jakarta.servlet; exports library; }");
        Files.writeString(source.resolve("library/Startup.java"), "package library; public class Startup implements jakarta.servlet.ServletContextListener { public Startup() throws java.lang.Throwable { } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "25", "--module-path", modulePath(),
                "-d", library.toString(), source.resolve("module-info.java").toString(), source.resolve("library/Startup.java").toString()));
        Files.writeString(library.resolve("META-INF/tags/library.tld"), "<taglib><uri>urn:library</uri><listener><listener-class>library.Startup</listener-class></listener></taglib>");
        Path jar = directory.resolve("tag-library.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar)); var files = Files.walk(library)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new JarEntry(library.relativize(file).toString().replace('\\', '/'))); Files.copy(file, out); out.closeEntry();
            }
        }
        extraModules.add(jar);
        Path info = directory.resolve("original/module-info.java");
        Files.writeString(info, "module demo.web { requires io.vidocq.ibarra.api; requires tag.library; }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "25", "--module-path", modulePath(), "-d", classes.toString(), info.toString()));
        precompile(); assertEquals("library.Startup", listenerNames());
        Files.writeString(info, "module demo.web { requires io.vidocq.ibarra.api; }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "25", "--module-path", modulePath(), "-d", classes.toString(), info.toString()));
        assertNull(precompile().indexClass());
    }

    @Test
    void applicationMetaInfTldsProvideListenersWithoutWebSourcePages() throws Exception {
        prepare(false); listenerClass();
        Files.createDirectories(classes.resolve("META-INF/tags"));
        Files.writeString(classes.resolve("META-INF/tags/local.tld"), "<taglib><listener><listener-class>demo.Listener</listener-class></listener></taglib>");
        assertNotNull(precompile().indexClass()); assertEquals("demo.Listener", listenerNames());
    }

    @Test
    void listenerOnlyApplicationsGenerateAnIndexWithoutRunningListenerInitialization() throws Exception {
        prepare(false);
        listenerClass();
        Files.createDirectories(web.resolve("WEB-INF"));
        Files.writeString(web.resolve("WEB-INF/one.tld"), "<taglib><listener><listener-class>demo.Listener</listener-class></listener></taglib>");
        Files.writeString(web.resolve("WEB-INF/two.tld"), "<taglib><listener><listener-class>demo.Listener</listener-class></listener></taglib>");
        Files.createDirectories(web.resolve("WEB-INF/classes")); Files.createDirectories(web.resolve("WEB-INF/lib"));
        Files.writeString(web.resolve("WEB-INF/classes/ignored.tld"), "invalid XML excluded from TLD scanning");
        Files.writeString(web.resolve("WEB-INF/lib/ignored.tld"), "invalid XML excluded from loose TLD scanning");
        var original = classFiles();
        var result = precompile();
        assertNotNull(result.indexClass());
        assertTrue(result.pagePaths().isEmpty());
        assertEquals("demo.Listener", listenerNames());
        Files.delete(web.resolve("WEB-INF/one.tld")); Files.delete(web.resolve("WEB-INF/two.tld"));
        assertNull(precompile().indexClass());
        assertEquals(original, classFiles());
    }

    @Test
    void unsupportedListenerTypesFailBeforeReplacingAnExistingGeneratedCatalogue() throws Exception {
        prepare(false); Files.writeString(web.resolve("index.jsp"), "original");
        precompile();
        byte[] module = Files.readAllBytes(classes.resolve("module-info.class"));
        Files.createDirectories(web.resolve("WEB-INF"));
        Files.writeString(web.resolve("WEB-INF/wrong.tld"), "<taglib><listener><listener-class>demo.Bean</listener-class></listener></taglib>");
        var failure = assertThrows(PageParseException.class, this::precompile);
        assertTrue(failure.getMessage().contains("wrong.tld"));
        assertArrayEquals(module, Files.readAllBytes(classes.resolve("module-info.class")));
    }

    private void listenerClass() throws Exception {
        Path source = directory.resolve("listener-source/demo/Listener.java"); Files.createDirectories(source.getParent());
        Files.writeString(source, "package demo; public class Listener implements jakarta.servlet.ServletContextListener { static { if (Boolean.parseBoolean(\"true\")) throw new AssertionError(\"Listener initialized during metadata discovery\"); } public static class Nested implements jakarta.servlet.ServletContextListener { } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "25", "--module-path",
                modulePath() + File.pathSeparator + classes, "--patch-module", "demo.web=" + source.getParent().getParent(),
                "-d", classes.toString(), source.toString()));
    }

    private String listenerNames() throws Exception {
        Path harness = directory.resolve("listener-harness");
        String main = "package io.vidocq.ibarra.core.internal; public class ListenerMain { public static void main(String[] args) { var indexes = java.util.ServiceLoader.load(ModuleLayer.boot(), io.vidocq.ibarra.spi.PageIndex.class); for (var index : indexes) for (var listener : index.listeners()) System.out.print(listener.type().getName()); } }";
        new PageCompiler().compile(List.of(new GeneratedPage("io.vidocq.ibarra.core.internal.ListenerMain", main, Map.of())), harness,
                List.of("--module-path", modulePath(), "--add-modules", "io.vidocq.ibarra.core", "--patch-module", "io.vidocq.ibarra.core=" + harness.resolve(".ibarra-sources")));
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "--limit-modules", "io.vidocq.ibarra.core,demo.web", "--module-path", modulePath() + File.pathSeparator + classes,
                "--add-modules", "demo.web", "--patch-module", "io.vidocq.ibarra.core=" + harness,
                "-m", "io.vidocq.ibarra.core/io.vidocq.ibarra.core.internal.ListenerMain")
                .redirectErrorStream(true).redirectOutput(directory.resolve("listener.log").toFile()).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        var output = Files.readString(directory.resolve("listener.log")); assertEquals(0, process.exitValue(), output);
        return output;
    }

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
        Files.writeString(web.resolve("index.jsp"), "${1 + }");
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
        prepare(manual, false);
    }
    private void prepare(boolean manual, boolean el) throws Exception {
        classes = directory.resolve("classes"); web = directory.resolve("web");
        Files.createDirectories(classes); Files.createDirectories(web);
        Path sources = directory.resolve("original"); Files.createDirectories(sources.resolve("demo"));
        Files.writeString(sources.resolve("module-info.java"), "module demo.web { requires io.vidocq.ibarra.api;"
                + (el ? " requires io.vidocq.peano.api;" : "")
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
    private void jar(Path output, Path target) throws Exception {
        try (var stream = new JarOutputStream(Files.newOutputStream(target)); var files = Files.walk(output)) {
            for (var file : files.filter(Files::isRegularFile).sorted().toList()) {
                stream.putNextEntry(new JarEntry(output.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, stream); stream.closeEntry();
            }
        }
    }

    private List<Path> modulePaths() { return Arrays.stream(modulePath().split(java.util.regex.Pattern.quote(File.pathSeparator))).map(Path::of).toList(); }
    private String modulePath() {
        var names = Set.of("io.vidocq.ibarra.api", "io.vidocq.ibarra.core", "jakarta.servlet", "jakarta.servlet.jsp", "jakarta.el", "io.vidocq.peano.api", "io.vidocq.peano.core");
        String available = System.getProperty("jdk.module.path", "") + File.pathSeparator + System.getProperty("surefire.test.class.path", "");
        var paths = Arrays.stream(available.split(java.util.regex.Pattern.quote(File.pathSeparator)))
                .filter(path -> !path.isEmpty()).map(Path::of)
                .filter(path -> path.toString().endsWith(".jar") || Files.isRegularFile(path.resolve("module-info.class")))
                .distinct().toArray(Path[]::new);
        String base = ModuleFinder.of(paths).findAll().stream().filter(module -> names.contains(module.descriptor().name()))
                .map(module -> Path.of(module.location().orElseThrow()).toString()).collect(java.util.stream.Collectors.joining(File.pathSeparator));
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(base), extraModules.stream().map(Path::toString))
                .collect(java.util.stream.Collectors.joining(File.pathSeparator));
    }

    private String render(Path jar) throws Exception {
        return render(jar, false);
    }
    private String render(Path jar, boolean el) throws Exception {
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
                                || ModuleLayer.boot().findModule("io.vidocq.peano.compiler").isPresent()
                                || ModuleLayer.boot().findModule("java.compiler").isPresent()) throw new AssertionError("Compiler resolved at runtime");
                        %s
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
                        var request = new ServletDoubles.Request(); request.setAttribute("answer", 42);
                        page.service(request, response);
                        System.out.print("RESULT=" + java.util.Base64.getEncoder().encodeToString(response.output.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    }
                }
                """.formatted(el ? noParsingFactory() : "JspFactory.setDefaultFactory(new IbarraJspFactory());");
        Path harness = directory.resolve("harness");
        new PageCompiler().compile(List.of(new GeneratedPage("io.vidocq.ibarra.core.internal.PackagedMain", main, Map.of()),
                new GeneratedPage("io.vidocq.ibarra.core.internal.ServletDoubles", doubles, Map.of())), harness,
                List.of("--module-path", modulePath(), "--add-modules", "io.vidocq.ibarra.core",
                        "--patch-module", "io.vidocq.ibarra.core=" + harness.resolve(".ibarra-sources")));
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "--limit-modules", "io.vidocq.ibarra.core,demo.web" + (el ? ",io.vidocq.peano.core" : ""),
                "--module-path", modulePath() + File.pathSeparator + jar, "--add-modules", "demo.web" + (el ? ",io.vidocq.peano.core" : ""),
                "--patch-module", "io.vidocq.ibarra.core=" + harness,
                "-m", "io.vidocq.ibarra.core/io.vidocq.ibarra.core.internal.PackagedMain")
                .redirectErrorStream(true).redirectOutput(directory.resolve("render.log").toFile()).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        String output = Files.readString(directory.resolve("render.log"));
        assertEquals(0, process.exitValue(), output);
        return new String(Base64.getDecoder().decode(output.substring(output.indexOf("RESULT=") + 7).trim()), java.nio.charset.StandardCharsets.UTF_8);
    }
    private String noParsingFactory() {
        return """
                var delegate = jakarta.el.ExpressionFactory.newInstance();
                var application = new IbarraApplicationContext(new jakarta.el.ExpressionFactory() {
                    public jakarta.el.ValueExpression createValueExpression(jakarta.el.ELContext context, String text, Class<?> type) { throw new AssertionError("Runtime parsing: " + text); }
                    public jakarta.el.ValueExpression createValueExpression(Object value, Class<?> type) { throw new AssertionError("Runtime expression creation"); }
                    public jakarta.el.MethodExpression createMethodExpression(jakarta.el.ELContext context, String text, Class<?> type, Class<?>[] parameters) { throw new AssertionError("Runtime method parsing"); }
                    public jakarta.el.ELResolver getStreamELResolver() { return delegate.getStreamELResolver(); }
                    public <T> T coerceToType(Object value, Class<T> type) { return delegate.coerceToType(value, type); }
                });
                JspFactory.setDefaultFactory(new JspFactory() {
                    public PageContext getPageContext(Servlet servlet, ServletRequest request, ServletResponse response, String error, boolean session, int buffer, boolean autoFlush) {
                        var context = new IbarraPageContext(application);
                        try { context.initialize(servlet, request, response, error, session, buffer, autoFlush); }
                        catch(java.io.IOException failure) { throw new IllegalStateException(failure); }
                        return context;
                    }
                    public void releasePageContext(PageContext context) { context.release(); }
                    public jakarta.servlet.jsp.JspApplicationContext getJspApplicationContext(ServletContext context) { return application; }
                    public jakarta.servlet.jsp.JspEngineInfo getEngineInfo() { return new IbarraJspFactory().getEngineInfo(); }
                });
                """;
    }
}
