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
import jakarta.el.*;
import jakarta.servlet.jsp.PageContext;
import io.vidocq.ibarra.spi.PageOptions;
import io.vidocq.ibarra.spi.TagFileJspContext;
import org.junit.jupiter.api.Test;

/** Pages 4.0 sections 2.10 and 3.3.4: only unresolved identifiers use this policy. */
class PagePolicyTest {
    @Test void generatedImportsAreAvailableToListenersAndRejectRuntimeMutation() throws Exception {
        var web = new PageRuntimeTest.Web(); var application = new IbarraApplicationContext();
        application.addELContextListener(event -> assertEquals(java.util.Map.Entry.class, event.getELContext().getImportHandler().resolveClass("Entry")));
        var servlet = new ImportedPage(); servlet.init(web.config);
        var page = new IbarraPageContext(application);
        page.initialize(servlet, web.request, web.response, null, false, 64, true);
        var imports = page.getELContext().getImportHandler();
        assertEquals(java.util.Map.Entry.class, imports.resolveClass("Entry"));
        assertNull(imports.resolveClass("Unknown"));
        assertThrows(ELException.class, () -> imports.importClass("java.lang.String"));
        assertThrows(ELException.class, () -> imports.importPackage("java.util"));
        assertThrows(ELException.class, () -> imports.importStatic("java.lang.Math.PI"));
    }
    private static final class ImportedPage extends jakarta.servlet.http.HttpServlet implements jakarta.servlet.jsp.HttpJspPage, PageOptions {
        public boolean usesStaticELImports() { return true; }
        public Class<?> resolveELClass(String name) { return "Entry".equals(name) ? java.util.Map.Entry.class : null; }
        public void jspInit() {} public void jspDestroy() {}
        public void _jspService(jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {}
    }
    @Test
    void strictAndPermissivePagesShareTheApplicationWithoutSharingPolicy() throws Exception {
        var web = new PageRuntimeTest.Web();
        var application = new IbarraApplicationContext();
        var strict = open(web, application, true);
        var permissive = open(web, application, false);
        var factory = ExpressionFactory.newInstance();
        assertThrows(PropertyNotFoundException.class, () -> evaluate(factory, strict, "${missing}"));
        assertEquals("", evaluate(factory, permissive, "${missing}"));
        assertThrows(PropertyNotFoundException.class, () -> evaluate(factory, strict, "${missing}"));
    }

    @Test
    void tagFileELContextSeesWrapperPageAttributesAndDelegatesOtherScopes() throws Exception {
        var web = new PageRuntimeTest.Web();
        web.request.setAttribute("requestValue", "request");
        var page = open(web, new IbarraApplicationContext(), false);
        var tag = new TagFileJspContext(page);
        tag.setAttribute("tagValue", "tag", PageContext.PAGE_SCOPE);
        var factory = ExpressionFactory.newInstance();
        var context = tag.getELContext();

        assertSame(tag, context.getContext(PageContext.class));
        assertSame(tag, context.getContext(jakarta.servlet.jsp.JspContext.class));
        assertEquals("tag", evaluate(factory, context, "${tagValue}"));
        assertEquals("tag", evaluate(factory, context, "${pageScope.tagValue}"));
        assertEquals("request", evaluate(factory, context, "${requestValue}"));
        assertEquals("request", evaluate(factory, context, "${requestScope.requestValue}"));
    }

    @Test
    void tagFileStrictELPolicyOverridesPermissiveParentAndFalseInheritsParentPolicy() throws Exception {
        var web = new PageRuntimeTest.Web();
        var application = new IbarraApplicationContext();
        var factory = ExpressionFactory.newInstance();
        var permissiveParent = open(web, application, false);

        assertThrows(PropertyNotFoundException.class,
                () -> evaluate(factory, new TagFileJspContext(permissiveParent, true).getELContext(), "${missing}"));
        assertEquals("", evaluate(factory, new TagFileJspContext(permissiveParent, false).getELContext(), "${missing}"));

        var strictParent = open(web, application, true);
        assertThrows(PropertyNotFoundException.class,
                () -> evaluate(factory, new TagFileJspContext(strictParent, false).getELContext(), "${missing}"));
    }

    @Test
    void strictPolicyIsAvailableDuringContextCreatedCallbacks() throws Exception {
        var web = new PageRuntimeTest.Web();
        var application = new IbarraApplicationContext();
        var factory = ExpressionFactory.newInstance();
        application.addELContextListener(event -> {
            var context = event.getELContext();
            assertThrows(PropertyNotFoundException.class,
                    () -> factory.createValueExpression(context, "${missing}", Object.class).getValue(context));
        });
        assertNotNull(open(web, application, true).getELContext());
    }

    @Test
    void declaredNullValuesAndMissingMapKeysAreNotUnknownIdentifiers() throws Exception {
        var web = new PageRuntimeTest.Web();
        web.request.setAttribute("data", java.util.Map.of());
        var application = new IbarraApplicationContext();
        application.addELResolver(new ELResolver() {
            public Object getValue(ELContext context, Object base, Object property) {
                if (base == null && "nullable".equals(property)) context.setPropertyResolved(true);
                return null;
            }
            public Class<?> getType(ELContext context, Object base, Object property) { return null; }
            public void setValue(ELContext context, Object base, Object property, Object value) { }
            public boolean isReadOnly(ELContext context, Object base, Object property) { return true; }
            public Class<?> getCommonPropertyType(ELContext context, Object base) { return null; }
        });
        var page = open(web, application, true);
        var factory = ExpressionFactory.newInstance();
        assertEquals("", evaluate(factory, page, "${nullable}"));
        assertEquals("", evaluate(factory, page, "${data.absent}"));
        assertEquals("", evaluate(factory, page, "${null}"));
        page.setAttribute("known", "value", PageContext.REQUEST_SCOPE);
        assertEquals("value", evaluate(factory, page, "${known}"));
    }

    @Test
    void assignmentCanCreateAnAttributeAndReleaseDropsTheStrictContext() throws Exception {
        var web = new PageRuntimeTest.Web();
        var application = new IbarraApplicationContext();
        var page = open(web, application, true);
        var context = page.getELContext();
        var factory = ExpressionFactory.newInstance();
        factory.createValueExpression(context, "${created}", Object.class).setValue(context, "new");
        assertEquals("new", page.getAttribute("created"));
        page.release();
        page.initialize(web.page, web.request, web.response, null, false, 64, true);
        assertNotSame(context, page.getELContext());
        assertEquals("", evaluate(factory, page, "${missing}"));
    }

    private Object evaluate(ExpressionFactory factory, IbarraPageContext page, String text) {
        return evaluate(factory, page.getELContext(), text);
    }

    private Object evaluate(ExpressionFactory factory, ELContext context, String text) {
        return factory.createValueExpression(context, text, String.class).getValue(context);
    }

    private IbarraPageContext open(PageRuntimeTest.Web web, IbarraApplicationContext application, boolean strict) throws Exception {
        var servlet = new PolicyPage(strict);
        servlet.init(web.config);
        var page = new IbarraPageContext(application);
        page.initialize(servlet, web.request, web.response, null, false, 64, true);
        return page;
    }

    static final class PolicyPage extends jakarta.servlet.http.HttpServlet implements PageOptions {
        private final boolean strict;
        PolicyPage(boolean strict) { this.strict = strict; }
        @Override public boolean errorOnELNotFound() { return strict; }
    }
}

