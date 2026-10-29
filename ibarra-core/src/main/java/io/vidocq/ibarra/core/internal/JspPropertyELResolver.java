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

import java.util.Objects;
import jakarta.el.ELContext;
import jakarta.el.ELResolver;
import jakarta.el.PropertyNotWritableException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.jsp.PageContext;

/** Resolves standard JSP and Servlet properties through their public APIs. */
final class JspPropertyELResolver extends ELResolver {
    private static final String CONTEXT = "context";

    private static boolean resolves(Object base, Object property) {
        return property instanceof String name
                && ((base instanceof PageContext && name.equals("request"))
                || (base instanceof HttpServletRequest && name.equals("contextPath")));
    }

    @Override
    public Object getValue(ELContext context, Object base, Object property) {
        Objects.requireNonNull(context, CONTEXT);
        if (!resolves(base, property)) return null;
        context.setPropertyResolved(true);
        if (base instanceof PageContext page) return page.getRequest();
        return ((HttpServletRequest) base).getContextPath();
    }

    @Override
    public Class<?> getType(ELContext context, Object base, Object property) {
        Objects.requireNonNull(context, CONTEXT);
        if (!resolves(base, property)) return null;
        context.setPropertyResolved(true);
        return base instanceof PageContext ? ServletRequest.class : String.class;
    }

    @Override
    public void setValue(ELContext context, Object base, Object property, Object value) {
        Objects.requireNonNull(context, CONTEXT);
        if (resolves(base, property)) {
            context.setPropertyResolved(true);
            throw new PropertyNotWritableException("JSP and Servlet properties are read-only: " + property);
        }
    }

    @Override
    public boolean isReadOnly(ELContext context, Object base, Object property) {
        Objects.requireNonNull(context, CONTEXT);
        if (!resolves(base, property)) return false;
        context.setPropertyResolved(true);
        return true;
    }

    @Override
    public Class<?> getCommonPropertyType(ELContext context, Object base) {
        return base instanceof PageContext || base instanceof HttpServletRequest ? String.class : null;
    }
}
