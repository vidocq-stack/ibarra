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

import io.vidocq.foy.internal.webxml.WebAppDescriptor;
import io.vidocq.foy.internal.webxml.WebXmlParser;
import io.vidocq.ibarra.compiler.GenerationOptions;
import io.vidocq.ibarra.compiler.JspConfigurationParser;
import io.vidocq.ibarra.compiler.JspPropertyGroup;
import io.vidocq.ibarra.compiler.PagePrecompiler;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import javax.tools.ToolProvider;
import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.container.LifecycleException;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.arquillian.container.spi.client.protocol.metadata.Servlet;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.jboss.shrinkwrap.descriptor.api.Descriptor;

/** Build-side Arquillian adapter. The server process contains only named runtime modules. */
public final class PagesContainer implements DeployableContainer<PagesConfiguration> {
    private static final String WORK_PROPERTY = "ibarra.tck.work";
    private static final String DEFAULT_WORK = "target/pages-deployments";
    private static final String WEB_INF_CLASSES = "WEB-INF/classes/";
    private static final String WEB_XML = "WEB-INF/web.xml";
    private static final String SERVLET_CONTAINER_INITIALIZERS = "META-INF/services/jakarta.servlet.ServletContainerInitializer";
    private static final Set<String> SERVLET_ANNOTATIONS = Set.of("Ljakarta/servlet/annotation/WebServlet;",
            "Ljakarta/servlet/annotation/WebFilter;", "Ljakarta/servlet/annotation/WebListener;");
    private static final String MODULE_INFO_SOURCE = "module-info.java";
    private static final String MODULE_INFO_CLASS = "module-info.class";
    private static final String BEAN_ACCESSOR_ERRORS = "bean-accessor-errors.txt";
    private static final String TRANSLATION_ERRORS = "translation-errors.txt";
    private static final String SERVER_LOG = "server.log";
    private static final String WEB_COMPONENT = "io.vidocq.foy.spi.gen.WebComponent";
    private static final String ACCESSOR_PROVIDER = "io.vidocq.peano.spi.AccessorProvider";
    private static final String EL_MODULE = "jakarta.el";
    private static final List<String> HOST_SOURCES = List.of(MODULE_INFO_SOURCE, "host/Main.java");
    private static final String HOST_MODULE = "tck.host";
    private static final String HOST_MAIN = HOST_MODULE + "/host.Main";
    private static final String CHAPPE_CORE = "io.vidocq.chappe.core";
    private static final String MODULE_PATH = "--module-path";
    private static final String OUTPUT_DIRECTORY = "-d";
    private static final String READY = "READY:";
    private static final long STARTUP_TIMEOUT_SECONDS = 20;
    private static final long STARTUP_POLL_MILLIS = 20;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 15;
    private static final long TERMINATION_TIMEOUT_SECONDS = 10;
    private static final long TERMINATION_POLL_MILLIS = 100;
    private static final Set<String> RUNTIME_MODULES = Set.of("io.vidocq.ibarra.api", "io.vidocq.ibarra.core",
            "io.vidocq.ibarra.foy", "io.vidocq.foy.api", "io.vidocq.foy.core", "io.vidocq.peano.api", "io.vidocq.peano.core",
            "jakarta.servlet", "jakarta.servlet.jsp", EL_MODULE);
    private static final String CHAPPE_PREFIX = "io.vidocq.chappe.";
    private static final String COMPILER_MODULE = "io.vidocq.ibarra.compiler";
    /**
     * The build-side compilers the server must never contain: the same list as the guard of the host
     * {@code Main}, which is compiled apart and cannot read this constant.
     */
    static final Set<String> BUILD_TOOLING = Set.of("java.compiler", COMPILER_MODULE, "io.vidocq.peano.compiler");
    /** The end of every refusal of a configured module that is, or brings, build tooling. */
    static final String BUILD_TOOLING_REFUSAL = " is build tooling, which the server must never contain";
    private static final Pattern VERSIONED_DESCRIPTOR = Pattern.compile("META-INF/versions/[0-9]+/module-info.class");
    private static final String DESCRIPTOR_DEPLOYMENT_UNSUPPORTED = "Descriptor-only deployment is unsupported";

    private record Running(Process process, Path work) { }

    /** Directories and build-side module path of one deployment. */
    private record Layout(Path work, Path web, Path classes, List<Path> modules) { }

    private final Map<String, Running> deployments = new LinkedHashMap<>();
    /** Where the runtime modules and the configured libraries are looked up. */
    private final List<Path> lookupPath;
    private List<ApplicationLibraries.Library> libraries = List.of();
    private List<String> jdkModules = List.of();
    private List<String> applicationOpens = List.of();

