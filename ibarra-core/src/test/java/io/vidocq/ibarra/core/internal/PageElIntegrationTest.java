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

import static org.junit.jupiter.api.Assertions.*;
import jakarta.el.ExpressionFactory;
import org.junit.jupiter.api.Test;

class PageElIntegrationTest {
    @Test
    void peanoResolvesScopeAndParameterImplicitObjects() throws Exception {
        var web = new PageRuntimeTest.Web();
        web.request.setAttribute("user", java.util.Map.of("name", "Ada"));
        web.request.parameters.put("q", new String[]{"first", "second"});
        var page = web.open(false, null);
        var factory = ExpressionFactory.newInstance();
        assertEquals("io.vidocq.peano.core", factory.getClass().getModule().getName());
        var context = page.getELContext();
        assertEquals("Ada:first:second", factory.createValueExpression(context,
                "${requestScope.user.name}:${param.q}:${paramValues.q[1]}", String.class).getValue(context));
        var value = factory.createValueExpression(context, "${requestScope.updated}", Object.class);
        value.setValue(context, 42);
        assertEquals(42, web.request.getAttribute("updated"));
    }

    @Test
    void implicitObjectsAreReadOnlyButTheirScopeMapsAreWritable() throws Exception {
        var web = new PageRuntimeTest.Web();
        var page = web.open(false, null);
        var context = page.getELContext();
        var factory = ExpressionFactory.newInstance();
        assertThrows(jakarta.el.PropertyNotWritableException.class,
                () -> factory.createValueExpression(context, "${requestScope}", Object.class).setValue(context, java.util.Map.of()));
        assertEquals("", factory.createValueExpression(context, "${missing}", String.class).getValue(context));
        assertEquals(true, factory.createValueExpression(context, "${empty sessionScope}", Boolean.class).getValue(context));
    }

    @Test
    void headersCookiesAndApplicationInitParametersUseTheirServletSemantics() throws Exception {
        var web = new PageRuntimeTest.Web();
        web.request.headers.put("X-Name", "Ada");
        web.request.cookies = new jakarta.servlet.http.Cookie[]{new jakarta.servlet.http.Cookie("sid", "123")};
        web.context.initParameters.put("mode", "test");
        var page = web.open(false, null);
        var context = page.getELContext();
        var factory = ExpressionFactory.newInstance();
        assertEquals("Ada:Ada:123:test", factory.createValueExpression(context,
                "${header['x-name']}:${headerValues['X-NAME'][0]}:${cookie.sid.value}:${initParam.mode}", String.class).getValue(context));
    }
}

