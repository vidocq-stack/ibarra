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
        var error = assertThrows(IllegalArgumentException.class, () -> new PrecompiledPages(List.of(first, second)));
        assertTrue(error.getMessage().contains("/index.jsp"));
    }

    @Test
    void descriptorsRejectInvalidPathsAndNullFactories() {
        for (String path : List.of("index.jsp", "/../index.jsp", "/a/../index.jsp", "/a\\b.jsp", "/a.jsp?x=1", "/a.jsp#x"))
            assertThrows(IllegalArgumentException.class, () -> new PageDescriptor(path, TestPage::new), path);
        assertThrows(NullPointerException.class, () -> new PageDescriptor("/index.jsp", null));
        assertThrows(NullPointerException.class, () -> new PageDescriptor("/index.jsp", () -> null).createPage());
    }

    public static final class TestPage extends HttpServlet implements HttpJspPage {
        public void jspInit() { }
        public void jspDestroy() { }
        public void _jspService(HttpServletRequest request, HttpServletResponse response) { }
    }
}
