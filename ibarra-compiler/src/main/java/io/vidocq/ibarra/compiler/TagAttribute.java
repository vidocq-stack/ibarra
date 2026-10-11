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

/** Immutable, build-only attribute contract from a TLD. */
public record TagAttribute(String name, String type, boolean required, boolean runtimeExpression, boolean fragment,
        String deferredValueType, DeferredMethodSignature deferredMethod) {
    public TagAttribute(String name, String type, boolean required, boolean runtimeExpression, boolean fragment) {
        this(name, type, required, runtimeExpression, fragment, null, null);
    }
    public TagAttribute {
        Objects.requireNonNull(name); Objects.requireNonNull(type);
        if (deferredValueType != null && deferredMethod != null || fragment && (deferredValueType != null || deferredMethod != null))
            throw new IllegalArgumentException("Conflicting deferred attribute contracts");
    }
    /** Whether this attribute permits deferred expressions. */
    public boolean deferred() { return deferredValueType != null || deferredMethod != null; }
    /** Exact setter input, independent of the expected expression result type. */
    public String setterType() {
        if (deferred()) return runtimeExpression ? "java.lang.Object"
                : deferredMethod == null ? "jakarta.el.ValueExpression" : "jakarta.el.MethodExpression";
        return type;
    }
}
