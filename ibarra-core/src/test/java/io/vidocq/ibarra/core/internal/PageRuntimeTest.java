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

import static jakarta.servlet.jsp.PageContext.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Collections;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import jakarta.servlet.jsp.*;
import org.junit.jupiter.api.Test;

class PageRuntimeTest {

    @Test
    void initializesImplicitObjectsAndSessionOnlyWhenRequested() throws Exception {
        var web = new Web();
        var pc = web.open(true, null);
        assertSame(web.request.session, pc.getSession());
        assertEquals(1, web.request.sessionCalls);
        assertSame(web.page, pc.getPage());
        assertSame(web.request, pc.getAttribute(REQUEST));
        assertSame(web.response, pc.getAttribute(RESPONSE));
        assertSame(web.config, pc.getAttribute(CONFIG));
        assertSame(web.context, pc.getAttribute(APPLICATION));
        assertSame(pc, pc.getAttribute(PAGECONTEXT));
        assertSame(pc.getOut(), pc.getAttribute(OUT));
        assertEquals(0, web.response.acquisitions);
        var other = new Web();
        var sessionless = other.open(false, null);
        assertNull(sessionless.getSession());
        assertEquals(0, other.request.sessionCalls);
        assertThrows(IllegalStateException.class, () -> sessionless.getAttribute("x", SESSION_SCOPE));
    }

    @Test
    void findsAttributesInScopeOrderAndDelegatesStorage() throws Exception {
        var web = new Web();
        var pc = web.open(true, null);
        pc.setAttribute("x", "application", APPLICATION_SCOPE);
        pc.setAttribute("x", "session", SESSION_SCOPE);
        pc.setAttribute("x", "request", REQUEST_SCOPE);
        pc.setAttribute("x", "page");
        assertEquals("page", pc.findAttribute("x"));
        assertEquals(PAGE_SCOPE, pc.getAttributesScope("x"));
        pc.setAttribute("x", null);
        assertEquals("request", pc.findAttribute("x"));
        assertEquals("request", web.request.attributes.get("x"));
        pc.removeAttribute("x", REQUEST_SCOPE);
        assertEquals("session", pc.findAttribute("x"));
        pc.removeAttribute("x", SESSION_SCOPE);
        assertEquals("application", pc.findAttribute("x"));
        assertTrue(Collections.list(pc.getAttributeNamesInScope(APPLICATION_SCOPE)).contains("x"));
        pc.removeAttribute("x");
        assertNull(pc.findAttribute("x"));
        assertEquals(0, pc.getAttributesScope("x"));
    }

    @Test
    void sessionlessLookupSkipsSessionAndStillReachesApplication() throws Exception {
        var web = new Web();
        var pc = web.open(false, null);
        pc.setAttribute("x", "global", APPLICATION_SCOPE);
        assertEquals("global", pc.findAttribute("x"));
        assertEquals(APPLICATION_SCOPE, pc.getAttributesScope("x"));
        pc.removeAttribute("x");
        assertEquals(0, web.request.sessionCalls);
        assertThrows(NullPointerException.class, () -> pc.getAttribute(null));
        assertThrows(IllegalArgumentException.class, () -> pc.getAttribute("x", 99));
    }

    @Test
    void nestedBodiesUpdateOutAndRestoreThePreviousWriter() throws Exception {
        var pc = new Web().open(false, null);
        var base = pc.getOut();
        var outer = pc.pushBody();
        outer.write("outer");
        assertSame(outer, pc.getAttribute(OUT));
        var inner = pc.pushBody();
        inner.write("inner");
        assertSame(outer, pc.popBody());
        inner.writeOut(outer);
        assertEquals("outerinner", outer.getString());
        var target = new StringWriter();
        var redirected = pc.pushBody(target);
        redirected.write("direct");
        assertEquals("direct", target.toString());
        assertSame(redirected, pc.getAttribute(OUT));
        assertSame(outer, pc.popBody());
        assertSame(base, pc.popBody());
        assertSame(base, pc.popBody());
    }

