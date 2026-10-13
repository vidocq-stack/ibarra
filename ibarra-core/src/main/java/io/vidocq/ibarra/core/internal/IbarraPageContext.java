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
import java.io.Writer;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import jakarta.el.ELContext;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import jakarta.servlet.jsp.*;
import jakarta.servlet.jsp.tagext.BodyContent;

/** Per-request page state. No thread-local storage or context pooling. */
final class IbarraPageContext extends PageContext implements io.vidocq.ibarra.spi.BeanScopeContext,
        io.vidocq.ibarra.spi.PageDispatchContext {
    private final IbarraApplicationContext application;
    private final Map<String, Object> attributes = new HashMap<>();
    private final ArrayDeque<JspWriter> bodies = new ArrayDeque<>();
    private Servlet servlet;
    private ServletRequest request;
    private ServletResponse response;
    private ServletConfig config;
    private ServletContext context;
    private HttpSession session;
    private String errorPage;
    private JspWriter out;
    private BufferedJspWriter base;
    private ELContext elContext;
    private IbarraPageContext includingPage;
    private boolean forwarded;

    IbarraPageContext() { this(new IbarraApplicationContext()); }
    IbarraPageContext(IbarraApplicationContext application) { this.application = Objects.requireNonNull(application); }

    @Override
    public void initialize(Servlet servlet, ServletRequest request, ServletResponse response,
            String errorPageURL, boolean needsSession, int bufferSize, boolean autoFlush) throws IOException {
        if (this.servlet != null) throw new IllegalStateException("Page context is already initialized");
        Objects.requireNonNull(servlet);
        Objects.requireNonNull(request);
        Objects.requireNonNull(response);
        var config = Objects.requireNonNull(servlet.getServletConfig());
        var context = Objects.requireNonNull(config.getServletContext());
        var writer = new BufferedJspWriter(response::getWriter, bufferSize, autoFlush);
        HttpSession session = null;
        if (needsSession) {
            if (!(request instanceof HttpServletRequest http)) throw new IllegalStateException("Session requires an HTTP request");
            session = http.getSession();
            if (session == null) throw new IllegalStateException("Session is unavailable");
        }
        this.servlet = servlet;
        this.request = request;
        this.response = response;
        this.includingPage = includingPage(response);
        this.forwarded = false;
        this.config = config;
        this.context = context;
        this.session = session;
        this.errorPage = errorPageURL;
        this.base = writer;
        this.out = writer;
        attributes.put(PAGE, servlet);
        attributes.put(PAGECONTEXT, this);
        attributes.put(REQUEST, request);
        attributes.put(RESPONSE, response);
        attributes.put(CONFIG, config);
        attributes.put(APPLICATION, context);
        attributes.put(OUT, writer);
        if (session != null) attributes.put(SESSION, session);
        var exception = getException();
        if (exception != null) attributes.put(EXCEPTION, exception);
    }

    @Override
    public void release() {
        attributes.clear();
        bodies.clear();
        servlet = null; request = null; response = null; config = null; context = null;
        session = null; errorPage = null; out = null; base = null; elContext = null;
        includingPage = null; forwarded = false;
    }

    private static String name(String name) { return Objects.requireNonNull(name, "attribute name"); }
    private HttpSession requiredSession() {
        if (session == null) throw new IllegalStateException("Page does not participate in a session");
        return session;
    }

    @Override
    public java.util.concurrent.locks.Lock beanScopeLock(int scope) {
        if (servlet == null) throw new IllegalStateException("Page context is inactive");
        if (scope == SESSION_SCOPE) requiredSession();
        else if (scope != APPLICATION_SCOPE) throw new IllegalArgumentException("Not a shared scope: " + scope);
        return application.beanScopeLock;
    }

    @Override public boolean isForwarded() { return forwarded; }

    @Override public Object getAttribute(String name) { return attributes.get(name(name)); }
    @Override
    public Object getAttribute(String name, int scope) {
        name(name);
        return switch (scope) {
            case PAGE_SCOPE -> attributes.get(name);
            case REQUEST_SCOPE -> request.getAttribute(name);
            case SESSION_SCOPE -> requiredSession().getAttribute(name);
            case APPLICATION_SCOPE -> context.getAttribute(name);
            default -> throw new IllegalArgumentException("Invalid scope: " + scope);
        };
    }

    @Override public void setAttribute(String name, Object value) { setAttribute(name, value, PAGE_SCOPE); }
    @Override
    public void setAttribute(String name, Object value, int scope) {
        name(name);
        if (value == null) { removeAttribute(name, scope); return; }
        switch (scope) {
            case PAGE_SCOPE -> attributes.put(name, value);
            case REQUEST_SCOPE -> request.setAttribute(name, value);
            case SESSION_SCOPE -> requiredSession().setAttribute(name, value);
            case APPLICATION_SCOPE -> context.setAttribute(name, value);
            default -> throw new IllegalArgumentException("Invalid scope: " + scope);
        }
    }

    @Override
    public void removeAttribute(String name, int scope) {
        name(name);
        switch (scope) {
            case PAGE_SCOPE -> attributes.remove(name);
            case REQUEST_SCOPE -> request.removeAttribute(name);
            case SESSION_SCOPE -> requiredSession().removeAttribute(name);
            case APPLICATION_SCOPE -> context.removeAttribute(name);
            default -> throw new IllegalArgumentException("Invalid scope: " + scope);
        }
    }

    @Override
    public void removeAttribute(String name) {
        removeAttribute(name, PAGE_SCOPE);
        removeAttribute(name, REQUEST_SCOPE);
        if (session != null) removeAttribute(name, SESSION_SCOPE);
        removeAttribute(name, APPLICATION_SCOPE);
    }

    @Override
    public Object findAttribute(String name) {
        int scope = getAttributesScope(name);
        return scope == 0 ? null : getAttribute(name, scope);
    }

    @Override
    public int getAttributesScope(String name) {
        name(name);
        if (getAttribute(name) != null) return PAGE_SCOPE;
        if (request.getAttribute(name) != null) return REQUEST_SCOPE;
        if (session != null && session.getAttribute(name) != null) return SESSION_SCOPE;
        if (context.getAttribute(name) != null) return APPLICATION_SCOPE;
        return 0;
    }

    @Override
    public Enumeration<String> getAttributeNamesInScope(int scope) {
        return switch (scope) {
            case PAGE_SCOPE -> Collections.enumeration(attributes.keySet());
            case REQUEST_SCOPE -> request.getAttributeNames();
            case SESSION_SCOPE -> requiredSession().getAttributeNames();
            case APPLICATION_SCOPE -> context.getAttributeNames();
            default -> throw new IllegalArgumentException("Invalid scope: " + scope);
        };
    }

    @Override public HttpSession getSession() { return session; }
    @Override public Object getPage() { return servlet; }
    @Override public ServletRequest getRequest() { return request; }
    @Override public ServletResponse getResponse() { return response; }
    @Override public ServletConfig getServletConfig() { return config; }
    @Override public ServletContext getServletContext() { return context; }
    @Override public JspWriter getOut() { return out; }

    @Override
    public Exception getException() {
        if (request == null) return null;
        var error = request.getAttribute("jakarta.servlet.jsp.jspException");
        if (error == null) error = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        if (error instanceof Exception exception) return exception;
        return error instanceof Throwable throwable ? new ServletException(throwable) : null;
    }

    @Override
    public BodyContent pushBody() {
        var body = new BufferedBodyContent(out);
        push(body);
        return body;
    }

    @Override
    public JspWriter pushBody(Writer writer) {
        Objects.requireNonNull(writer);
        var body = new BufferedJspWriter(() -> writer, JspWriter.NO_BUFFER, true);
        push(body);
        return body;
    }

    private void push(JspWriter writer) {
        bodies.push(out);
        out = writer;
        attributes.put(OUT, out);
    }

    @Override
    public JspWriter popBody() {
        if (!bodies.isEmpty()) out = bodies.pop();
        attributes.put(OUT, out);
        return out;
    }

    private RequestDispatcher dispatcher(String path) throws ServletException {
        Objects.requireNonNull(path);
        if (!path.startsWith("/")) {
            Object included = request.getAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH);
            String current = included instanceof String value ? value
                    : request instanceof HttpServletRequest http ? http.getServletPath() : "/";
            path = current.substring(0, current.lastIndexOf('/') + 1) + path;
        }
        var dispatcher = context.getRequestDispatcher(path);
        if (dispatcher == null) throw new ServletException("No dispatcher for " + path);
        return dispatcher;
    }

    @Override
    public void forward(String path) throws ServletException, IOException {
        var pages = new ArrayDeque<IbarraPageContext>();
        for (var page = this; page != null; page = page.includingPage) {
            if (page.response.isCommitted()) throw new IllegalStateException("Response is already committed");
            if (page.base.hasFlushed()) throw new IllegalStateException("Page output has already been flushed");
            pages.addLast(page);
        }
        var target = dispatcher(path);
        for (var page : pages) {
            if (page.base.getBufferSize() != JspWriter.NO_BUFFER) page.base.clearBuffer();
            if (page.out instanceof BodyContent body) body.clearBody();
            for (var writer : page.bodies) if (writer instanceof BodyContent body) body.clearBody();
        }
        var destination = pages.getLast().response;
        destination.resetBuffer();
        target.forward(request, destination);
        for (var page : pages) page.forwarded = true;
    }

    @Override public void include(String path) throws ServletException, IOException { include(path, true); }
    @Override
    public void include(String path, boolean flush) throws ServletException, IOException {
        if (flush) out.flush();
        var writer = new PrintWriter(out) {
            @Override public void close() { }
            @Override public void flush() { }
        };
        ServletResponse included;
        if (response instanceof HttpServletResponse http) {
            included = new HttpInclusion(http, writer);
        } else {
            included = new GenericInclusion(response, writer);
        }
        dispatcher(path).include(request, included);
        if (writer.checkError()) throw new IOException("Failed to write included output");
    }

    private interface Inclusion {
        IbarraPageContext includingPage();
    }

    private static IbarraPageContext includingPage(ServletResponse response) {
        while (response instanceof ServletResponseWrapper wrapper) {
            if (response instanceof Inclusion inclusion) return inclusion.includingPage();
            response = wrapper.getResponse();
        }
        return null;
    }

    private final class HttpInclusion extends HttpServletResponseWrapper implements Inclusion {
        private final PrintWriter writer;
        HttpInclusion(HttpServletResponse response, PrintWriter writer) { super(response); this.writer = writer; }
        public IbarraPageContext includingPage() { return IbarraPageContext.this; }
        @Override public PrintWriter getWriter() { return writer; }
        @Override public ServletOutputStream getOutputStream() {
            throw new IllegalStateException("JSP inclusion uses character output");
        }
    }

    private final class GenericInclusion extends ServletResponseWrapper implements Inclusion {
        private final PrintWriter writer;
        GenericInclusion(ServletResponse response, PrintWriter writer) { super(response); this.writer = writer; }
        public IbarraPageContext includingPage() { return IbarraPageContext.this; }
        @Override public PrintWriter getWriter() { return writer; }
        @Override public ServletOutputStream getOutputStream() {
            throw new IllegalStateException("JSP inclusion uses character output");
        }
    }

    @Override
    public void handlePageException(Exception exception) throws ServletException, IOException {
        handlePageException((Throwable) exception);
    }

    @Override
    public void handlePageException(Throwable exception) throws ServletException, IOException {
        Objects.requireNonNull(exception);
        if (errorPage == null || errorPage.isEmpty()) {
            if (exception instanceof IOException io) throw io;
            if (exception instanceof ServletException servletException) throw servletException;
            if (exception instanceof RuntimeException runtime) throw runtime;
            if (exception instanceof Error error) throw error;
            throw new ServletException(exception);
        }
        request.setAttribute("jakarta.servlet.jsp.jspException", exception);
        request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, exception);
        request.setAttribute(RequestDispatcher.ERROR_EXCEPTION_TYPE, exception.getClass());
        request.setAttribute(RequestDispatcher.ERROR_MESSAGE, exception.getMessage());
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 500);
        request.setAttribute(RequestDispatcher.ERROR_SERVLET_NAME, config.getServletName());
        if (request instanceof HttpServletRequest http) request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, http.getRequestURI());
        if (response.isCommitted() || base.hasFlushed()) include(errorPage, false);
        else forward(errorPage);
    }

    @Override
    public ELContext getELContext() {
        if (elContext == null) {
            elContext = application.createELContext(this);
            // Listeners may call getELContext; publish it before invoking them.
            try {
                application.notifyListeners(elContext);
            } catch (RuntimeException | Error failure) {
                elContext = null;
                throw failure;
            }
        }
        return elContext;
    }
}

