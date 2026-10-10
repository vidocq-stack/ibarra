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
package io.vidocq.ibarra.core.internal;

import jakarta.el.*;

/** Final resolver for unresolved reads; resolved null values never reach it. */
final class NotFoundELResolver extends ELResolver {
    private final boolean strict;
    NotFoundELResolver(boolean strict) { this.strict = strict; }

    @Override
    public Object getValue(ELContext context, Object base, Object property) {
        context.setPropertyResolved(true);
        if (strict) throw new PropertyNotFoundException("Unresolved EL identifier or property: " + property);
        return null;
    }

    @Override public Class<?> getType(ELContext context, Object base, Object property) { return null; }
    @Override public void setValue(ELContext context, Object base, Object property, Object value) { }
    @Override public boolean isReadOnly(ELContext context, Object base, Object property) { return false; }
    @Override public Class<?> getCommonPropertyType(ELContext context, Object base) { return Object.class; }
    @Override
    public Object invoke(ELContext context, Object base, Object method, Class<?>[] parameterTypes, Object[] parameters) {
        throw new MethodNotFoundException("Unresolved EL method: " + method);
    }
}

