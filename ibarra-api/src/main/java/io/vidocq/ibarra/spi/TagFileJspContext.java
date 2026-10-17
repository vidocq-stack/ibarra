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
package io.vidocq.ibarra.spi;

import java.io.IOException;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import jakarta.el.CompositeELResolver;
import jakarta.el.ELContext;
import jakarta.el.ELResolver;
import jakarta.el.EvaluationListener;
import jakarta.el.FunctionMapper;
import jakarta.el.ImportHandler;
import jakarta.el.VariableMapper;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.jsp.JspContext;
import jakarta.servlet.jsp.JspWriter;
import jakarta.servlet.jsp.PageContext;
import jakarta.servlet.jsp.tagext.BodyContent;

/**
 * Per-invocation page context for a generated standard tag file.
 *
 * <p>Page-scoped attributes belong to this wrapper. Request, session, and
 * application attributes, along with servlet dispatch and writer operations,
 * are delegated to the invoking page context.
 */
public final class TagFileJspContext extends PageContext {
    private final PageContext invoking;
    private final boolean errorOnELNotFound;
    private final Map<String, Object> pageAttributes = new LinkedHashMap<>();
    private ELContext elContext;

    public TagFileJspContext(PageContext invoking) {
        this(invoking, false);
    }

    public TagFileJspContext(JspContext invokingContext, boolean errorOnELNotFound) {
        Objects.requireNonNull(invokingContext, "invoking JSP context");
        if (!(invokingContext instanceof PageContext pageContext))
            throw new IllegalArgumentException("A tag-file context requires an invoking PageContext");
        this.invoking = pageContext;
        this.errorOnELNotFound = errorOnELNotFound;
    }

    /**
     * Returns whether this tag-file directive enables strict handling of unresolved EL names.
     * The value is exposed in the wrapped EL context for the final unresolved-name resolver;
     * {@code false} leaves the invoking page's policy in effect.
     */
    public boolean errorOnELNotFound() {
        return errorOnELNotFound;
    }

    @Override
    public void initialize(Servlet servlet, ServletRequest request, ServletResponse response,
            String errorPageURL, boolean needsSession, int bufferSize, boolean autoFlush) {
        throw new IllegalStateException("A tag-file JSP context wrapper cannot be initialized");
    }

    @Override public void release() { pageAttributes.clear(); elContext = null; }
    @Override public HttpSession getSession() { return invoking.getSession(); }
    @Override public Object getPage() { return invoking.getPage(); }
    @Override public ServletRequest getRequest() { return invoking.getRequest(); }
    @Override public ServletResponse getResponse() { return invoking.getResponse(); }
    @Override public Exception getException() { return invoking.getException(); }
    @Override public ServletConfig getServletConfig() { return invoking.getServletConfig(); }
    @Override public ServletContext getServletContext() { return invoking.getServletContext(); }
    @Override public void forward(String path) throws ServletException, IOException { invoking.forward(path); }
    @Override public void include(String path) throws ServletException, IOException { invoking.include(path); }
    @Override public void include(String path, boolean flush) throws ServletException, IOException { invoking.include(path, flush); }
    @Override public void handlePageException(Exception failure) throws ServletException, IOException { invoking.handlePageException(failure); }
    @Override public void handlePageException(Throwable failure) throws ServletException, IOException { invoking.handlePageException(failure); }
    @Override public BodyContent pushBody() { return invoking.pushBody(); }
    @Override public JspWriter pushBody(java.io.Writer writer) { return invoking.pushBody(writer); }
    @Override public JspWriter popBody() { return invoking.popBody(); }
    @Override public JspWriter getOut() { return invoking.getOut(); }
    @Override
    public ELContext getELContext() {
        if (elContext == null) elContext = new TagFileELContext(invoking.getELContext());
        return elContext;
    }
    @Override public jakarta.servlet.jsp.ErrorData getErrorData() { return invoking.getErrorData(); }

