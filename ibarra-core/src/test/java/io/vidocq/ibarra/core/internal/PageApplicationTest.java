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

import io.vidocq.ibarra.spi.*;
import jakarta.servlet.http.*;
import jakarta.servlet.jsp.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PageApplicationTest {
    @Test
    void deploymentInstallsOnlyItsOwnForwardHookAndUndeploymentRemovesIt() throws Exception {
        JspFactory previous = JspFactory.getDefaultFactory(); JspFactory.setDefaultFactory(null);
        var first = new ServletDoubles.Context(); var second = new ServletDoubles.Context();
        var catalogue = new PrecompiledPages(List.of());
        int[] calls = { 0 };
        var app = IbarraPageApplication.open(first, catalogue, (target, request, response) -> {
            calls[0]++; response.resetBuffer(); target.forward(request, response);
        });
        var other = IbarraPageApplication.open(second, catalogue);
        try {
            var factory = (IbarraJspFactory) JspFactory.getDefaultFactory();
            var target = new PageRuntimeTest.Dispatch(); first.dispatchers.put("/target.jsp", target); second.dispatchers.put("/target.jsp", target);
            for (var context : List.of(first, second)) {
                var servlet = new TestPage();
                servlet.init(new jakarta.servlet.ServletConfig() {
                    public String getServletName() { return "page"; }
                    public jakarta.servlet.ServletContext getServletContext() { return context; }
                    public String getInitParameter(String name) { return null; }
                    public java.util.Enumeration<String> getInitParameterNames() { return java.util.Collections.emptyEnumeration(); }
                });
                var page = factory.getPageContext(servlet, new ServletDoubles.Request(), new ServletDoubles.Response(), null, false, 64, true);
                try { page.forward("/target.jsp"); }
                finally { factory.releasePageContext(page); }
            }
            assertEquals(1, calls[0]); app.close();
            var fresh = (IbarraApplicationContext) factory.getJspApplicationContext(first);
            fresh.forwardDispatcher().forward(target, new ServletDoubles.Request(), new ServletDoubles.Response());
            assertEquals(1, calls[0]);
        } finally {
            app.close(); other.close(); ((IbarraJspFactory) JspFactory.getDefaultFactory()).releaseApplicationContext(first);
            JspFactory.setDefaultFactory(previous);
        }
    }

    @Test
    void deploymentOwnsFactoriesAndReleasesOnlyItsApplicationContext() {
        JspFactory previous = JspFactory.getDefaultFactory();
        JspFactory.setDefaultFactory(null);
        var first = new ServletDoubles.Context(); var second = new ServletDoubles.Context();
        var catalogue = new PrecompiledPages(List.of((PageIndex) () -> List.of(new PageDescriptor("/page.jsp", TestPage::new))));
        var app = IbarraPageApplication.open(first, catalogue);
        var other = IbarraPageApplication.open(second, catalogue);
        try {
            assertNotSame(app.createPage("/page.jsp"), app.createPage("/page.jsp"));
            assertNull(app.createPage("/missing.jsp"));
            var factory = JspFactory.getDefaultFactory();
            var firstContext = factory.getJspApplicationContext(first);
            var secondContext = factory.getJspApplicationContext(second);
            app.close(); app.close();
            assertNotSame(firstContext, factory.getJspApplicationContext(first));
            assertSame(secondContext, factory.getJspApplicationContext(second));
            assertThrows(IllegalStateException.class, () -> app.createPage("/page.jsp"));
        } finally {
            other.close();
            ((IbarraJspFactory) JspFactory.getDefaultFactory()).releaseApplicationContext(first);
            JspFactory.setDefaultFactory(previous);
        }
    }

    public static final class TestPage extends HttpServlet implements HttpJspPage {
        public void jspInit() { /* Fixture page: nothing to initialize. */ }
        public void jspDestroy() { /* Fixture page: nothing to release. */ }
        public void _jspService(HttpServletRequest request, HttpServletResponse response) { /* Fixture page: never rendered by this test. */ }
    }
}
