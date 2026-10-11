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
package io.vidocq.ibarra.foy.internal;

import io.vidocq.ibarra.core.binding.PageApplication;
import io.vidocq.foy.internal.container.*;
import jakarta.servlet.http.*;
import jakarta.servlet.jsp.HttpJspPage;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RouterLifecycleTest {
    @Test
    void concurrentFirstRequestsInitializeOnePageAndMissingPathsDoNotAllocateFactories() throws Exception {
        var created = new AtomicInteger(); var initialized = new AtomicInteger();
        PageApplication application = new PageApplication() {
            public boolean containsPage(String path) { return path.equals("/page.jsp"); }
            public HttpJspPage createPage(String path) { created.incrementAndGet(); return new TestPage(initialized, new AtomicInteger(), false); }
            public void close() { }
        };
        var router = new IbarraJspEngine.Router(application);
        router.init(new ServletConfigImpl("jsp", new VidocqServletContext("/pages"), Map.of()));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Future<HttpJspPage>>();
            for (int i = 0; i < 24; i++) tasks.add(executor.submit(() -> router.page("/page.jsp")));
            var first = tasks.getFirst().get();
            for (var task : tasks) assertSame(first, task.get());
        }
        assertEquals(1, created.get()); assertEquals(1, initialized.get());
        for (int i = 0; i < 100; i++) assertNull(router.page("/missing-" + i + ".jsp"));
        assertEquals(1, created.get());
        router.destroy();
        assertThrows(jakarta.servlet.ServletException.class, () -> router.page("/page.jsp"));
    }

    @Test
    void failingDestroyDoesNotPreventOtherPagesFromBeingDestroyed() throws Exception {
        var destroyed = new AtomicInteger();
        PageApplication application = new PageApplication() {
            public boolean containsPage(String path) { return true; }
            public HttpJspPage createPage(String path) { return new TestPage(new AtomicInteger(), destroyed, path.equals("/bad.jsp")); }
            public void close() { }
        };
        var router = new IbarraJspEngine.Router(application);
        router.init(new ServletConfigImpl("jsp", new VidocqServletContext("/pages"), Map.of()));
        router.page("/bad.jsp"); router.page("/good.jsp");
        assertDoesNotThrow(router::destroy); router.destroy();
        assertEquals(2, destroyed.get());
    }

    static final class TestPage extends HttpServlet implements HttpJspPage {
        private final AtomicInteger initialized, destroyed; private final boolean failDestroy;
        TestPage(AtomicInteger initialized, AtomicInteger destroyed, boolean failDestroy) { this.initialized = initialized; this.destroyed = destroyed; this.failDestroy = failDestroy; }
        public void jspInit() { }
        public void jspDestroy() { }
        @Override public void init() { initialized.incrementAndGet(); }
        @Override public void destroy() { destroyed.incrementAndGet(); if (failDestroy) throw new IllegalStateException("destroy failure"); }
        public void _jspService(HttpServletRequest request, HttpServletResponse response) { }
    }
}