    @Override
    public void setAttribute(String name, Object value) {
        setAttribute(name, value, PAGE_SCOPE);
    }

    @Override
    public void setAttribute(String name, Object value, int scope) {
        Objects.requireNonNull(name, "attribute name");
        if (scope == PAGE_SCOPE) {
            if (value == null) pageAttributes.remove(name);
            else pageAttributes.put(name, value);
        } else invoking.setAttribute(name, value, scope);
    }

    @Override
    public Object getAttribute(String name) {
        Objects.requireNonNull(name, "attribute name");
        Object value = pageAttributes.get(name);
        if (value != null) return value;
        value = invoking.getAttribute(name, REQUEST_SCOPE);
        if (value != null) return value;
        if (invoking.getSession() != null) {
            value = invoking.getAttribute(name, SESSION_SCOPE);
            if (value != null) return value;
        }
        return invoking.getAttribute(name, APPLICATION_SCOPE);
    }

    @Override
    public Object getAttribute(String name, int scope) {
        Objects.requireNonNull(name, "attribute name");
        return scope == PAGE_SCOPE ? pageAttributes.get(name) : invoking.getAttribute(name, scope);
    }

    @Override public Object findAttribute(String name) { return getAttribute(name); }

    @Override
    public int getAttributesScope(String name) {
        Objects.requireNonNull(name, "attribute name");
        if (pageAttributes.containsKey(name)) return PAGE_SCOPE;
        if (invoking.getAttribute(name, REQUEST_SCOPE) != null) return REQUEST_SCOPE;
        if (invoking.getSession() != null && invoking.getAttribute(name, SESSION_SCOPE) != null) return SESSION_SCOPE;
        if (invoking.getAttribute(name, APPLICATION_SCOPE) != null) return APPLICATION_SCOPE;
        return 0;
    }

    @Override
    public Enumeration<String> getAttributeNamesInScope(int scope) {
        return scope == PAGE_SCOPE
                ? Collections.enumeration(pageAttributes.keySet())
                : invoking.getAttributeNamesInScope(scope);
    }

    @Override
    public void removeAttribute(String name) {
        Objects.requireNonNull(name, "attribute name");
        pageAttributes.remove(name);
        invoking.removeAttribute(name, REQUEST_SCOPE);
        if (invoking.getSession() != null) invoking.removeAttribute(name, SESSION_SCOPE);
        invoking.removeAttribute(name, APPLICATION_SCOPE);
    }

    @Override
    public void removeAttribute(String name, int scope) {
        Objects.requireNonNull(name, "attribute name");
        if (scope == PAGE_SCOPE) pageAttributes.remove(name);
        else invoking.removeAttribute(name, scope);
    }

    private final class TagFileELContext extends ELContext {
        private final ELContext delegate;
        private final ELResolver resolver;

        TagFileELContext(ELContext delegate) {
            this.delegate = Objects.requireNonNull(delegate, "invoking EL context");
            var resolvers = new CompositeELResolver();
            resolvers.add(new TagFilePageResolver());
            resolvers.add(delegate.getELResolver());
            this.resolver = resolvers;
        }