    @Test
    void includeFlushesByDefaultButCanPreservePendingOutput() throws Exception {
        var web = new Web();
        var dispatcher = web.dispatch("/pages/part.jsp");
        var pc = web.open(false, null);
        pc.getOut().write("before");
        pc.include("part.jsp");
        assertEquals("before", web.response.output.toString());
        assertEquals(1, dispatcher.includes);
        assertSame(web.request, dispatcher.request);
        assertSame(web.response, ((ServletResponseWrapper) dispatcher.response).getResponse());
        var other = new Web();
        var target = other.dispatch("/part.jsp");
        var pending = other.open(false, null);
        pending.getOut().write("pending");
        pending.include("/part.jsp", false);
        assertEquals(0, other.response.acquisitions);
        assertEquals(1, target.includes);
        pending.getOut().flush();
        assertEquals("pending", other.response.output.toString());
    }

    @Test
    void forwardDiscardsPendingOutputAndRejectsCommittedResponses() throws Exception {
        var web = new Web();
        var dispatcher = web.dispatch("/pages/next.jsp");
        var pc = web.open(false, null);
        pc.getOut().write("discard");
        pc.forward("next.jsp");
        assertEquals("", web.response.output.toString());
        assertEquals(1, dispatcher.forwards);
        var committed = new Web();
        var committedPage = committed.open(false, null);
        committedPage.getOut().flush();
        assertThrows(IllegalStateException.class, () -> committedPage.forward("/next.jsp"));
    }

