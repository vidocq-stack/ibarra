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

import io.vidocq.ibarra.spi.PageDescriptor;
import io.vidocq.ibarra.spi.PageIndex;
import jakarta.servlet.http.*;
import jakarta.servlet.jsp.HttpJspPage;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PrecompiledPagesTest {
    @Test
    void listenerMetadataIsImmutableAndDeduplicatedWithoutConstruction() {
        int[] created = {0};
        var listener = new io.vidocq.ibarra.spi.ListenerDescriptor(TestListener.class, () -> { created[0]++; return new TestListener(); });
        PageIndex index = new PageIndex() {
            public List<PageDescriptor> pages() { return List.of(); }
            @Override public List<io.vidocq.ibarra.spi.ListenerDescriptor> listeners() { return List.of(listener, listener); }
        };
        var catalogue = new PrecompiledPages(List.of(index));
        assertEquals(List.of(listener), catalogue.listeners());
        assertEquals(0, created[0]);
        var listeners = catalogue.listeners();
        assertThrows(UnsupportedOperationException.class, listeners::clear);
        assertNotSame(listener.createListener(), listener.createListener());
        var mismatched = new io.vidocq.ibarra.spi.ListenerDescriptor(
                jakarta.servlet.ServletContextListener.class, TestListener::new);
        assertThrows(IllegalArgumentException.class, mismatched::createListener);
        assertTrue(((PageIndex) List::of).listeners().isEmpty());
    }

    public static final class TestListener implements jakarta.servlet.ServletContextListener { }

    @Test
    void pageFactoriesCreateFreshServletsAndLookupDoesNotInstantiateThem() {
        int[] created = {0};
        var descriptor = new PageDescriptor("/index.jsp", () -> { created[0]++; return new TestPage(); });
        var pages = new PrecompiledPages(List.of((PageIndex) () -> List.of(descriptor)));
        assertSame(descriptor, pages.find("/index.jsp"));
        assertNull(pages.find("/missing.jsp"));
        assertEquals(0, created[0]);
        assertNotSame(descriptor.createPage(), descriptor.createPage());
        assertEquals(2, created[0]);
    }

    @Test
    void duplicatePathsAreRejectedInsteadOfDependingOnProviderOrder() {
        PageIndex first = () -> List.of(new PageDescriptor("/index.jsp", TestPage::new));
        PageIndex second = () -> List.of(new PageDescriptor("/index.jsp", TestPage::new));
        var indexes = List.of(first, second);
        var error = assertThrows(IllegalArgumentException.class, () -> new PrecompiledPages(indexes));
        assertTrue(error.getMessage().contains("/index.jsp"));
    }

    @Test
    void descriptorsRejectInvalidPathsAndNullFactories() {
        for (String path : List.of("index.jsp", "/../index.jsp", "/a/../index.jsp", "/a\\b.jsp", "/a.jsp?x=1", "/a.jsp#x"))
            assertThrows(IllegalArgumentException.class, () -> new PageDescriptor(path, TestPage::new), path);
        assertThrows(NullPointerException.class, () -> new PageDescriptor("/index.jsp", null));
        var nullFactory = new PageDescriptor("/index.jsp", () -> null);
        assertThrows(NullPointerException.class, nullFactory::createPage);
    }

    public static final class TestPage extends HttpServlet implements HttpJspPage {
        public void jspInit() { /* Fixture page: nothing to initialize. */ }
        public void jspDestroy() { /* Fixture page: nothing to release. */ }
        public void _jspService(HttpServletRequest request, HttpServletResponse response) { /* Fixture page: never rendered by this test. */ }
    }
}
