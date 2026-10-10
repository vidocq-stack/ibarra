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

/**
 * Ibarra — Jakarta Pages 4.0 implementation (scaffold).
 *
 * <p>Nothing is exported: {@code io.vidocq.ibarra.core.internal} is the page
 * runtime. {@code jakarta.servlet.jsp} brings {@code jakarta.servlet} and
 * {@code jakarta.el} transitively; the Expression Language implementation
 * (Peano by default) is discovered at run time through the standard
 * {@code jakarta.el.ExpressionFactory} service, and the runtime will be plugged
 * into a servlet container through Foy's JSP-engine SPI (see PLAN.md).
 */
module io.vidocq.ibarra.core {
    requires transitive jakarta.servlet.jsp;
}
