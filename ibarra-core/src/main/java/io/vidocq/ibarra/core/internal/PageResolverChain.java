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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import jakarta.el.CompositeELResolver;
import jakarta.el.ELContext;
import jakarta.el.ELResolver;

/**
 * The resolver chain of a page ELContext. ELContext.convertToType runs on every expression
 * evaluation, and CompositeELResolver asks each resolver of the chain in turn. Resolvers added
 * with addLookup keep ELResolver's default convertToType, which resolves nothing, so conversion
 * asks only the others, in chain order; the result is the same (Vidocq/peano#63).
 */
final class PageResolverChain extends CompositeELResolver {
    private ELResolver[] converters = new ELResolver[4];
    private int size;
    private final List<ELResolver> lookups = new ArrayList<>(16);

    /** Adds a resolver that conversion asks, as every resolver of a CompositeELResolver. */
    @Override
    public void add(ELResolver resolver) {
        super.add(resolver);
        if (size == converters.length) converters = Arrays.copyOf(converters, size * 2);
        converters[size++] = resolver;
    }

    /** Adds a resolver that keeps ELResolver's default convertToType; conversion skips it. */
    void addLookup(ELResolver resolver) {
        super.add(resolver);
        lookups.add(resolver);
    }

    List<ELResolver> lookups() { return List.copyOf(lookups); }

    @Override
    public <T> T convertToType(ELContext context, Object obj, Class<T> targetType) {
        context.setPropertyResolved(false);
        for (int i = 0; i < size; i++) {
            T value = converters[i].convertToType(context, obj, targetType);
            if (context.isPropertyResolved()) return value;
        }
        return null;
    }
}
