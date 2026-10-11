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

import io.vidocq.ibarra.spi.BeanScopeContext;
import jakarta.servlet.*;
import jakarta.servlet.jsp.PageContext;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BeanScopeLockTest {
    @Test
    void sharedNamespacesUseOneApplicationOwnedLockWithoutSessionState() throws Exception {
        var factory = new IbarraJspFactory();
        var web = new ServletDoubles.Context();
        var first = page(factory, web, true);
        var second = page(factory, web, true);
        var lock = ((BeanScopeContext) first).beanScopeLock(PageContext.SESSION_SCOPE);
        assertSame(lock, ((BeanScopeContext) second).beanScopeLock(PageContext.SESSION_SCOPE));
        assertSame(lock, ((BeanScopeContext) second).beanScopeLock(PageContext.APPLICATION_SCOPE));
        assertTrue(web.attributes.isEmpty());
        assertFalse(first.getSession().getAttributeNames().hasMoreElements());
        var other = page(factory, new ServletDoubles.Context(), true);
        assertNotSame(lock, ((BeanScopeContext) other).beanScopeLock(PageContext.APPLICATION_SCOPE));
        factory.releaseApplicationContext(web);
        assertNotSame(lock, ((BeanScopeContext) page(factory, web, true)).beanScopeLock(PageContext.APPLICATION_SCOPE));
    }

    @Test
    void locksAreReentrantAndCoordinateVirtualThreads() throws Exception {
        var factory = new IbarraJspFactory();
        var web = new ServletDoubles.Context();
        var pages = new ArrayList<PageContext>();
        for (int i = 0; i < 16; i++) pages.add(page(factory, web, true));
        int[] count = {0};
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<?>>();
            for (var page : pages) futures.add(executor.submit(() -> {
                start.await();
                var lock = ((BeanScopeContext) page).beanScopeLock(PageContext.SESSION_SCOPE);
                for (int i = 0; i < 64; i++) {
                    lock.lock();
                    try {
                        lock.lock();
                        try { int previous = count[0]; Thread.yield(); count[0] = previous + 1; }
                        finally { lock.unlock(); }
                    } finally { lock.unlock(); }
                }
                return null;
            }));
            start.countDown();
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        }
        assertEquals(1024, count[0]);
    }

    @Test
    void scopeAccessRejectsSessionlessAndReleasedContexts() throws Exception {
        var page = page(new IbarraJspFactory(), new ServletDoubles.Context(), false);
        var access = (BeanScopeContext) page;
        assertThrows(IllegalStateException.class, () -> access.beanScopeLock(PageContext.SESSION_SCOPE));
        for (int scope : List.of(0, PageContext.PAGE_SCOPE, PageContext.REQUEST_SCOPE, 5))
            assertThrows(IllegalArgumentException.class, () -> access.beanScopeLock(scope));
        assertNotNull(access.beanScopeLock(PageContext.APPLICATION_SCOPE));
        page.release();
        assertThrows(IllegalStateException.class, () -> access.beanScopeLock(PageContext.APPLICATION_SCOPE));
        assertThrows(IllegalStateException.class, () -> ((BeanScopeContext) new IbarraPageContext()).beanScopeLock(PageContext.APPLICATION_SCOPE));
    }

    private PageContext page(IbarraJspFactory factory, ServletDoubles.Context context, boolean session) throws Exception {
        var servlet = new PageApplicationTest.TestPage();
        servlet.init(new ServletConfig() {
            public String getServletName() { return "scope"; }
            public ServletContext getServletContext() { return context; }
            public String getInitParameter(String name) { return null; }
            public Enumeration<String> getInitParameterNames() { return Collections.emptyEnumeration(); }
        });
        return Objects.requireNonNull(factory.getPageContext(servlet, new ServletDoubles.Request(),
                new ServletDoubles.Response(), null, session, 0, true));
    }
}
