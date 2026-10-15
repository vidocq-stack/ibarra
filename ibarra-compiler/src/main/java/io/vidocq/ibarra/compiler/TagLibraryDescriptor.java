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

import java.util.*;

/** Immutable build-time TLD metadata; a missing URI still permits listener registration. */
public record TagLibraryDescriptor(String resourcePath, String uri, List<String> listenerClasses,
        Map<String, TagDescriptor> tags, TagLibraryValidatorDescriptor validator, Map<String, TldFunction> functions) {
    public TagLibraryDescriptor(String resourcePath, String uri, List<String> listenerClasses) {
        this(resourcePath, uri, listenerClasses, Map.of(), null, Map.of());
    }
    public TagLibraryDescriptor {
        Objects.requireNonNull(resourcePath); listenerClasses = List.copyOf(listenerClasses);
        tags = Collections.unmodifiableMap(new LinkedHashMap<>(tags));
        functions = Collections.unmodifiableMap(new LinkedHashMap<>(functions));
    }
    public boolean validationRequired() { return validator != null; }
}
