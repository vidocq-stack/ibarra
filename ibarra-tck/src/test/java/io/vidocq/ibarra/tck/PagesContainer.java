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

import io.vidocq.ibarra.compiler.GenerationOptions;
import io.vidocq.ibarra.compiler.PagePrecompiler;
import io.vidocq.ibarra.compiler.JspConfigurationParser;
import io.vidocq.ibarra.compiler.JspPropertyGroup;
import java.io.*;
import java.lang.module.ModuleFinder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.Attributes;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import javax.tools.ToolProvider;
import io.vidocq.foy.internal.webxml.WebXmlParser;
import org.jboss.arquillian.container.spi.client.container.*;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.*;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.jboss.shrinkwrap.descriptor.api.Descriptor;

/** Build-side Arquillian adapter. The server process contains only named runtime modules. */
public final class PagesContainer implements DeployableContainer<PagesConfiguration> {
    private record Running(Process process, Path work) { }
    private final Map<String, Running> deployments = new LinkedHashMap<>();
    public Class<PagesConfiguration> getConfigurationClass() { return PagesConfiguration.class; }
    public void setup(PagesConfiguration configuration) { configuration.validate(); }
    public void start() { }
    public ProtocolDescription getDefaultProtocol() { return new ProtocolDescription("Servlet 3.0"); }

