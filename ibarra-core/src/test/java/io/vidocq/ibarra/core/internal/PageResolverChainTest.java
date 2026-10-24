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

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import jakarta.el.ELContext;
import jakarta.el.ELResolver;
import jakarta.el.ExpressionFactory;
import org.junit.jupiter.api.Test;

class PageResolverChainTest {
    @Test void conversionAsksTheConvertingResolversInOrderAndStopsAtTheFirstResolution() throws Exception {
        var calls = new ArrayList<String>();
        var chain = new PageResolverChain();
        chain.add(new Converter("first", null, calls));
        chain.add(new Converter("second", "converted", calls));
        chain.add(new Converter("third", "late", calls));
        var context = new PageRuntimeTest.Web().open(false, null).getELContext();
        context.setPropertyResolved(true);
        assertEquals("converted", chain.convertToType(context, "value", Object.class));
        assertTrue(context.isPropertyResolved());
        assertEquals(List.of("first", "second"), calls);
    }

    @Test void conversionReachesEveryConvertingResolver() throws Exception {
        var calls = new ArrayList<String>();
        var chain = new PageResolverChain();
        for (int i = 1; i < 9; i++) chain.add(new Converter("c" + i, null, calls));
        chain.add(new Converter("last", "converted", calls));
        var context = new PageRuntimeTest.Web().open(false, null).getELContext();
        assertEquals("converted", chain.convertToType(context, "value", Object.class));
        assertEquals(List.of("c1", "c2", "c3", "c4", "c5", "c6", "c7", "c8", "last"), calls);
    }

    @Test void conversionSkipsLookupResolvers() throws Exception {
        var calls = new ArrayList<String>();
        var chain = new PageResolverChain();
        chain.addLookup(new Converter("lookup", "wrong", calls));
        chain.add(new Converter("converter", null, calls));
        var context = new PageRuntimeTest.Web().open(false, null).getELContext();
        assertNull(chain.convertToType(context, "value", Object.class));
        assertFalse(context.isPropertyResolved());
        assertEquals(List.of("converter"), calls);
    }

    @Test void lookupResolversStillResolveValues() throws Exception {
        var chain = new PageResolverChain();
        chain.addLookup(new Converter("lookup", null, new ArrayList<>()));
        var context = new PageRuntimeTest.Web().open(false, null).getELContext();
        assertEquals("lookup", chain.getValue(context, null, "lookup"));
        assertTrue(context.isPropertyResolved());
    }

    @Test void everyLookupResolverOfAPageKeepsTheDefaultConversion() throws Exception {
        var context = new PageRuntimeTest.Web().open(false, null).getELContext();
        var chain = assertInstanceOf(PageResolverChain.class, context.getELResolver());
        assertFalse(chain.lookups().isEmpty());
        for (ELResolver resolver : chain.lookups())
            assertEquals(ELResolver.class, resolver.getClass()
                    .getMethod("convertToType", ELContext.class, Object.class, Class.class).getDeclaringClass(),
                    resolver.getClass().getName());
    }

    @Test void pagesStillUnwrapOptionals() throws Exception {
        var context = new PageRuntimeTest.Web().open(false, null).getELContext();
        assertEquals("present", context.convertToType(Optional.of("present"), Object.class));
    }

    @Test void pagesStillApplyTheConversionsOfRegisteredResolvers() throws Exception {
        var application = new IbarraApplicationContext(ExpressionFactory.newInstance());
        application.addELResolver(new Converter("registered", "custom", new ArrayList<>()));
        var web = new PageRuntimeTest.Web();
        var page = new IbarraPageContext(application);
        page.initialize(web.page, web.request, web.response, null, false, 64, true);
        var context = page.getELContext();
        assertEquals("custom", context.convertToType("value", Object.class));
    }

    /** Resolves its own name as a top-level identifier and converts every value to result, when not null. */
    private static final class Converter extends ELResolver {
        private final String name;
        private final Object result;
        private final List<String> calls;

        Converter(String name, Object result, List<String> calls) {
            this.name = name;
            this.result = result;
            this.calls = calls;
        }

        @Override public Object getValue(ELContext context, Object base, Object property) {
            if (base != null || !name.equals(property)) return null;
            context.setPropertyResolved(true);
            return name;
        }
        @Override public Class<?> getType(ELContext context, Object base, Object property) { return null; }
        @Override public void setValue(ELContext context, Object base, Object property, Object value) { }
        @Override public boolean isReadOnly(ELContext context, Object base, Object property) { return true; }
        @Override public Class<?> getCommonPropertyType(ELContext context, Object base) { return null; }
        @Override @SuppressWarnings("unchecked") public <T> T convertToType(ELContext context, Object value, Class<T> type) {
            calls.add(name);
            if (result == null) return null;
            context.setPropertyResolved(true);
            return (T) result;
        }
    }
}