        @Override public ELResolver getELResolver() { return resolver; }
        @Override public FunctionMapper getFunctionMapper() { return delegate.getFunctionMapper(); }
        @Override public VariableMapper getVariableMapper() { return delegate.getVariableMapper(); }
        @Override public ImportHandler getImportHandler() { return delegate.getImportHandler(); }
        @Override public Object getContext(Class<?> key) {
            if (key == TagFileJspContext.class || key == JspContext.class || key == PageContext.class)
                return TagFileJspContext.this;
            return delegate.getContext(key);
        }
        @Override public void putContext(Class<?> key, Object value) { delegate.putContext(key, value); }
        @Override public boolean isPropertyResolved() { return delegate.isPropertyResolved(); }
        @Override public void setPropertyResolved(boolean resolved) { delegate.setPropertyResolved(resolved); }
        @Override public void setPropertyResolved(Object base, Object property) { delegate.setPropertyResolved(base, property); }
        @Override public java.util.Locale getLocale() { return delegate.getLocale(); }
        @Override public void setLocale(java.util.Locale locale) { delegate.setLocale(locale); }
        @Override public void addEvaluationListener(EvaluationListener listener) { delegate.addEvaluationListener(listener); }
        @Override public java.util.List<EvaluationListener> getEvaluationListeners() { return delegate.getEvaluationListeners(); }
        @Override public void notifyBeforeEvaluation(String expression) { delegate.notifyBeforeEvaluation(expression); }
        @Override public void notifyAfterEvaluation(String expression) { delegate.notifyAfterEvaluation(expression); }
        @Override public void notifyPropertyResolved(Object base, Object property) { delegate.notifyPropertyResolved(base, property); }
        @Override public boolean isLambdaArgument(String name) { return delegate.isLambdaArgument(name); }
        @Override public Object getLambdaArgument(String name) { return delegate.getLambdaArgument(name); }
        @Override public void enterLambdaScope(Map<String, Object> arguments) { delegate.enterLambdaScope(arguments); }
        @Override public void exitLambdaScope() { delegate.exitLambdaScope(); }
        @Override public <T> T convertToType(Object value, Class<T> type) { return delegate.convertToType(value, type); }
    }

    private final class TagFilePageResolver extends ELResolver {
        private final Map<String, Object> pageScope = new AbstractMap<>() {
            @Override public Object get(Object key) {
                return key instanceof String name ? getAttribute(name, PAGE_SCOPE) : null;
            }
            @Override public Object put(String key, Object value) {
                Object previous = getAttribute(key, PAGE_SCOPE);
                setAttribute(key, value, PAGE_SCOPE);
                return previous;
            }
            @Override public Object remove(Object key) {
                if (!(key instanceof String name)) return null;
                Object previous = getAttribute(name, PAGE_SCOPE);
                removeAttribute(name, PAGE_SCOPE);
                return previous;
            }
            @Override public Set<Entry<String, Object>> entrySet() {
                var entries = new LinkedHashMap<String, Object>();
                var names = getAttributeNamesInScope(PAGE_SCOPE);
                while (names.hasMoreElements()) {
                    String name = names.nextElement();
                    entries.put(name, getAttribute(name, PAGE_SCOPE));
                }
                return entries.entrySet();
            }
        };

        @Override
        public Object getValue(ELContext context, Object base, Object property) {
            if (base != null || !(property instanceof String name)) return null;
            if (name.equals("pageContext")) {
                context.setPropertyResolved(true);
                return TagFileJspContext.this;
            }
            if (name.equals("pageScope")) {
                context.setPropertyResolved(true);
                return pageScope;
            }
            if (pageAttributes.containsKey(name)) {
                context.setPropertyResolved(true);
                return pageAttributes.get(name);
            }
            return null;
        }

        @Override
        public Class<?> getType(ELContext context, Object base, Object property) {
            if (base != null || !(property instanceof String name)) return null;
            if (name.equals("pageContext") || name.equals("pageScope") || pageAttributes.containsKey(name)) {
                context.setPropertyResolved(true);
                return Object.class;
            }
            return null;
        }

        @Override
        public void setValue(ELContext context, Object base, Object property, Object value) {
            if (base == null && property instanceof String name && pageAttributes.containsKey(name)) {
                context.setPropertyResolved(true);
                setAttribute(name, value, PAGE_SCOPE);
            }
        }

        @Override
        public boolean isReadOnly(ELContext context, Object base, Object property) {
            if (base == null && property instanceof String name) {
                if (name.equals("pageContext") || name.equals("pageScope")) {
                    context.setPropertyResolved(true);
                    return true;
                }
                if (pageAttributes.containsKey(name)) {
                    context.setPropertyResolved(true);
                    return false;
                }
            }
            return false;
        }

        @Override public Class<?> getCommonPropertyType(ELContext context, Object base) {
            return base == null ? String.class : null;
        }
    }
}