    public ProtocolMetaData deploy(Archive<?> archive) throws DeploymentException {
        if (!(archive instanceof WebArchive)) throw new IllegalArgumentException("Pages TCK requires a WebArchive");
        if (deployments.containsKey(archive.getName())) throw new DeploymentException("Archive is already deployed: " + archive.getName());
        Process process = null;
        Path work = null;
        try {
            Path base = Path.of(System.getProperty("ibarra.tck.work", "target/pages-deployments")).toAbsolutePath();
            Files.createDirectories(base);
            work = Files.createTempDirectory(base, "war-");
            Path web = Files.createDirectories(work.resolve("web"));
            Path classes = Files.createDirectories(work.resolve("application"));
            for (var entry : archive.getContent().entrySet()) {
                var asset = entry.getValue().getAsset();
                if (asset == null) continue;
                String name = entry.getKey().get().replaceFirst("^/", "");
                Path root = name.startsWith("WEB-INF/classes/") ? classes : web;
                String relative = root == classes ? name.substring("WEB-INF/classes/".length()) : name;
                Path file = root.resolve(relative).normalize();
                if (!file.startsWith(root)) throw new IOException("Unsafe archive path: " + name);
                Files.createDirectories(file.getParent());
                try (var input = asset.openStream()) { Files.copy(input, file, StandardCopyOption.REPLACE_EXISTING); }
            }
            WarLibraries.assemble(web, classes);
            if (Files.exists(classes.resolve("META-INF/services/jakarta.servlet.ServletContainerInitializer")))
                throw new IOException("WAR ServletContainerInitializer factories are not yet adapted by this runner");
            try (var files = Files.walk(classes)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                    var annotations = ClassFile.of().parse(Files.readAllBytes(file)).findAttribute(Attributes.runtimeVisibleAnnotations());
                    if (annotations.isPresent() && annotations.get().annotations().stream().anyMatch(a ->
                            Set.of("Ljakarta/servlet/annotation/WebServlet;", "Ljakarta/servlet/annotation/WebFilter;",
                                    "Ljakarta/servlet/annotation/WebListener;").contains(a.className().stringValue())))
                        throw new IOException("WAR Servlet annotations need build-generated factories: " + file);
                }
            }
            Path webXml = web.resolve("WEB-INF/web.xml");
            var groups = Files.exists(webXml) ? new JspConfigurationParser().parse("/WEB-INF/web.xml", Files.readAllBytes(webXml)) : List.<JspPropertyGroup>of();
            var components = new TreeSet<String>();
            if (Files.exists(webXml)) try (var input = Files.newInputStream(webXml)) {
                var model = WebXmlParser.parse(input);
                model.servlets().stream().map(s -> s.className()).filter(Objects::nonNull).forEach(components::add);
                model.filters().stream().map(f -> f.className()).forEach(components::add);
                components.addAll(model.listenerClasses());
            }
            var providers = ComponentFactories.generate(classes, components);
            List<Path> modules = modulePaths(true);
            Path descriptor = work.resolve("module-info.java");
            String service = providers.isEmpty() ? "" : "provides io.vidocq.foy.spi.gen.WebComponent with " + String.join(",", providers) + ";";
            Files.writeString(descriptor, "module tck.application { requires io.vidocq.ibarra.api; requires io.vidocq.peano.api; requires io.vidocq.foy.api; " + service + " }");
            compile("--module-path", join(modules), "--patch-module", "tck.application=" + classes,
                    "-d", classes.toString(), descriptor.toString());
            var beanAccessors = BeanAccessors.generate(classes, modules);
            var beanProviders = beanAccessors.providers();
            var beanDiagnostics = new ArrayList<String>();
            new TreeMap<>(beanAccessors.unsupported()).forEach((type, reason) -> beanDiagnostics.add(type+"\n"+reason+"\n"));
            Files.write(work.resolve("bean-accessor-errors.txt"),beanDiagnostics);
            if (!beanProviders.isEmpty()) {
                Files.writeString(descriptor, "module tck.application { requires io.vidocq.ibarra.api; requires io.vidocq.peano.api; requires io.vidocq.foy.api; " + service
                        + "provides io.vidocq.peano.spi.AccessorProvider with " + String.join(",", beanProviders) + "; }");
                compile("--module-path", join(modules), "--patch-module", "tck.application=" + classes,
                        "-d", classes.toString(), descriptor.toString());
            }
            var result = new PagePrecompiler().precompileDeployment(web, classes, modules, null, new GenerationOptions(groups, Map.of()));
            var diagnostics = new ArrayList<String>();
            new TreeMap<>(result.failures()).forEach((page, failure) -> diagnostics.add(page + "\n" + failure + "\n"));
            Files.write(work.resolve("translation-errors.txt"), diagnostics);
            Path hostSource = Files.createDirectories(work.resolve("host-source"));
            for (String file : List.of("module-info.java", "host/Main.java")) {
                Path target = hostSource.resolve(file); Files.createDirectories(target.getParent());
                try (var input = PagesContainer.class.getResourceAsStream("/host/" + file)) {
                    if (input == null) throw new IOException("Missing host source: " + file);
                    Files.copy(input, target);
                }
            }
            Path host = Files.createDirectories(work.resolve("host"));
            compile("--module-path", join(modules), "-d", host.toString(), hostSource.resolve("module-info.java").toString(), hostSource.resolve("host/Main.java").toString());
            Path log = work.resolve("server.log");
            String context = "/" + archive.getName().replaceFirst("\\.war$", "");
            var runtime = new ArrayList<>(modulePaths(false)); runtime.add(host);
            process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                    "-Dpeano.reflection=deny", "--module-path", join(runtime),
                    "--limit-modules", "tck.host,io.vidocq.chappe.core", "--add-modules", "io.vidocq.chappe.core",
                    "-m", "tck.host/host.Main", classes.toString(), web.toString(), context)
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < deadline && process.isAlive()) {
                String output = Files.readString(log);
                int start = output.indexOf("READY:");
                int end = start < 0 ? -1 : output.indexOf('\n', start);
                if (end >= 0) {
                    int port = Integer.parseInt(output.substring(start + 6, end).trim());
                    deployments.put(archive.getName(), new Running(process, work));
                    return new ProtocolMetaData().addContext(new HTTPContext("127.0.0.1", port).add(new Servlet("Pages", context)));
                }
                Thread.sleep(20);
            }
            throw new IOException("Server did not start: " + Files.readString(log));
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            if (process != null) try { terminate(process); } catch (LifecycleException cleanup) {
                deployments.put(archive.getName(), new Running(process, work)); failure.addSuppressed(cleanup);
            }
            throw new DeploymentException("Cannot precompile/deploy " + archive.getName() + " (diagnostics: " + work + ")", failure);
        }
    }

    private static void compile(String... arguments) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IOException("TCK deployment requires a Java 25 JDK on the build machine");
        var diagnostics = new ByteArrayOutputStream();
        if (compiler.run(null, diagnostics, diagnostics, arguments) != 0)
            throw new IOException("Build-side Java compilation failed: " + diagnostics);
    }

    private static String join(List<Path> paths) { return String.join(File.pathSeparator, paths.stream().map(Path::toString).toList()); }

    static List<Path> modulePaths(boolean compiler) throws IOException {
        String available = System.getProperty("jdk.module.path", "") + File.pathSeparator + System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var paths = new ArrayList<Path>();
        for (String item : available.split(Pattern.quote(File.pathSeparator))) {
            if (item.isEmpty()) continue;
            Path path = Path.of(item);
            boolean modular = Files.isRegularFile(path.resolve("module-info.class"));
            if (Files.isRegularFile(path) && item.endsWith(".jar")) {
                try (var jar = new JarFile(path.toFile())) {
                    modular = jar.getJarEntry("module-info.class") != null || jar.stream().anyMatch(e -> e.getName().matches("META-INF/versions/[0-9]+/module-info.class"));
                }
            }
            if (modular) paths.add(path);
        }
        Set<String> names = Set.of("io.vidocq.ibarra.api", "io.vidocq.ibarra.core", "io.vidocq.ibarra.foy", "io.vidocq.foy.api", "io.vidocq.foy.core",
                "io.vidocq.peano.api", "io.vidocq.peano.core", "jakarta.servlet", "jakarta.servlet.jsp", "jakarta.el");
        return ModuleFinder.of(paths.toArray(Path[]::new)).findAll().stream()
                .filter(r -> names.contains(r.descriptor().name()) || r.descriptor().name().startsWith("io.vidocq.chappe.")
                        || compiler && r.descriptor().name().equals("io.vidocq.ibarra.compiler"))
                .map(r -> Path.of(r.location().orElseThrow())).toList();
    }

    public void undeploy(Archive<?> archive) throws DeploymentException {
        Running running = deployments.get(archive.getName());
        if (running == null) return;
        Process process = running.process();
        try {
            process.getOutputStream().write(1); process.getOutputStream().flush();
            if (!process.waitFor(15, TimeUnit.SECONDS)) throw new IOException("Server shutdown timed out");
            if (process.exitValue() != 0) throw new IOException("Server failed: " + Files.readString(running.work().resolve("server.log")));
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            try { terminate(process); deployments.remove(archive.getName()); } catch (LifecycleException cleanup) { failure.addSuppressed(cleanup); }
            throw new DeploymentException("Cannot undeploy " + archive.getName(), failure);
        }
        deployments.remove(archive.getName());
    }
    public void stop() throws LifecycleException {
        LifecycleException failure = null;
        var iterator = deployments.values().iterator();
        while (iterator.hasNext()) {
            try { terminate(iterator.next().process()); iterator.remove(); }
            catch (LifecycleException cleanup) { if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup); }
        }
        if (failure != null) throw failure;
    }
    private static void terminate(Process process) throws LifecycleException {
        boolean interrupted = Thread.interrupted();
        try {
            try { process.getOutputStream().close(); } catch (IOException ignored) { }
            if (process.isAlive()) process.destroyForcibly();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (process.isAlive() && System.nanoTime() < deadline) {
                try { process.waitFor(100, TimeUnit.MILLISECONDS); }
                catch (InterruptedException failure) { interrupted = true; }
            }
            if (process.isAlive()) throw new LifecycleException("Cannot terminate server process " + process.pid());
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    public void deploy(Descriptor descriptor) { throw new UnsupportedOperationException("Descriptor-only deployment is unsupported"); }
    public void undeploy(Descriptor descriptor) { throw new UnsupportedOperationException("Descriptor-only deployment is unsupported"); }
}
