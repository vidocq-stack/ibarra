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

import java.lang.module.FindException;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jboss.arquillian.container.spi.ConfigurationException;

/**
 * Resolves the {@code applicationLibraries} names against the test class path. Each library joins the
 * application layer of every deployment; an explicit module is also read by the application and put on
 * the build-side module path, an automatic module (such as a JDBC driver) stays runtime-only.
 */
final class ApplicationLibraries {
    /** One library module: its name, its jar or directory, and whether it is an automatic module. */
    record Library(String name, Path location, boolean automatic) { }

    private ApplicationLibraries() { }

    /**
     * Finds every library on {@code searchPath}, in the configured order.
     *
     * @throws ConfigurationException when a name is a host module or is not on the search path
     */
    static List<Library> resolve(List<String> names, List<Path> searchPath) {
        for (String name : names) {
            if (PagesContainer.isHostModule(name)) {
                throw new ConfigurationException("applicationLibraries: " + name
                        + " is a host module, already in the server boot layer");
            }
        }
        var found = find(Set.copyOf(names), searchPath);
        for (String name : names) {
            if (!found.containsKey(name)) {
                throw new ConfigurationException("applicationLibraries: module " + name
                        + " not found on the test class path");
            }
        }
        return names.stream().map(found::get).toList();
    }

    /** Probes each entry alone, so that one unreadable entry does not hide the others; the first entry of a name wins. */
    private static Map<String, Library> find(Set<String> names, List<Path> searchPath) {
        var found = new HashMap<String, Library>();
        for (Path entry : searchPath) {
            if (found.size() == names.size()) {
                break;
            }
            for (ModuleReference module : modules(entry)) {
                String name = module.descriptor().name();
                if (names.contains(name)) {
                    found.putIfAbsent(name, new Library(name, Path.of(module.location().orElseThrow()),
                            module.descriptor().isAutomatic()));
                }
            }
        }
        return found;
    }

    private static Set<ModuleReference> modules(Path entry) {
        try {
            return ModuleFinder.of(entry).findAll();
        } catch (FindException unreadable) {
            return Set.of();
        }
    }
}
