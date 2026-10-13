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
package io.vidocq.ibarra.core.binding;

import jakarta.servlet.ServletContext;
import jakarta.servlet.jsp.HttpJspPage;

/** Narrow container-binding facade; implementations and factory state stay internal. */
public interface PageApplication extends AutoCloseable {
    static PageApplication open(ServletContext context, ModuleLayer applicationLayer) {
        return io.vidocq.ibarra.core.internal.IbarraPageApplication.open(context, applicationLayer);
    }
    /** Installs the container's typed dispatch transition before any page contexts are served. */
    static PageApplication open(ServletContext context, ModuleLayer applicationLayer, PageForwardDispatcher dispatcher) {
        return io.vidocq.ibarra.core.internal.IbarraPageApplication.open(context, applicationLayer, dispatcher);
    }
    /** Creates an uninitialized servlet, or returns null for a missing precompiled page. */
    HttpJspPage createPage(String path);
    /** Checks the finite precompiled catalogue without constructing a servlet. */
    boolean containsPage(String path);
    /** Immutable TLD metadata; the container owns listener construction and lifecycle. */
    default java.util.List<io.vidocq.ibarra.spi.ListenerDescriptor> listeners() { return java.util.List.of(); }
    /** Releases application state after the container has drained all requests. */
    @Override void close();
}
