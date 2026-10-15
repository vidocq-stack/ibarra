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

import java.util.Objects;

/** Page variable metadata declared by a tag library descriptor. */
public record TagVariableDescriptor(String name, String nameFromAttribute, String className, boolean declare, int scope) {
    public static final int NESTED = 0;
    public static final int AT_BEGIN = 1;
    public static final int AT_END = 2;

    public TagVariableDescriptor(String name, String className, boolean declare, int scope) {
        this(name, null, className, declare, scope);
    }

    public TagVariableDescriptor {
        if ((name == null) == (nameFromAttribute == null))
            throw new IllegalArgumentException("Exactly one tag variable name source is required");
        Objects.requireNonNull(className);
        if (scope < NESTED || scope > AT_END) throw new IllegalArgumentException("Invalid tag variable scope: " + scope);
    }
}