    @Test
    void relativeDispatchDuringIncludeUsesTheIncludedServletPath() throws Exception {
        var web = new Web();
        web.request.setAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH, "/tags/body.jsp");
        var target = web.dispatch("/tags/part.jsp");
        web.open(false, null).include("part.jsp", false);
        assertEquals(1, target.includes);
    }

    @Test
    void errorPageReceivesStandardErrorAttributesAndOriginalThrowable() throws Exception {
        var web = new Web();
        var dispatcher = web.dispatch("/error.jsp");
        var pc = web.open(false, "/error.jsp");
        var problem = new IllegalArgumentException("bad input");
        pc.getOut().write("discard");
        pc.handlePageException(problem);
        assertEquals(1, dispatcher.forwards);
        assertSame(problem, web.request.getAttribute(RequestDispatcher.ERROR_EXCEPTION));
        assertSame(problem, web.request.getAttribute("jakarta.servlet.jsp.jspException"));
        assertEquals(500, web.request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE));
        assertEquals("/pages/view.jsp", web.request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI));
        var errorPage = web.open(false, null);
        assertSame(problem, errorPage.getException());
        assertEquals(500, errorPage.getErrorData().getStatusCode());
        assertSame(problem, errorPage.getErrorData().getThrowable());
    }

    @Test
    void committedErrorPageIsIncludedAndUnhandledExceptionsKeepTheirType() throws Exception {
        var web = new Web();
        var target = web.dispatch("/error.jsp");
        var pc = web.open(false, "/error.jsp");
        pc.getOut().flush();
        pc.handlePageException(new IOException("failed"));
        assertEquals(1, target.includes);
        var plain = new Web().open(false, null);
        var io = new IOException("io");
        var servlet = new ServletException("servlet");
        var runtime = new IllegalStateException("runtime");
        assertSame(io, assertThrows(IOException.class, () -> plain.handlePageException(io)));
        assertSame(servlet, assertThrows(ServletException.class, () -> plain.handlePageException(servlet)));
        assertSame(runtime, assertThrows(IllegalStateException.class, () -> plain.handlePageException(runtime)));
        var checked = new Exception("checked");
        assertSame(checked, assertThrows(ServletException.class, () -> plain.handlePageException(checked)).getCause());
        assertThrows(NullPointerException.class, () -> plain.handlePageException((Throwable) null));
    }

    @Test
    void releaseDropsRequestReferencesAndPermitsFreshInitialization() throws Exception {
        var web = new Web();
        var pc = web.open(false, null);
        pc.setAttribute("local", "gone");
        pc.pushBody().write("captured");
        pc.release();
        assertNull(pc.getPage());
        assertNull(pc.getRequest());
        assertNull(pc.getResponse());
        assertNull(pc.getSession());
        assertNull(pc.getOut());
        assertNull(pc.getAttribute("local"));
        pc.initialize(web.page, web.request, web.response, null, false, 16, true);
        assertNull(pc.getAttribute("local"));
        assertSame(pc.getOut(), pc.getAttribute(OUT));
    }

    @Test
    void factoryProvidesDistinctRequestContextsAndSpecificationVersion() throws Exception {
        var factory = new IbarraJspFactory();
        var web = new Web();
        var first = factory.getPageContext(web.page, web.request, web.response, null, false, 32, true);
        var second = factory.getPageContext(web.page, web.request, web.response, null, false, 32, true);
        assertNotNull(first);
        assertNotSame(first, second);
        first.setAttribute("local", "first");
        assertNull(second.getAttribute("local"));
        assertEquals("4.0", factory.getEngineInfo().getSpecificationVersion());
        factory.releasePageContext(first);
        factory.releasePageContext(null);
        assertNull(first.getRequest());
    }

    @Test
    void handWrittenHttpJspPageRendersUsingTheFactory() throws Exception {
        var web = new Web();
        var previous = JspFactory.getDefaultFactory();
        try {
            JspFactory.setDefaultFactory(new IbarraJspFactory());
            web.page._jspService(web.request, web.response);
            assertEquals("Hello, Ibarra!", web.response.output.toString());
        } finally {
            JspFactory.setDefaultFactory(previous);
        }
    }

    @Test
    void factorySharesApplicationContextsAndCanReleaseThemAtUndeploy() {
        var factory = new IbarraJspFactory();
        var one = new ServletDoubles.Context();
        var two = new ServletDoubles.Context();
        var app = factory.getJspApplicationContext(one);
        assertSame(app, factory.getJspApplicationContext(one));
        assertNotSame(app, factory.getJspApplicationContext(two));
        assertThrows(NullPointerException.class, () -> app.addELResolver(null));
        assertThrows(NullPointerException.class, () -> app.addELContextListener(null));
        factory.releaseApplicationContext(one);
        assertNotSame(app, factory.getJspApplicationContext(one));
    }

    @Test
    void includedOutputKeepsOrderAndCanBeCapturedInsideATagBody() throws Exception {
        var web = new Web();
        var target = web.dispatch("/part.jsp");
        target.includeText = "included";
        var pc = web.open(false, null);
        pc.getOut().write("before");
        pc.include("/part.jsp", false);
        pc.getOut().write("after");
        pc.getOut().flush();
        assertEquals("beforeincludedafter", web.response.output.toString());

        var other = new Web();
        var bodyTarget = other.dispatch("/part.jsp");
        bodyTarget.includeText = "captured";
        var bodyPage = other.open(false, null);
        var body = bodyPage.pushBody();
        bodyPage.include("/part.jsp", false);
        assertEquals("captured", body.getString());
        assertEquals(0, other.response.acquisitions);
    }

    @Test
    void applicationListenersObserveEachPageContextAndResolversFreezeOnFirstUse() throws Exception {
        var application = new IbarraApplicationContext(new FixtureExpressionFactory());
        var events = new java.util.ArrayList<jakarta.el.ELContext>();
        application.addELContextListener(event -> {
            events.add(event.getELContext());
            var page = (PageContext) event.getELContext().getContext(PageContext.class);
            assertSame(event.getELContext(), page.getELContext());
        });
        var web = new Web();
        var pc = new IbarraPageContext(application);
        pc.initialize(web.page, web.request, web.response, null, false, 64, true);
        pc.setAttribute("value", "page");
        var el = pc.getELContext();
        assertSame(el, pc.getELContext());
        assertEquals(1, events.size());
        assertSame(pc, el.getContext(JspContext.class));
        assertEquals("page", el.getELResolver().getValue(el, null, "value"));
        el.setPropertyResolved(false);
        el.getELResolver().setValue(el, null, "value", "updated");
        assertEquals("updated", pc.getAttribute("value"));
        assertThrows(IllegalStateException.class,
                () -> application.addELResolver(new jakarta.el.MapELResolver()));
        pc.release();
        pc.initialize(web.page, web.request, web.response, null, false, 64, true);
        assertNotSame(el, pc.getELContext());
        assertEquals(2, events.size());
    }

    @Test
    void invalidInitializationReturnsNullFromFactoryAndDirectInitializationIsAtomic() throws Exception {
        var web = new Web();
        var pc = new IbarraPageContext();
        assertThrows(IllegalArgumentException.class,
                () -> pc.initialize(web.page, web.request, web.response, null, true, -3, true));
        assertNull(pc.getRequest());
        assertEquals(0, web.request.sessionCalls);
        var factory = new IbarraJspFactory();
        assertNull(factory.getPageContext(web.page, web.request, web.response, null, false, -3, true));
        pc.initialize(web.page, web.request, web.response, null, false, 64, true);
        assertThrows(IllegalStateException.class,
                () -> pc.initialize(web.page, web.request, web.response, null, false, 64, true));
    }

    @Test
    void expressionFactoryIsInitializedOnceAcrossConcurrentRequests() throws Exception {
        var created = new java.util.concurrent.atomic.AtomicInteger();
        var provider = new FixtureExpressionFactory();
        var application = new IbarraApplicationContext(() -> {
            created.incrementAndGet();
            return provider;
        });
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<jakarta.el.ExpressionFactory>>();
            for (int i = 0; i < 32; i++) futures.add(executor.submit(application::getExpressionFactory));
            for (var future : futures) assertSame(provider, future.get());
        }
        assertEquals(1, created.get());
    }

    @Test
    void listenerFailureDoesNotLeaveAPartlyInitializedCachedContext() throws Exception {
        var application = new IbarraApplicationContext(new FixtureExpressionFactory());
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        application.addELContextListener(event -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("listener failed");
        });
        var web = new Web();
        var pc = new IbarraPageContext(application);
        pc.initialize(web.page, web.request, web.response, null, false, 64, true);
        assertThrows(IllegalStateException.class, pc::getELContext);
        assertNotNull(pc.getELContext());
        assertEquals(2, calls.get());
    }

    @Test
    void applicationContextIdentityIsStableUnderConcurrentAccess() throws Exception {
        var factory = new IbarraJspFactory();
        var servletContext = new ServletDoubles.Context();
        var expected = factory.getJspApplicationContext(servletContext);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<JspApplicationContext>>();
            for (int i = 0; i < 32; i++) futures.add(executor.submit(() -> factory.getJspApplicationContext(servletContext)));
            for (var future : futures) assertSame(expected, future.get());
        }
    }

    static final class FixtureExpressionFactory extends jakarta.el.ExpressionFactory {
        public jakarta.el.ValueExpression createValueExpression(jakarta.el.ELContext context, String expression, Class<?> type) {
            throw new UnsupportedOperationException();
        }
        public jakarta.el.ValueExpression createValueExpression(Object value, Class<?> type) {
            throw new UnsupportedOperationException();
        }
        public jakarta.el.MethodExpression createMethodExpression(jakarta.el.ELContext context, String expression,
                Class<?> type, Class<?>[] parameters) { throw new UnsupportedOperationException(); }
        public <T> T coerceToType(Object value, Class<T> type) { return type.cast(value); }
    }

    static final class Web {
        final ServletDoubles.Context context = new ServletDoubles.Context();
        final ServletDoubles.Request request = new ServletDoubles.Request();
        final ServletDoubles.Response response = new ServletDoubles.Response();
        final ServletConfig config = new ServletConfig() {
            public String getServletName() { return "fixture"; }
            public ServletContext getServletContext() { return context; }
            public String getInitParameter(String name) { return null; }
            public java.util.Enumeration<String> getInitParameterNames() { return Collections.emptyEnumeration(); }
        };
        final FixturePage page = new FixturePage();
        Web() throws ServletException { page.init(config); }
        IbarraPageContext open(boolean session, String error) throws IOException {
            var pc = new IbarraPageContext();
            pc.initialize(page, request, response, error, session, 64, true);
            return pc;
        }
        Dispatch dispatch(String path) {
            var result = new Dispatch();
            context.dispatchers.put(path, result);
            return result;
        }
    }

    static final class FixturePage extends HttpServlet implements HttpJspPage {
        public void jspInit() { }
        public void jspDestroy() { }
        public void _jspService(HttpServletRequest request, HttpServletResponse response) throws IOException {
            var factory = JspFactory.getDefaultFactory();
            var pc = factory.getPageContext(this, request, response, null, false, 64, true);
            try {
                pc.setAttribute("name", "Ibarra");
                pc.getOut().print("Hello, ");
                pc.getOut().print(pc.findAttribute("name"));
                pc.getOut().print('!');
                pc.getOut().flush();
            } finally {
                factory.releasePageContext(pc);
            }
        }
    }

    static final class Dispatch implements RequestDispatcher {
        String includeText;
        int forwards;
        int includes;
        ServletRequest request;
        ServletResponse response;
        public void forward(ServletRequest request, ServletResponse response) {
            forwards++; this.request = request; this.response = response;
        }
        public void include(ServletRequest request, ServletResponse response) throws IOException {
            includes++; this.request = request; this.response = response;
            if (includeText != null) response.getWriter().write(includeText);
        }
    }
}

