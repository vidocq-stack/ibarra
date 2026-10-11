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

package io.vidocq.ibarra.compiler.internal;

import io.vidocq.ibarra.compiler.*;
import io.vidocq.ibarra.spi.PageDescriptor;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.module.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;
import java.util.jar.JarInputStream;
import org.w3c.dom.Element;

/** Build-only TLD discovery in web resources and the named application module graph. */
public final class TldListeners {
    private TldListeners() { }
    public static List<String> discover(Map<String, byte[]> web, List<Path> dependencies, Path output, ModuleDescriptor application) throws IOException {
        var libraries = new ArrayList<TagLibraryDescriptor>();
        var parser = new TldParser();
        configured(web, parser, libraries);
        for (var entry : web.entrySet()) {
            String name = entry.getKey();
            if (name.startsWith("/WEB-INF/") && !name.startsWith("/WEB-INF/classes/")
                    && !name.startsWith("/WEB-INF/lib/") && name.endsWith(".tld"))
                libraries.add(parser.parse(name, entry.getValue()));
        }
        Path applicationPath = output.toAbsolutePath().normalize();
        Path metadata = applicationPath.resolve("META-INF");
        if (Files.isDirectory(metadata)) try (var resources = Files.walk(metadata)) {
            for (Path file : resources.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".tld")).sorted().toList())
                libraries.add(parser.parse(application.name() + "!/" + applicationPath.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file)));
        }
        // Previous generated providers may be partly deleted; do not make ModuleFinder
        // validate that stale application descriptor before the precompiler repairs it.
        var finder = ModuleFinder.of(dependencies.stream().filter(path -> !path.toAbsolutePath().normalize().equals(applicationPath)).toArray(Path[]::new));
        var modules = new TreeMap<String, ModuleReference>();
        for (var reference : finder.findAll()) modules.put(reference.descriptor().name(), reference);
        var reachable = new TreeSet<String>();
        reachable(application, modules, reachable);
        for (String name : reachable) {
            var reference = modules.get(name);
            if (reference == null) continue;
            try (var reader = reference.open(); var resources = reader.list()) {
                for (String resource : resources.filter(path -> path.startsWith("META-INF/") && path.endsWith(".tld")).sorted().toList()) {
                    try (var input = reader.open(resource).orElseThrow()) {
                        libraries.add(parser.parse(name + "!/" + resource, input.readAllBytes()));
                    }
                }
            }
        }
        var paths = new ArrayList<Path>(); paths.add(applicationPath); paths.addAll(dependencies);
        var classes = new BeanClasses(paths);
        var listeners = new LinkedHashMap<String, String>();
        for (var library : libraries) for (String type : library.listenerClasses())
            if (!listeners.containsKey(type)) listeners.put(type, classes.listener(type, new SourceLocation(library.resourcePath(), 1, 1)));
        return List.copyOf(listeners.values());
    }
    private static void configured(Map<String, byte[]> web, TldParser parser, List<TagLibraryDescriptor> libraries) throws IOException {
        byte[] xml = web.get("/WEB-INF/web.xml");
        if (xml == null) return;
        var at = new SourceLocation("/WEB-INF/web.xml", 1, 1);
        var root = DeploymentXml.read("/WEB-INF/web.xml", xml).getDocumentElement();
        String namespace = Objects.toString(root.getNamespaceURI(), "");
        if (!root.getLocalName().equals("web-app") || !Set.of("", "https://jakarta.ee/xml/ns/jakartaee",
                "http://xmlns.jcp.org/xml/ns/javaee", "http://java.sun.com/xml/ns/javaee", "http://java.sun.com/xml/ns/j2ee").contains(namespace))
            throw new PageParseException(at, "Expected a standard web-app root");
        var uris = new HashSet<String>();
        for (var config : DeploymentXml.children(root)) if (config.getLocalName().equals("jsp-config")) {
            requireNamespace(config, namespace, at);
            for (var entry : DeploymentXml.children(config)) if (entry.getLocalName().equals("taglib")) {
                requireNamespace(entry, namespace, at);
                var fields = new HashMap<String, String>();
                for (var field : DeploymentXml.children(entry)) {
                    requireNamespace(field, namespace, at);
                    if (!Set.of("taglib-uri", "taglib-location").contains(field.getLocalName())
                            || fields.putIfAbsent(field.getLocalName(), field.getTextContent().trim()) != null)
                        throw new PageParseException(at, "Invalid or duplicate taglib mapping field");
                }
                String uri = fields.get("taglib-uri"), location = fields.get("taglib-location");
                if (uri == null || uri.isEmpty() || !uris.add(uri) || location == null || location.isEmpty())
                    throw new PageParseException(at, "Invalid or duplicate taglib mapping");
                location = TranslationUnit.resolve("/WEB-INF/web.xml", location);
                try { PageDescriptor.validatePath(location); }
                catch (IllegalArgumentException failure) { throw new PageParseException(at, "Invalid taglib location", failure); }
                byte[] resource = web.get(location);
                if (resource == null) throw new PageParseException(at, "Taglib location is absent: " + location);
                if (location.endsWith(".jar")) {
                    boolean found = false;
                    try (var jar = new JarInputStream(new ByteArrayInputStream(resource))) {
                        for (var item = jar.getNextJarEntry(); item != null; item = jar.getNextJarEntry()) {
                            if (item.getName().equals("META-INF/taglib.tld")) {
                                libraries.add(parser.parse(location + "!/META-INF/taglib.tld", jar.readAllBytes())); found = true; break;
                            }
                        }
                    }
                    if (!found) throw new PageParseException(at, "Taglib JAR has no META-INF/taglib.tld: " + location);
                } else libraries.add(parser.parse(location, resource));
            }
        }
    }
    private static void requireNamespace(Element element, String namespace, SourceLocation at) {
        if (!Objects.toString(element.getNamespaceURI(), "").equals(namespace))
            throw new PageParseException(at, "Foreign namespace in taglib mapping");
    }
    private static void reachable(ModuleDescriptor descriptor, Map<String, ModuleReference> modules, Set<String> visited) {
        if (!visited.add(descriptor.name())) return;
        for (var requirement : descriptor.requires()) {
            if (requirement.modifiers().contains(ModuleDescriptor.Requires.Modifier.STATIC)) continue;
            var reference = modules.get(requirement.name());
            if (reference != null) reachable(reference.descriptor(), modules, visited);
        }
    }
}
