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
import java.util.List;
import java.util.Map;

/** Deployment defaults and decoded sources available for translation-time inclusion. */
public record GenerationOptions(List<JspPropertyGroup> propertyGroups, Map<String, String> sources, boolean allowStartupEl) {
    public static final GenerationOptions DEFAULT = new GenerationOptions(List.of(), Map.of());
    public GenerationOptions(List<JspPropertyGroup> propertyGroups, Map<String, String> sources) { this(propertyGroups, sources, false); }
    public GenerationOptions { propertyGroups = List.copyOf(propertyGroups); sources = Map.copyOf(sources); }
    /** Explicit preview fallback until Peano's build-time compiler is available. */
    public GenerationOptions withStartupEl(boolean allowed) { return new GenerationOptions(propertyGroups, sources, allowed); }
}

