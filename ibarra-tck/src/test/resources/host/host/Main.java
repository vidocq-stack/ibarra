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
package host;

import java.lang.module.ModuleFinder;
import java.nio.file.*;
import java.util.*;
import io.vidocq.foy.internal.boot.*;
import io.vidocq.foy.internal.webxml.WebXmlParser;
import io.vidocq.chappe.api.Server;
import io.vidocq.foy.internal.container.VidocqServletContext.ResourceProvider;
import java.io.*;
import java.net.URL;

/** Minimal Foy host for applications precompiled by the build-side TCK adapter. */
public final class Main {
    public static void main(String[] args) throws Exception {
        for (String forbidden : List.of("java.compiler", "io.vidocq.ibarra.compiler", "io.vidocq.peano.compiler"))
            if (ModuleLayer.boot().findModule(forbidden).isPresent()) throw new IllegalStateException("Build tooling present in runtime: " + forbidden);
        var finder = ModuleFinder.of(Path.of(args[0]));
        var configuration = ModuleLayer.boot().configuration().resolveAndBind(finder, ModuleFinder.of(), Set.of("tck.application"));
        var layer = ModuleLayer.defineModulesWithOneLoader(configuration, List.of(ModuleLayer.boot()), ClassLoader.getSystemClassLoader()).layer();
        var options = DeployOptions.defaults(layer.findLoader("tck.application"))
                .withResourceProvider(new Resources(Path.of(args[1])));
        var model = WebAppModel.builder(args[2]);
        Path descriptor = Path.of(args[1], "WEB-INF/web.xml");
        if (Files.exists(descriptor)) try (var input = Files.newInputStream(descriptor)) {
            DescriptorMerger.merge(WebXmlParser.parse(input), DescriptorMerger.AnnotatedComponents.none(), options.componentFactory(), model);
        }
        try (var deployment = WebAppDeployer.deploy(model.build(), options, layer);
             var server = Server.builder().host("127.0.0.1").port(0).handler(deployment.handler()).build()) {
            server.start();
            System.out.println("READY:" + server.port()); System.out.flush();
            System.in.read();
            server.stop();
        }
    }

    private record Resources(Path root) implements ResourceProvider {
        Resources { root = root.toAbsolutePath().normalize(); }
        private Path resolve(String path) {
            if (path == null || !path.startsWith("/") || path.indexOf('\\') >= 0) return null;
            Path file = root.resolve(path.substring(1)).normalize();
            return file.startsWith(root) ? file : null;
        }
        public InputStream openStream(String path) {
            Path file = resolve(path);
            if (file == null || !Files.isRegularFile(file)) return null;
            try { return Files.newInputStream(file); } catch (IOException failure) { throw new UncheckedIOException(failure); }
        }
        public URL toUrl(String path) {
            Path file = resolve(path);
            if (file == null || !Files.exists(file)) return null;
            try { return file.toUri().toURL(); } catch (java.net.MalformedURLException failure) { throw new IllegalArgumentException(failure); }
        }
        public Set<String> listPaths(String path) {
            Path directory = resolve(path);
            if (directory == null || !Files.isDirectory(directory)) return null;
            try (var files = Files.list(directory)) {
                var result = new TreeSet<String>();
                files.forEach(file -> result.add("/" + root.relativize(file).toString().replace(File.separatorChar, '/') + (Files.isDirectory(file) ? "/" : "")));
                return result.isEmpty() ? null : result;
            } catch (IOException failure) { throw new UncheckedIOException(failure); }
        }
        public Metadata metadata(String path) {
            Path file = resolve(path);
            if (file == null || !Files.isRegularFile(file)) return null;
            try { return new Metadata(Files.size(file), Files.getLastModifiedTime(file).toMillis()); }
            catch (IOException failure) { throw new UncheckedIOException(failure); }
        }
    }
}