    /** An adapter that looks up its modules on the test class path: {@code jdk.module.path} plus the class path. */
    public PagesContainer() {
        this(testClassPath());
    }

    /** An adapter that looks up its modules on {@code searchPath}, a platform path list. */
    PagesContainer(String searchPath) {
        this.lookupPath = searchPath(searchPath);
    }

    private static String testClassPath() {
        return System.getProperty("jdk.module.path", "") + File.pathSeparator
                + System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    }

    /** The non-empty entries of a platform path list. */
    static List<Path> searchPath(String path) {
        return Arrays.stream(path.split(Pattern.quote(File.pathSeparator))).filter(item -> !item.isEmpty()).map(Path::of).toList();
    }

    @Override
    public Class<PagesConfiguration> getConfigurationClass() {
        return PagesConfiguration.class;
    }

    @Override
    public void setup(PagesConfiguration configuration) {
        configuration.validate();
        libraries = ApplicationLibraries.resolve(configuration.libraryNames(), lookupPath);
        jdkModules = configuration.jdkModuleNames();
        applicationOpens = configuration.openedPackageNames();
    }

    @Override
    public void start() {
        // Nothing to start: every deployment launches its own server process.
    }

    @Override
    public ProtocolDescription getDefaultProtocol() {
        return new ProtocolDescription("Servlet 3.0");
    }

    @Override
    public ProtocolMetaData deploy(Archive<?> archive) throws DeploymentException {
        if (!(archive instanceof WebArchive)) {
            throw new IllegalArgumentException("Pages TCK requires a WebArchive");
        }
        String name = archive.getName();
        if (deployments.containsKey(name)) {
            throw new DeploymentException("Archive is already deployed: " + name);
        }
        Process process = null;
        Path work = null;
        try {
            work = createWorkDirectory();
            Path web = Files.createDirectories(work.resolve("web"));
            Path classes = Files.createDirectories(work.resolve("application"));
            extract(archive, web, classes);
            rejectUnadaptedMetadata(classes);
            var groups = jspPropertyGroups(web);
            var descriptor = generateComponents(web, classes);
            explicitLibraries().forEach(library -> descriptor.requires(library.name()));
            openPackages(classes, descriptor);
            var layout = new Layout(work, web, classes, buildModulePath());
            compileDescriptor(layout, descriptor);
            var accessorDiagnostics = new ArrayList<String>();
            generateBeanAccessors(layout, descriptor, accessorDiagnostics);
            precompilePages(layout, groups);
            generateCollectionAccessors(layout, accessorDiagnostics);
            Path host = buildHost(layout);
            String context = "/" + name.replaceFirst("\\.war$", "");
            process = launch(layout, host, applicationPath(classes), context);
            int port = awaitReady(process, work.resolve(SERVER_LOG));
            deployments.put(name, new Running(process, work));
            return new ProtocolMetaData().addContext(new HTTPContext("127.0.0.1", port).add(new Servlet("Pages", context)));
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw abandon(name, process, work, failure);
        }
    }

    /** Opens each configured package, which the application classes must hold directly (a subpackage alone does not count). */
    private void openPackages(Path classes, ApplicationDescriptor descriptor) throws IOException {
        for (String packageName : applicationOpens) {
            Path directory = classes.resolve(packageName.replace('.', File.separatorChar));
            if (!holdsFile(directory)) {
                throw new IOException("Configured package to open is absent from the application: " + packageName);
            }
            descriptor.opens(packageName);
        }
    }

    private static boolean holdsFile(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (var files = Files.list(directory)) {
            return files.anyMatch(Files::isRegularFile);
        }
    }

    /** The libraries the build side compiles against: automatic modules stay runtime-only. */
    private List<ApplicationLibraries.Library> explicitLibraries() {
        return libraries.stream().filter(library -> !library.automatic()).toList();
    }

    /** The adapter's runtime and compiler modules, then the explicit libraries. */
    private List<Path> buildModulePath() throws IOException {
        var modules = new ArrayList<>(modulePaths(lookupPath, true));
        explicitLibraries().forEach(library -> modules.add(library.location()));
        return modules;
    }

    /** The application layer of the server: the application classes, then every library. */
    private List<Path> applicationPath(Path classes) {
        var path = new ArrayList<>(List.of(classes));
        libraries.forEach(library -> path.add(library.location()));
        return path;
    }

