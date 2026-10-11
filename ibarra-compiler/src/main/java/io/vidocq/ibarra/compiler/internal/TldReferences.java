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
import java.io.*;
import java.util.*;
import java.util.jar.JarInputStream;

/** Direct build-time TLD references, separate from URI aliases. */
public final class TldReferences {
    public record Reference(String uri, SourceLocation location, boolean forced) {
        public Reference { Objects.requireNonNull(uri); Objects.requireNonNull(location); }
    }
    public record Resolved(Map<String, TagLibraryDescriptor> libraries, Map<String, TagLibraryDescriptor> resources) {
        public Resolved { libraries = Map.copyOf(libraries); resources = Map.copyOf(resources); }

        /** Standard declarations permit direct fallback; ordinary XML namespaces use aliases only. */
        public TagLibraryDescriptor resolve(String uri, SourceLocation at, boolean standard) {
            if (!standard && uri.startsWith("urn:jsptld:"))
                return resources.get(path(uri.substring(11), at));
            var library = libraries.get(uri);
            if (library != null || !standard || uri.contains(":")) return library;
            return resources.get(path(uri, at));
        }
    }
    private TldReferences() { }

    /** Reads only references captured before source normalization, from immutable bytes. */
    public static Resolved discover(Collection<TranslationUnit.Result> units, GenerationOptions options) {
        return discover(units, options, options.tagLibraries());
    }

    /** Supplied resource metadata stays distinct from aliases discovered in web.xml. */
    public static Resolved discover(Collection<TranslationUnit.Result> units, GenerationOptions options,
            Map<String, TagLibraryDescriptor> suppliedResources) {
        var resources = new LinkedHashMap<String, TagLibraryDescriptor>();
        for (var unit : units) for (var reference : unit.tagLibraries()) {
            String uri = reference.uri();
            if (!reference.forced() && (options.tagLibraries().containsKey(uri) || uri.contains(":"))) continue;
            String path = path(uri, reference.location());
            if (resources.containsKey(path)) continue;
            byte[] bytes = options.byteSource(path);
            if (bytes == null) {
                var supplied = suppliedResources.get(path);
                if (supplied != null && (!reference.forced() || supplied.resourcePath().equals(path))) {
                    resources.put(path, supplied); continue;
                }
                throw new PageParseException(reference.location(), "TLD resource is absent: " + path);
            }
            resources.put(path, read(path, bytes, reference.location()));
        }
        return new Resolved(options.tagLibraries(), resources);
    }

    private static String path(String uri, SourceLocation at) {
        if (uri.contains(":")) throw new PageParseException(at, "Forced TLD reference must be a web resource path: " + uri);
        try {
            String path = TranslationUnit.resolve(at.source(), uri);
            PageDescriptor.validatePath(path); return path;
        } catch (IllegalArgumentException invalid) { throw new PageParseException(at, "Invalid TLD resource path: " + uri, invalid); }
    }

    /** Explicit files need no .tld extension; JARs use their conventional descriptor. */
    static TagLibraryDescriptor read(String path, byte[] bytes, SourceLocation at) {
        var parser = new TldParser();
        if (!path.endsWith(".jar")) return parser.parse(path, bytes);
        try (var jar = new JarInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = jar.getNextJarEntry(); entry != null; entry = jar.getNextJarEntry())
                if (entry.getName().equals("META-INF/taglib.tld"))
                    return parser.parse(path + "!/META-INF/taglib.tld", jar.readAllBytes());
        } catch (IOException invalid) { throw new PageParseException(at, "Cannot read taglib JAR: " + path, invalid); }
        throw new PageParseException(at, "Taglib JAR has no META-INF/taglib.tld: " + path);
    }
}
