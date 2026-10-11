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

import io.vidocq.foy.spi.jsp.JspEngine;
import io.vidocq.ibarra.core.binding.PageApplication;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import jakarta.servlet.jsp.HttpJspPage;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Container binding for constructor-based page indexes in the supplied application layer. */
public final class IbarraJspEngine implements JspEngine {
    public Application deploy(ServletContext context, ModuleLayer layer) {
        var pages = PageApplication.open(context, layer);
        var servlet = new Router(pages);
        return new Application() {
            private final AtomicBoolean closed = new AtomicBoolean();
            public Servlet servlet() { return servlet; }
            public java.util.List<JspEngine.Listener> listeners() {
                return pages.listeners().stream().map(listener -> new JspEngine.Listener(listener.type(), listener::createListener)).toList();
            }
            public Servlet servletFor(String path) {
                if (closed.get()) throw new IllegalStateException("JSP application is closed");
                var page = pages.createPage(path);
                return page == null ? new MissingPage() : page;
            }
            public void close() { if (closed.compareAndSet(false, true)) pages.close(); }
        };
    }

    private static final class MissingPage extends HttpServlet {
        @Override protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
        }
    }

    static final class Router extends HttpServlet {
        private final PageApplication application;
        private final ConcurrentHashMap<String, FutureTask<HttpJspPage>> pages = new ConcurrentHashMap<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        Router(PageApplication application) { this.application = application; }

        @Override protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
            if (closed.get()) throw new ServletException("JSP servlet is closed");
            String path = request.getDispatcherType() == DispatcherType.INCLUDE
                    && request.getAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH) instanceof String included
                    ? included : request.getServletPath();
            if (request.getDispatcherType() == DispatcherType.REQUEST && (path.startsWith("/WEB-INF/") || path.startsWith("/META-INF/"))) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND); return;
            }
            var page = page(path);
            if (page == null) response.sendError(HttpServletResponse.SC_NOT_FOUND);
            else page.service(request, response);
        }

        HttpJspPage page(String path) throws ServletException {
            if (closed.get()) throw new ServletException("JSP servlet is closed");
            if (!application.containsPage(path)) return null;
            var task = pages.computeIfAbsent(path, key -> new FutureTask<>(() -> {
                var page = application.createPage(key);
                if (page != null) page.init(getServletConfig());
                return page;
            }));
            task.run();
            try {
                return task.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new ServletException("Interrupted while initializing JSP page", interrupted);
            } catch (ExecutionException failure) {
                throw new ServletException("Cannot initialize JSP page " + path, failure.getCause());
            }
        }

        @Override public void destroy() {
            if (!closed.compareAndSet(false, true)) return;
            for (var task : pages.values()) {
                if (!task.isDone() || task.isCancelled()) continue;
                try { var page = task.get(); if (page != null) page.destroy(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                catch (ExecutionException failure) { /* Failed initialization is not a live servlet. */ }
                catch (RuntimeException failure) { log("JSP page destruction failed", failure); }
            }
            pages.clear();
        }
    }
}