    private static Path createWorkDirectory() throws IOException {
        Path base = Path.of(System.getProperty(WORK_PROPERTY, DEFAULT_WORK)).toAbsolutePath();
        Files.createDirectories(base);
        return Files.createTempDirectory(base, "war-");
    }

    /** Copies the WAR: {@code WEB-INF/classes} into the application directory, everything else into the web root. */
    private static void extract(Archive<?> archive, Path web, Path classes) throws IOException {
        for (var entry : archive.getContent().entrySet()) {
            var asset = entry.getValue().getAsset();
            if (asset != null) {
                copy(asset, entry.getKey().get().replaceFirst("^/", ""), web, classes);
            }
        }
        WarLibraries.assemble(web, classes);
    }

    private static void copy(Asset asset, String name, Path web, Path classes) throws IOException {
        boolean application = name.startsWith(WEB_INF_CLASSES);
        Path root = application ? classes : web;
        String relative = application ? name.substring(WEB_INF_CLASSES.length()) : name;
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root)) {
            throw new IOException("Unsafe archive path: " + name);
        }
        Files.createDirectories(file.getParent());
        try (var input = asset.openStream()) {
            Files.copy(input, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Refuses the WAR metadata that would need runtime discovery: container initializers and Servlet annotations. */
    private static void rejectUnadaptedMetadata(Path classes) throws IOException {
        if (Files.exists(classes.resolve(SERVLET_CONTAINER_INITIALIZERS))) {
            throw new IOException("WAR ServletContainerInitializer factories are not yet adapted by this runner");
        }
        try (var files = Files.walk(classes)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                if (hasServletAnnotation(file)) {
                    throw new IOException("WAR Servlet annotations need build-generated factories: " + file);
                }
            }
        }
    }

    private static boolean hasServletAnnotation(Path file) throws IOException {
        var annotations = ClassFile.of().parse(Files.readAllBytes(file)).findAttribute(Attributes.runtimeVisibleAnnotations());
        return annotations.isPresent() && annotations.get().annotations().stream()
                .anyMatch(annotation -> SERVLET_ANNOTATIONS.contains(annotation.className().stringValue()));
    }

    private static List<JspPropertyGroup> jspPropertyGroups(Path web) throws IOException {
        Path webXml = web.resolve(WEB_XML);
        if (!Files.exists(webXml)) {
            return List.of();
        }
        return new JspConfigurationParser().parse("/" + WEB_XML, Files.readAllBytes(webXml));
    }

    /** Generates direct factories for the web.xml servlets, filters and listeners, and the descriptor that provides them. */
    private static ApplicationDescriptor generateComponents(Path web, Path classes) throws IOException {
        Path webXml = web.resolve(WEB_XML);
        var components = new TreeSet<String>();
        if (Files.exists(webXml)) {
            try (var input = Files.newInputStream(webXml)) {
                var model = WebXmlParser.parse(input);
                model.servlets().stream().map(WebAppDescriptor.ServletDef::className).filter(Objects::nonNull).forEach(components::add);
                model.filters().stream().map(WebAppDescriptor.FilterDef::className).forEach(components::add);
                components.addAll(model.listenerClasses());
            }
        }
        return ApplicationDescriptor.base().provides(WEB_COMPONENT, ComponentFactories.generate(classes, components));
    }

    private static void compileDescriptor(Layout layout, ApplicationDescriptor descriptor) throws IOException {
        Path source = layout.work().resolve(MODULE_INFO_SOURCE);
        Files.writeString(source, descriptor.source());
        compile(MODULE_PATH, join(layout.modules()), "--patch-module", ApplicationDescriptor.MODULE + "=" + layout.classes(),
                OUTPUT_DIRECTORY, layout.classes().toString(), source.toString());
    }

    /** Generates the bean accessors; when there are any, exports their packages to EL and provides them. */
    private static void generateBeanAccessors(Layout layout, ApplicationDescriptor descriptor, List<String> diagnostics)
            throws IOException {
        var accessors = BeanAccessors.generate(layout.classes(), layout.modules());
        addDiagnostics(diagnostics, accessors.unsupported());
        Files.write(layout.work().resolve(BEAN_ACCESSOR_ERRORS), diagnostics);
        if (accessors.providers().isEmpty()) {
            return;
        }
        accessors.beanPackages().forEach(beanPackage -> descriptor.exportsTo(beanPackage, EL_MODULE));
        descriptor.provides(ACCESSOR_PROVIDER, accessors.providers());
        compileDescriptor(layout, descriptor);
    }

    private static void precompilePages(Layout layout, List<JspPropertyGroup> groups) throws IOException {
        var result = new PagePrecompiler().precompileDeployment(layout.web(), layout.classes(), layout.modules(), null,
                new GenerationOptions(groups, Map.of()));
        var diagnostics = new ArrayList<String>();
        addDiagnostics(diagnostics, result.failures());
        Files.write(layout.work().resolve(TRANSLATION_ERRORS), diagnostics);
    }

    /**
     * Generates the collection accessors once the pages are compiled; when there are any, they join the
     * providers the compiled descriptor already has.
     */
    private static void generateCollectionAccessors(Layout layout, List<String> diagnostics) throws IOException {
        var collections = CollectionAccessors.generate(layout.classes(), layout.modules());
        addDiagnostics(diagnostics, collections.unsupported());
        Files.write(layout.work().resolve(BEAN_ACCESSOR_ERRORS), diagnostics);
        if (collections.providers().isEmpty()) {
            return;
        }
        var descriptor = ApplicationDescriptor.read(layout.classes().resolve(MODULE_INFO_CLASS))
                .provides(ACCESSOR_PROVIDER, collections.providers());
        compileDescriptor(layout, descriptor);
    }

    private static void addDiagnostics(List<String> diagnostics, Map<String, String> failures) {
        new TreeMap<>(failures).forEach((subject, failure) -> diagnostics.add(subject + "\n" + failure + "\n"));
    }

    /** Compiles the {@code tck.host} launcher module against the build-side module path. */
    private static Path buildHost(Layout layout) throws IOException {
        Path hostSource = Files.createDirectories(layout.work().resolve("host-source"));
        for (String file : HOST_SOURCES) {
            Path target = hostSource.resolve(file);
            Files.createDirectories(target.getParent());
            try (var input = PagesContainer.class.getResourceAsStream("host/" + file)) {
                if (input == null) {
                    throw new IOException("Missing host source: " + file);
                }
                Files.copy(input, target);
            }
        }
        Path host = Files.createDirectories(layout.work().resolve("host"));
        var arguments = new ArrayList<>(List.of(MODULE_PATH, join(layout.modules()), OUTPUT_DIRECTORY, host.toString()));
        HOST_SOURCES.forEach(file -> arguments.add(hostSource.resolve(file).toString()));
        compile(arguments.toArray(String[]::new));
        return host;
    }

    private Process launch(Layout layout, Path host, List<Path> applicationPath, String context) throws IOException {
        var runtime = new ArrayList<>(modulePaths(lookupPath, false));
        runtime.add(host);
        Path java = Path.of(System.getProperty("java.home"), "bin/java");
        var command = hostCommand(java, runtime, jdkModules, applicationPath, layout.web(), context);
        return new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(layout.work().resolve(SERVER_LOG).toFile())
                .start();
    }

    /**
     * The server command line: the runtime modules and the host on the module path, the boot layer limited
     * to the host and Chappe plus {@code jdkModules}, then the host arguments (application path, web root, context).
     */
    static List<String> hostCommand(Path java, List<Path> runtime, List<String> jdkModules, List<Path> applicationPath,
            Path web, String context) {
        return List.of(java.toString(), "-Dpeano.reflection=deny", MODULE_PATH, join(runtime),
                "--limit-modules", moduleList(List.of(HOST_MODULE, CHAPPE_CORE), jdkModules),
                "--add-modules", moduleList(List.of(CHAPPE_CORE), jdkModules),
                "-m", HOST_MAIN, join(applicationPath), web.toString(), context);
    }

    private static String moduleList(List<String> modules, List<String> jdkModules) {
        var names = new ArrayList<>(modules);
        names.addAll(jdkModules);
        return String.join(",", names);
    }

    /** Waits for the {@code READY:<port>} line of the server log. */
    static int awaitReady(Process process, Path log) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STARTUP_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline && process.isAlive()) {
            String output = Files.readString(log);
            int start = output.indexOf(READY);
            int end = start < 0 ? -1 : output.indexOf('\n', start);
            if (end >= 0) {
                return Integer.parseInt(output.substring(start + READY.length(), end).trim());
            }
            Thread.sleep(STARTUP_POLL_MILLIS);
        }
        throw new IOException("Server did not start: " + Files.readString(log));
    }

    /** Stops a failed deployment's server; a server that survives stays registered so that {@link #stop()} retries. */
    DeploymentException abandon(String name, Process process, Path work, Exception failure) {
        if (process != null) {
            try {
                terminate(process);
            } catch (LifecycleException cleanup) {
                deployments.put(name, new Running(process, work));
                failure.addSuppressed(cleanup);
            }
        }
        return new DeploymentException("Cannot precompile/deploy " + name + " (diagnostics: " + work + ")", failure);
    }

    private static void compile(String... arguments) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IOException("TCK deployment requires a Java 25 JDK on the build machine");
        }
        var diagnostics = new ByteArrayOutputStream();
        if (compiler.run(null, diagnostics, diagnostics, arguments) != 0) {
            throw new IOException("Build-side Java compilation failed: " + diagnostics);
        }
    }

    private static String join(List<Path> paths) {
        return String.join(File.pathSeparator, paths.stream().map(Path::toString).toList());
    }

    /** The host modules of the test class path; {@code compiler} adds the Ibarra compiler. */
    static List<Path> modulePaths(boolean compiler) throws IOException {
        return modulePaths(searchPath(testClassPath()), compiler);
    }

    private static List<Path> modulePaths(List<Path> searchPath, boolean compiler) throws IOException {
        var paths = new ArrayList<Path>();
        for (Path item : searchPath) {
            if (isModular(item)) {
                paths.add(item);
            }
        }
        return ModuleFinder.of(paths.toArray(Path[]::new)).findAll().stream()
                .filter(module -> isRuntimeModule(module.descriptor().name(), compiler))
                .map(module -> Path.of(module.location().orElseThrow()))
                .toList();
    }

    private static boolean isModular(Path path) throws IOException {
        if (Files.isRegularFile(path) && path.toString().endsWith(".jar")) {
            try (var jar = new JarFile(path.toFile())) {
                return jar.getJarEntry(MODULE_INFO_CLASS) != null
                        || jar.stream().anyMatch(entry -> VERSIONED_DESCRIPTOR.matcher(entry.getName()).matches());
            }
        }
        return Files.isRegularFile(path.resolve(MODULE_INFO_CLASS));
    }

    /** Whether {@code name} is a module the server boot layer or the build side already has. */
    static boolean isHostModule(String name) {
        return isRuntimeModule(name, true);
    }

    private static boolean isRuntimeModule(String name, boolean compiler) {
        return RUNTIME_MODULES.contains(name) || name.startsWith(CHAPPE_PREFIX) || (compiler && name.equals(COMPILER_MODULE));
    }

    @Override
    public void undeploy(Archive<?> archive) throws DeploymentException {
        String name = archive.getName();
        Running running = deployments.get(name);
        if (running == null) {
            return;
        }
        Process process = running.process();
        try {
            process.getOutputStream().write(1);
            process.getOutputStream().flush();
            if (!process.waitFor(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IOException("Server shutdown timed out");
            }
            if (process.exitValue() != 0) {
                throw new IOException("Server failed: " + Files.readString(running.work().resolve(SERVER_LOG)));
            }
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            release(name, process, failure);
            throw new DeploymentException("Cannot undeploy " + name, failure);
        }
        deployments.remove(name);
    }

    /** Forces a server down after a failed shutdown; it stays registered only if it survives. */
    void release(String name, Process process, Exception failure) {
        try {
            terminate(process);
            deployments.remove(name);
        } catch (LifecycleException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    @Override
    public void stop() throws LifecycleException {
        LifecycleException failure = null;
        var iterator = deployments.values().iterator();
        while (iterator.hasNext()) {
            try {
                terminate(iterator.next().process());
                iterator.remove();
            } catch (LifecycleException cleanup) {
                if (failure == null) {
                    failure = cleanup;
                } else {
                    failure.addSuppressed(cleanup);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** Kills a server process; the caller's interrupt status is kept across the wait. */
    static void terminate(Process process) throws LifecycleException {
        boolean interrupted = Thread.interrupted();
        try {
            closeInput(process);
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            awaitExit(process);
            if (process.isAlive()) {
                throw new LifecycleException("Cannot terminate server process " + process.pid());
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static void closeInput(Process process) {
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // The server may already have closed its end of the pipe: it is being killed anyway.
        }
    }

    /** Waits for a killed process to exit; an interrupt stops the wait and is kept for the caller. */
    static void awaitExit(Process process) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TERMINATION_TIMEOUT_SECONDS);
        while (process.isAlive() && System.nanoTime() < deadline) {
            try {
                process.waitFor(TERMINATION_POLL_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public void deploy(Descriptor descriptor) {
        throw new UnsupportedOperationException(DESCRIPTOR_DEPLOYMENT_UNSUPPORTED);
    }

    @Override
    public void undeploy(Descriptor descriptor) {
        throw new UnsupportedOperationException(DESCRIPTOR_DEPLOYMENT_UNSUPPORTED);
    }
}
