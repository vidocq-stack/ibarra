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

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.servlet.*;
import jakarta.servlet.jsp.*;

/** Container-neutral factory; application contexts are removed at undeployment. */
final class IbarraJspFactory extends JspFactory {
    private final ConcurrentHashMap<ServletContext, IbarraApplicationContext> applications = new ConcurrentHashMap<>();

    @Override
    public PageContext getPageContext(Servlet servlet, ServletRequest request,
            ServletResponse response, String errorPageURL, boolean needsSession,
            int buffer, boolean autoFlush) {
        var application = (IbarraApplicationContext) getJspApplicationContext(servlet.getServletConfig().getServletContext());
        var page = new IbarraPageContext(application);
        try {
            page.initialize(servlet, request, response, errorPageURL, needsSession, buffer, autoFlush);
            return page;
        } catch (IOException | IllegalArgumentException | IllegalStateException _) {
            page.release();
            return null;
        }
    }

    @Override
    public void releasePageContext(PageContext page) {
        if (page != null) page.release();
    }

    @Override
    public JspEngineInfo getEngineInfo() {
        return new JspEngineInfo() {
            @Override public String getSpecificationVersion() { return "4.0"; }
        };
    }

    @Override
    public JspApplicationContext getJspApplicationContext(ServletContext context) {
        return applications.computeIfAbsent(Objects.requireNonNull(context), ignored -> new IbarraApplicationContext());
    }

    /** Called by the container only after all requests for this application have drained. */
    void releaseApplicationContext(ServletContext context) {
        applications.remove(Objects.requireNonNull(context));
    }
}

