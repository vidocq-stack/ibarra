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
package io.vidocq.ibarra.foy;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.lang.module.ModuleFinder;
import javax.tools.ToolProvider;
import io.vidocq.ibarra.compiler.*;
import org.jboss.arquillian.container.spi.client.container.*;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.*;
import org.jboss.shrinkwrap.api.*;
import org.jboss.shrinkwrap.api.importer.ZipImporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FoyHttpPagesTest {
    @TempDir Path directory;

    @Test
    void arquillianDeploymentServesCompiledPagesAliasesAndIncludesOverHttp() throws Exception {
        Path app = prepareApplication();
        var archive = ShrinkWrap.create(ZipImporter.class, "pages.jar").importFrom(app.toFile()).as(JavaArchive.class);
        var container = new PagesContainer();
        var configuration = new Configuration(); configuration.directory = directory; configuration.modulePath = modulePath(false);
        container.setup(configuration); container.start();
        boolean deployed = false;
        try {
            var metadata = container.deploy(archive);
            deployed = true;
            var http = metadata.getContext(HTTPContext.class);
            String base = "http://" + http.getHost() + ":" + http.getPort() + "/site";
            try (var client = HttpClient.newHttpClient()) {
                assertEquals("Hello A true jsp", get(client, base + "/index.jsp?who=A").body());
                assertEquals("Hello B true dynamic", get(client, base + "/dynamic?who=B").body());
                assertEquals("Hello C true named", get(client, base + "/named?who=C").body());
                assertEquals("beforepieceafter", get(client, base + "/include.jsp").body());
                assertEquals("XML", get(client, base + "/document.jspx").body());
                assertEquals(404, get(client, base + "/missing.jsp").statusCode());
                assertEquals(404, get(client, base + "/WEB-INF/secret.jsp").statusCode());
            }
        } finally { try { if (deployed) container.undeploy(archive); } finally { container.stop(); } }
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private Path prepareApplication() throws Exception {
        Path output = directory.resolve("application"); Path source = directory.resolve("source"); Path web = directory.resolve("web");
        Files.createDirectories(output); Files.createDirectories(source); Files.createDirectories(web.resolve("WEB-INF"));
        Path info = source.resolve("module-info.java");
        Files.writeString(info, "module integration.pages { requires io.vidocq.ibarra.api; }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "25", "--module-path",
                modulePath(true), "-d", output.toString(), info.toString()));
        Files.writeString(web.resolve("index.jsp"), "<%@ page session='false' %>Hello <%= request.getParameter(\"who\") %> <%= Thread.currentThread().isVirtual() %> <%= config.getServletName() %>");
        Files.writeString(web.resolve("piece.jsp"), "<%@ page session='false' %>piece");
        Files.writeString(web.resolve("document.jspx"), "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' version='4.0'><jsp:directive.page session='false'/><jsp:text>XML</jsp:text></jsp:root>");
        Files.writeString(web.resolve("include.jsp"), "<%@ page session='false' %>before<jsp:include page='/piece.jsp' flush='false'/>after");
        Files.writeString(web.resolve("WEB-INF/secret.jsp"), "<%@ page session='false' %>SECRET");
        new PagePrecompiler().precompile(web, output, paths(true), null, GenerationOptions.DEFAULT);
        Files.writeString(output.resolve("web.xml"), """
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.1">
                  <servlet><servlet-name>named</servlet-name><jsp-file>/index.jsp</jsp-file></servlet>
                  <servlet-mapping><servlet-name>named</servlet-name><url-pattern>/named</url-pattern></servlet-mapping>
                </web-app>
                """);
        Path jar = directory.resolve("application.jar");
        try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(jar)); var files = Files.walk(output)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new java.util.jar.JarEntry(output.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, out); out.closeEntry();
            }
        }
        prepareHost();
        return jar;
    }

    private void prepareHost() throws Exception {
        Path source = directory.resolve("host-source"); Path output = directory.resolve("host");
        Files.createDirectories(source.resolve("host")); Files.createDirectories(output);
        Files.writeString(source.resolve("module-info.java"), "module integration.host { requires io.vidocq.foy.core; requires io.vidocq.chappe.api; requires io.vidocq.ibarra.foy; requires io.vidocq.ibarra.api; provides io.vidocq.ibarra.spi.PageIndex with host.Main.ParentIndex; }");
        Files.writeString(source.resolve("host/Main.java"), """
                package host;
                import java.nio.file.*;
                import java.lang.module.*;
                import io.vidocq.foy.internal.boot.*;
                import io.vidocq.foy.internal.webxml.*;
                import io.vidocq.chappe.api.Server;
                public class Main {
                    public static final class ParentIndex implements io.vidocq.ibarra.spi.PageIndex {
                        public ParentIndex() { throw new AssertionError("Parent index must not be constructed for a child application"); }
                        public java.util.List<io.vidocq.ibarra.spi.PageDescriptor> pages() { return java.util.List.of(); }
                    }
                    public static void main(String[] args) throws Exception {
                        if (ModuleLayer.boot().findModule("java.compiler").isPresent()
                                || ModuleLayer.boot().findModule("io.vidocq.ibarra.compiler").isPresent()) throw new AssertionError("Compiler present at runtime");
                        var finder = ModuleFinder.of(Path.of(args[0]));
                        var configuration = ModuleLayer.boot().configuration().resolveAndBind(finder, ModuleFinder.of(), java.util.Set.of("integration.pages"));
                        var layer = ModuleLayer.defineModulesWithOneLoader(configuration, java.util.List.of(ModuleLayer.boot()), ClassLoader.getSystemClassLoader()).layer();
                        var loader = layer.findLoader("integration.pages");
                        var options = DeployOptions.defaults(loader);
                        var model = WebAppModel.builder("/site");
                        try (var zip = new java.util.jar.JarFile(args[0]); var input = zip.getInputStream(zip.getJarEntry("web.xml"))) {
                            DescriptorMerger.merge(WebXmlParser.parse(input), DescriptorMerger.AnnotatedComponents.none(), options.componentFactory(), model);
                        }
                        model.initializer((types, context) -> context.addJspFile("dynamic", "/index.jsp").addMapping("/dynamic"));
                        try (var deployment = WebAppDeployer.deploy(model.build(), options, layer);
                             var server = Server.builder().host("127.0.0.1").port(0).handler(deployment.handler()).build()) {
                            server.start();
                            System.out.println("READY:" + server.port()); System.out.flush();
                            System.in.read();
                            server.stop();
                        }
                    }
                }
                """);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "25", "--module-path",
                modulePath(true), "-d", output.toString(), source.resolve("module-info.java").toString(), source.resolve("host/Main.java").toString()));
    }

    private List<Path> paths(boolean compiler) { return Arrays.stream(modulePath(compiler).split(java.util.regex.Pattern.quote(File.pathSeparator))).map(Path::of).toList(); }
    private String modulePath(boolean compiler) {
        var names = new HashSet<>(Set.of("io.vidocq.ibarra.api", "io.vidocq.ibarra.core", "io.vidocq.ibarra.foy",
                "io.vidocq.foy.api", "io.vidocq.foy.core", "io.vidocq.chappe.api", "io.vidocq.chappe.core",
                "jakarta.servlet", "jakarta.servlet.jsp", "jakarta.el"));
        if (compiler) names.add("io.vidocq.ibarra.compiler");
        String available = System.getProperty("jdk.module.path", "") + File.pathSeparator + System.getProperty("surefire.test.class.path", "");
        var paths = Arrays.stream(available.split(java.util.regex.Pattern.quote(File.pathSeparator))).filter(s -> !s.isEmpty())
                .map(Path::of).filter(FoyHttpPagesTest::modular)
                .distinct().toArray(Path[]::new);
        return ModuleFinder.of(paths).findAll().stream().filter(ref -> names.contains(ref.descriptor().name())
                        || ref.descriptor().name().startsWith("io.vidocq.chappe."))
                .map(ref -> Path.of(ref.location().orElseThrow()).toString()).collect(java.util.stream.Collectors.joining(File.pathSeparator));
    }

    private static boolean modular(Path path) {
        if (Files.isRegularFile(path.resolve("module-info.class"))) return true;
        if (!path.toString().endsWith(".jar")) return false;
        try (var jar = new java.util.jar.JarFile(path.toFile())) {
            return jar.getJarEntry("module-info.class") != null || jar.stream().anyMatch(entry -> entry.getName().matches("META-INF/versions/[0-9]+/module-info.class"));
        } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }

    static class Configuration implements ContainerConfiguration {
        Path directory; String modulePath;
        public void validate() { Objects.requireNonNull(directory); Objects.requireNonNull(modulePath); }
    }
    /** Arquillian deployable-container adapter; application and server run only on the module path. */
    static class PagesContainer implements DeployableContainer<Configuration> {
        private Configuration configuration; private Process process;
        public Class<Configuration> getConfigurationClass() { return Configuration.class; }
        public void setup(Configuration configuration) { configuration.validate(); this.configuration = configuration; }
        public ProtocolDescription getDefaultProtocol() { return new ProtocolDescription("Servlet 3.0"); }
        public ProtocolMetaData deploy(Archive<?> archive) throws DeploymentException {
            try {
                Path jar = configuration.directory.resolve("deployed.jar"); archive.as(ZipExporter.class).exportTo(jar.toFile(), true);
                Path log = configuration.directory.resolve("server.log");
                process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                        "--module-path", configuration.modulePath + File.pathSeparator + configuration.directory.resolve("host"),
                        "--limit-modules", "integration.host,io.vidocq.chappe.core",
                        "--add-modules", "io.vidocq.chappe.core", "-m", "integration.host/host.Main", jar.toString())
                        .redirectErrorStream(true).redirectOutput(log.toFile()).start();
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
                while (System.nanoTime() < deadline && process.isAlive()) {
                    String output = Files.readString(log);
                    int start = output.indexOf("READY:");
                    if (start >= 0 && output.indexOf('\n', start) >= 0) {
                        int port = Integer.parseInt(output.substring(start + 6, output.indexOf('\n', start)).trim());
                        return new ProtocolMetaData().addContext(new HTTPContext("127.0.0.1", port));
                    }
                    Thread.sleep(20);
                }
                throw new DeploymentException("Server did not start: " + Files.readString(log));
            } catch (Exception failure) { if (failure instanceof DeploymentException deployment) throw deployment; throw new DeploymentException("Cannot deploy modular pages", failure); }
        }
        public void undeploy(Archive<?> archive) throws DeploymentException {
            if (process == null) return;
            try {
                process.getOutputStream().write(1); process.getOutputStream().flush();
                if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new DeploymentException("Server shutdown timed out"); }
                if (process.exitValue() != 0) throw new DeploymentException("Server failed: " + Files.readString(configuration.directory.resolve("server.log")));
            } catch (Exception failure) { if (failure instanceof DeploymentException deployment) throw deployment; throw new DeploymentException("Cannot undeploy pages", failure); }
        }
        public void stop() { if (process != null && process.isAlive()) process.destroyForcibly(); }
    }
}
