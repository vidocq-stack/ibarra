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
package io.vidocq.ibarra.tck.fixtures;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Locale;

/** Application-owned header/body transformation with a writer cached before forwarding. */
public class ForwardResponseFilter implements Filter {
    @Override public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        chain.doFilter(request, new WrappedResponse((HttpServletResponse) response, new BodyWriter(response.getWriter())));
    }

    public static final class BodyWriter extends PrintWriter {
        public BodyWriter(PrintWriter delegate) { super(delegate); }
        @Override public void write(String value, int offset, int length) {
            String upper = value.substring(offset, offset + length).toUpperCase(Locale.ROOT);
            super.write(upper, 0, upper.length());
        }
        @Override public void write(char[] value, int offset, int length) {
            String upper = new String(value, offset, length).toUpperCase(Locale.ROOT);
            super.write(upper, 0, upper.length());
        }
        @Override public void write(int value) { super.write(Character.toUpperCase(value)); }
    }

    public static final class WrappedResponse extends HttpServletResponseWrapper {
        private final PrintWriter writer;
        public WrappedResponse(HttpServletResponse response, PrintWriter writer) { super(response); this.writer = writer; }
        @Override public PrintWriter getWriter() { return writer; }
        @Override public void setHeader(String name, String value) { super.setHeader(name, value.toUpperCase(Locale.ROOT)); }
    }
}
