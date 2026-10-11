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

import io.vidocq.ibarra.core.binding.PageApplication;
import jakarta.servlet.ServletContext;
import jakarta.servlet.jsp.*;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Context-owned catalogue and cleanup; the standard factory belongs to the container. */
public final class IbarraPageApplication implements PageApplication {
    private static final IbarraJspFactory DEFAULT_FACTORY = new IbarraJspFactory();
    private final ServletContext context;
    private final PrecompiledPages pages;
    private final IbarraJspFactory factory;
    private final AtomicBoolean closed = new AtomicBoolean();

    public static PageApplication open(ServletContext context, ModuleLayer layer) {
        return open(context, PrecompiledPages.discover(Objects.requireNonNull(layer)));
    }
    static PageApplication open(ServletContext context, PrecompiledPages pages) {
        var existing = JspFactory.getDefaultFactory();
        if (existing == null) {
            JspFactory.setDefaultFactory(DEFAULT_FACTORY);
            existing = JspFactory.getDefaultFactory();
        }
        if (!(existing instanceof IbarraJspFactory factory)) throw new IllegalStateException("A different JSP factory is installed");
        factory.getJspApplicationContext(Objects.requireNonNull(context));
        return new IbarraPageApplication(context, pages, factory);
    }
    private IbarraPageApplication(ServletContext context, PrecompiledPages pages, IbarraJspFactory factory) {
        this.context = context; this.pages = pages; this.factory = factory;
    }
    public HttpJspPage createPage(String path) {
        if (closed.get()) throw new IllegalStateException("JSP application is closed");
        var descriptor = pages.find(path);
        return descriptor == null ? null : descriptor.createPage();
    }
    public boolean containsPage(String path) {
        if (closed.get()) throw new IllegalStateException("JSP application is closed");
        return pages.find(path) != null;
    }
    public void close() {
        if (closed.compareAndSet(false, true)) factory.releaseApplicationContext(context);
    }
    public java.util.List<io.vidocq.ibarra.spi.ListenerDescriptor> listeners() {
        if (closed.get()) throw new IllegalStateException("JSP application is closed");
        return pages.listeners();
    }
}
