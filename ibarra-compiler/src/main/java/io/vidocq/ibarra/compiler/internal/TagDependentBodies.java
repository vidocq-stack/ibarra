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
import java.util.*;
import java.util.function.Function;

/** Translation-time body contracts, available before interpreting page contents. */
public final class TagDependentBodies {
    private final GenerationOptions options;
    private final Function<String, SourceDocument> sources;
    private final Function<SourceDocument, Page.Syntax> syntax;
    private final Set<String> includes = new HashSet<>();
    private final Map<String, TagLibraryDescriptor> resources = new HashMap<>();
    private final Map<String, String> bindings = new LinkedHashMap<>();
    private final Map<String, SourceLocation> origins = new LinkedHashMap<>();

    public TagDependentBodies(GenerationOptions options, Function<String, SourceDocument> sources,
            Function<SourceDocument, Page.Syntax> syntax) {
        this.options = options; this.sources = sources; this.syntax = syntax;
    }

    Map<String, String> bindings() { return bindings; }
    Map<String, SourceLocation> origins() { return origins; }
    void resetBindings() { bindings.clear(); origins.clear(); }
    void remember(Map<String, String> values, Map<String, SourceLocation> locations) {
        bindings.putAll(values); origins.putAll(locations);
    }

    boolean raw(String name, String uri, SourceLocation origin, boolean standard) {
        TagLibraryDescriptor library;
        if (!standard && uri.startsWith("urn:jsptld:")) library = resource(uri.substring(11), origin);
        else {
            library = options.tagLibraries().get(uri);
            if (library == null && standard && !uri.contains(":")) library = resource(uri, origin);
        }
        if (library == null) return false;
        var tag = library.tags().get(name.substring(name.lastIndexOf(':') + 1));
        return tag != null && tag.bodyContent().equals("tagdependent");
    }

    private TagLibraryDescriptor resource(String relative, SourceLocation origin) {
        if (relative.contains(":")) throw new PageParseException(origin, "Forced TLD reference must be a web resource path: " + relative);
        String path = TranslationUnit.resolve(origin.source(), relative);
        if (resources.containsKey(path)) return resources.get(path);
        byte[] bytes = options.byteSource(path);
        if (bytes != null) {
            var library = TldReferences.read(path, bytes, origin); resources.put(path, library); return library;
        }
        return options.tagLibraries().get(path);
    }

    void includeBindings(PageNode.Directive directive, Map<String, String> bindings,
            Map<String, SourceLocation> origins, ParserOptions parserOptions) {
        String file = directive.attributes().get("file");
        if (file == null) return;
        String path = TranslationUnit.resolve(directive.location().source(), file);
        if (!includes.add(path)) return; // The normal include expansion reports cycles.
        try {
            var document = sources.apply(path);
            if (document != null && syntax.apply(document) == Page.Syntax.STANDARD)
                new StandardParser(document, parserOptions, bindings, false, this, origins).scanBindings(bindings, origins);
        } finally { includes.remove(path); }
    }
}
