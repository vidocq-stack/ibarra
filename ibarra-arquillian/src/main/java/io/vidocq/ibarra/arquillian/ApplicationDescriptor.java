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

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Source model of the {@code tck.application} module descriptor that the adapter compiles over
 * the WAR classes. Requires, exports, opens and services are written sorted by name, so the same
 * model always gives the same source; the providers of one service keep their insertion order.
 */
final class ApplicationDescriptor {
    /** Name of the application module every deployment compiles. */
    static final String MODULE = "tck.application";

    private static final List<String> RUNTIME_MODULES =
            List.of("java.desktop", "jakarta.el", "io.vidocq.ibarra.api", "io.vidocq.peano.api", "io.vidocq.foy.api");

    /** Module name to its {@code static }/{@code transitive } prefix (empty for a plain read). */
    private final Map<String, String> reads = new TreeMap<>();
    /** Package to its sorted targets; an empty set is an unqualified export. */
    private final Map<String, SortedSet<String>> exported = new TreeMap<>();
    /** Package to its sorted targets; an empty set is an unqualified opening. */
    private final Map<String, SortedSet<String>> opened = new TreeMap<>();
    /** Service to its providers, in insertion order. */
    private final Map<String, List<String>> services = new TreeMap<>();

    private ApplicationDescriptor() { }

    /** The descriptor every application starts from: it reads the runtime APIs the generated code links against. */
    static ApplicationDescriptor base() {
        var descriptor = new ApplicationDescriptor();
        RUNTIME_MODULES.forEach(descriptor::requires);
        return descriptor;
    }

    /** Reads back a compiled descriptor, without the mandated {@code java.base} read. */
    static ApplicationDescriptor read(Path moduleInfoClass) throws IOException {
        ModuleDescriptor compiled;
        try (var input = Files.newInputStream(moduleInfoClass)) {
            compiled = ModuleDescriptor.read(input);
        }
        var descriptor = new ApplicationDescriptor();
        for (var read : compiled.requires()) {
            Set<ModuleDescriptor.Requires.Modifier> modifiers = read.modifiers();
            if (!modifiers.contains(ModuleDescriptor.Requires.Modifier.MANDATED)) {
                descriptor.reads.put(read.name(), prefix(modifiers));
            }
        }
        compiled.exports().forEach(export -> descriptor.exported.put(export.source(), new TreeSet<>(export.targets())));
        compiled.opens().forEach(opening -> descriptor.opened.put(opening.source(), new TreeSet<>(opening.targets())));
        compiled.provides().forEach(service -> descriptor.provides(service.service(), service.providers()));
        return descriptor;
    }

    private static String prefix(Set<ModuleDescriptor.Requires.Modifier> modifiers) {
        String prefix = modifiers.contains(ModuleDescriptor.Requires.Modifier.STATIC) ? "static " : "";
        return modifiers.contains(ModuleDescriptor.Requires.Modifier.TRANSITIVE) ? prefix + "transitive " : prefix;
    }

    /** Adds a plain read of {@code module}; an existing read keeps its modifiers. */
    ApplicationDescriptor requires(String module) {
        reads.putIfAbsent(module, "");
        return this;
    }

    /** Exports {@code packageName} to {@code target}; an unqualified export stays unqualified. */
    ApplicationDescriptor exportsTo(String packageName, String target) {
        qualify(exported, packageName, target);
        return this;
    }

    /** Opens {@code packageName} to every module. */
    ApplicationDescriptor opens(String packageName) {
        opened.put(packageName, new TreeSet<>());
        return this;
    }

    /** Appends {@code providers} after the providers {@code service} already has; no providers adds nothing. */
    ApplicationDescriptor provides(String service, List<String> providers) {
        if (!providers.isEmpty()) {
            services.computeIfAbsent(service, ignored -> new ArrayList<>()).addAll(providers);
        }
        return this;
    }

    /** The {@code module-info.java} source of this descriptor. */
    String source() {
        var text = new StringBuilder("module ").append(MODULE).append(" { ");
        reads.forEach((module, prefix) -> text.append("requires ").append(prefix).append(module).append("; "));
        exported.forEach((packageName, targets) -> statement(text, "exports", packageName, targets));
        opened.forEach((packageName, targets) -> statement(text, "opens", packageName, targets));
        services.forEach((service, providers) ->
                text.append("provides ").append(service).append(" with ").append(String.join(",", providers)).append("; "));
        return text.append('}').toString();
    }

    private static void qualify(Map<String, SortedSet<String>> statements, String packageName, String target) {
        var targets = statements.get(packageName);
        if (targets == null) {
            statements.put(packageName, new TreeSet<>(Set.of(target)));
        } else if (!targets.isEmpty()) {
            targets.add(target);
        }
    }

    private static void statement(StringBuilder text, String keyword, String packageName, Set<String> targets) {
        text.append(keyword).append(' ').append(packageName);
        if (!targets.isEmpty()) {
            text.append(" to ").append(String.join(",", targets));
        }
        text.append("; ");
    }
}
