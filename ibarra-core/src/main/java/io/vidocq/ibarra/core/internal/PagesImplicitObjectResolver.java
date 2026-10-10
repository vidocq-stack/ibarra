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

import jakarta.el.*;
import jakarta.servlet.http.*;
import jakarta.servlet.jsp.PageContext;
import java.util.*;

/** Request-owned views of the Pages implicit objects. */
final class PagesImplicitObjectResolver extends ELResolver {
    private static final Set<String> NAMES = Set.of("pageContext", "pageScope", "requestScope", "sessionScope",
            "applicationScope", "param", "paramValues", "header", "headerValues", "cookie", "initParam");
    private final PageContext page;

    PagesImplicitObjectResolver(PageContext page) { this.page = page; }

    @Override
    public Object getValue(ELContext context, Object base, Object property) {
        if (base != null || !(property instanceof String name) || !NAMES.contains(name)) return null;
        context.setPropertyResolved(true);
        return switch (name) {
            case "pageContext" -> page;
            case "pageScope" -> new ScopeMap(PageContext.PAGE_SCOPE);
            case "requestScope" -> new ScopeMap(PageContext.REQUEST_SCOPE);
            case "sessionScope" -> new ScopeMap(PageContext.SESSION_SCOPE);
            case "applicationScope" -> new ScopeMap(PageContext.APPLICATION_SCOPE);
            case "paramValues" -> Collections.unmodifiableMap(page.getRequest().getParameterMap());
            case "param" -> {
                var values = new LinkedHashMap<String, String>();
                page.getRequest().getParameterMap().forEach((key, items) -> {
                    if (items.length > 0) values.put(key, items[0]);
                });
                yield Collections.unmodifiableMap(values);
            }
            case "header", "headerValues" -> {
                var values = new TreeMap<String, Object>(String.CASE_INSENSITIVE_ORDER);
                if (page.getRequest() instanceof HttpServletRequest request) {
                    var names = request.getHeaderNames();
                    while (names.hasMoreElements()) {
                        var header = names.nextElement();
                        values.put(header, name.equals("header") ? request.getHeader(header)
                                : Collections.list(request.getHeaders(header)).toArray(String[]::new));
                    }
                }
                yield Collections.unmodifiableMap(values);
            }
            case "cookie" -> {
                var values = new LinkedHashMap<String, Cookie>();
                if (page.getRequest() instanceof HttpServletRequest request) {
                    var cookies = request.getCookies();
                    if (cookies != null) for (var cookie : cookies) values.putIfAbsent(cookie.getName(), cookie);
                }
                yield Collections.unmodifiableMap(values);
            }
            case "initParam" -> {
                var values = new LinkedHashMap<String, String>();
                var names = page.getServletContext().getInitParameterNames();
                while (names.hasMoreElements()) {
                    var key = names.nextElement(); values.put(key, page.getServletContext().getInitParameter(key));
                }
                yield Collections.unmodifiableMap(values);
            }
            default -> throw new AssertionError(name);
        };
    }

    private boolean resolves(ELContext context, Object base, Object property) {
        boolean found = base == null && property instanceof String name && NAMES.contains(name);
        if (found) context.setPropertyResolved(true);
        return found;
    }
    @Override public Class<?> getType(ELContext context, Object base, Object property) { resolves(context, base, property); return null; }
    @Override public boolean isReadOnly(ELContext context, Object base, Object property) { return resolves(context, base, property); }
    @Override
    public void setValue(ELContext context, Object base, Object property, Object value) {
        if (resolves(context, base, property)) throw new PropertyNotWritableException("Pages implicit objects are read-only: " + property);
    }
    @Override public Class<?> getCommonPropertyType(ELContext context, Object base) { return base == null ? String.class : null; }

    private final class ScopeMap extends AbstractMap<String, Object> {
        private final int scope;
        ScopeMap(int scope) { this.scope = scope; }
        private boolean available() { return scope != PageContext.SESSION_SCOPE || page.getSession() != null; }
        @Override public Object get(Object key) { return available() && key instanceof String name ? page.getAttribute(name, scope) : null; }
        @Override public Object put(String key, Object value) {
            var previous = get(key); page.setAttribute(key, value, scope); return previous;
        }
        @Override public Object remove(Object key) {
            var previous = get(key);
            if (available() && key instanceof String name) page.removeAttribute(name, scope);
            return previous;
        }
        @Override public void clear() {
            for (var entry : entrySet()) page.removeAttribute(entry.getKey(), scope);
        }
        @Override public Set<Entry<String, Object>> entrySet() {
            if (!available()) return Set.of();
            var entries = new LinkedHashSet<Entry<String, Object>>();
            var names = page.getAttributeNamesInScope(scope);
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                var value = page.getAttribute(name, scope);
                if (value != null) entries.add(Map.entry(name, value));
            }
            return Collections.unmodifiableSet(entries);
        }
    }
}

