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
import jakarta.el.ExpressionFactory;
import org.junit.jupiter.api.Test;

class PageElIntegrationTest {
    @Test void handwrittenPagesRetainTheStandardDefaultImports() throws Exception {
        var page = new PageRuntimeTest.Web().open(false, null);
        assertEquals(jakarta.servlet.http.Cookie.class, page.getELContext().getImportHandler().resolveClass("Cookie"));
        assertEquals(jakarta.servlet.jsp.ErrorData.class, page.getELContext().getImportHandler().resolveClass("ErrorData"));
    }
    @Test void providerResolversParticipateBeforeBeanFallback() throws Exception {
        var delegate = ExpressionFactory.newInstance();
        var web = new PageRuntimeTest.Web();
        var observed = new java.util.concurrent.atomic.AtomicReference<ClassLoader>();
        var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        var resolver = new jakarta.el.ELResolver() {
            public Object getValue(jakarta.el.ELContext context, Object base, Object property) {
                if (base == null && "provider".equals(property)) { context.setPropertyResolved(true); return "direct"; }
                return null;
            }
            public Class<?> getType(jakarta.el.ELContext context, Object base, Object property) { return null; }
            public void setValue(jakarta.el.ELContext context, Object base, Object property, Object value) { /* Read-only resolver. */ }
            public boolean isReadOnly(jakarta.el.ELContext context, Object base, Object property) { return true; }
            public Class<?> getCommonPropertyType(jakarta.el.ELContext context, Object base) { return null; }
        };
        var factory = new ExpressionFactory() {
            @Override public jakarta.el.ELResolver getStreamELResolver() {
                observed.set(Thread.currentThread().getContextClassLoader());
                if (fail.getAndSet(false)) throw new jakarta.el.ELException("provider failed");
                return resolver;
            }
            public jakarta.el.ValueExpression createValueExpression(jakarta.el.ELContext context, String text, Class<?> type) { return delegate.createValueExpression(context, text, type); }
            public jakarta.el.ValueExpression createValueExpression(Object value, Class<?> type) { return delegate.createValueExpression(value, type); }
            public jakarta.el.MethodExpression createMethodExpression(jakarta.el.ELContext context, String text, Class<?> type, Class<?>[] parameters) { return delegate.createMethodExpression(context, text, type, parameters); }
            public <T> T coerceToType(Object value, Class<T> type) { return delegate.coerceToType(value, type); }
        };
        var page = new IbarraPageContext(new IbarraApplicationContext(factory));
        page.initialize(web.page, web.request, web.response, null, false, 64, true);
        var thread = Thread.currentThread(); var previous = thread.getContextClassLoader();
        var caller = new ClassLoader(previous) {};
        try {
            thread.setContextClassLoader(caller);
            assertThrows(jakarta.el.ELException.class, page::getELContext);
            assertSame(web.page.getClass().getClassLoader(), observed.get());
            assertSame(caller, thread.getContextClassLoader());
            var context = page.getELContext();
            assertEquals("direct", context.getELResolver().getValue(context, null, "provider"));
            assertSame(web.page.getClass().getClassLoader(), observed.get());
            assertSame(caller, thread.getContextClassLoader());
        } finally { thread.setContextClassLoader(previous); }
    }
    @Test void pagesRetainTheProvidersCollectionResolver() throws Exception {
        var page = new PageRuntimeTest.Web().open(false, null);
        var context = page.getELContext();
        assertEquals((Object) 4L, ExpressionFactory.newInstance().createValueExpression(context,
                "${[1,2,3].stream().map(x->x*2).toList()[1]}", Object.class).getValue(context));
    }
    @Test
    void peanoResolvesScopeAndParameterImplicitObjects() throws Exception {
        var web = new PageRuntimeTest.Web();
        web.request.setAttribute("user", java.util.Map.of("name", "Ada"));
        web.request.parameters.put("q", new String[]{"first", "second"});
        var page = web.open(false, null);
        var factory = ExpressionFactory.newInstance();
        assertEquals("io.vidocq.peano.core", factory.getClass().getModule().getName());
        var context = page.getELContext();
        assertEquals("Ada:first:second", factory.createValueExpression(context,
                "${requestScope.user.name}:${param.q}:${paramValues.q[1]}", String.class).getValue(context));
        var value = factory.createValueExpression(context, "${requestScope.updated}", Object.class);
        value.setValue(context, 42);
        assertEquals(42, web.request.getAttribute("updated"));
    }

    @Test
    void pageContextRequestAndHttpRequestContextPathResolveWithDotAndIndexSyntax() throws Exception {
        var web = new PageRuntimeTest.Web();
        var page = web.open(false, null);
        var context = page.getELContext();
        var factory = ExpressionFactory.newInstance();
        var previous = System.getProperty("peano.reflection");
        try {
            System.setProperty("peano.reflection", "deny");
            var indexed = factory.createValueExpression(context,
                    "${pageContext['request']['contextPath']}", String.class).getValue(context);
            var dotted = factory.createValueExpression(context,
                    "${pageContext.request.contextPath}", String.class).getValue(context);

            assertEquals(web.request.getContextPath(), indexed);
            assertEquals(indexed, dotted);
        } finally {
            if (previous == null) System.clearProperty("peano.reflection");
            else System.setProperty("peano.reflection", previous);
        }
    }

    @Test
    void implicitObjectsAreReadOnlyButTheirScopeMapsAreWritable() throws Exception {
        var web = new PageRuntimeTest.Web();
        var page = web.open(false, null);
        var context = page.getELContext();
        var factory = ExpressionFactory.newInstance();
        assertThrows(jakarta.el.PropertyNotWritableException.class,
                () -> factory.createValueExpression(context, "${requestScope}", Object.class).setValue(context, java.util.Map.of()));
        assertEquals("", factory.createValueExpression(context, "${missing}", String.class).getValue(context));
        assertEquals(true, factory.createValueExpression(context, "${empty sessionScope}", Boolean.class).getValue(context));
    }

    @Test
    void headersCookiesAndApplicationInitParametersUseTheirServletSemantics() throws Exception {
        var web = new PageRuntimeTest.Web();
        web.request.headers.put("X-Name", "Ada");
        web.request.cookies = new jakarta.servlet.http.Cookie[]{new jakarta.servlet.http.Cookie("sid", "123")};
        web.context.initParameters.put("mode", "test");
        var page = web.open(false, null);
        var context = page.getELContext();
        var factory = ExpressionFactory.newInstance();
        assertEquals("Ada:Ada:123:test", factory.createValueExpression(context,
                "${header['x-name']}:${headerValues['X-NAME'][0]}:${cookie.sid.value}:${initParam.mode}", String.class).getValue(context));
    }
}

