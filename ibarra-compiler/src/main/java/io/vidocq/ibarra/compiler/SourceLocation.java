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
package io.vidocq.ibarra.compiler;

import java.util.Objects;

/** One-based line and column in the original source; columns count UTF-16 code units. */
public record SourceLocation(String source, int line, int column) implements java.io.Serializable {
    public SourceLocation {
        Objects.requireNonNull(source);
        if (line < 1 || column < 1) throw new IllegalArgumentException("Location must be one-based");
    }

    /** Advances through undecoded source text, treating CRLF as one newline. */
    public SourceLocation advance(String text) {
        int row = line;
        int col = column;
        boolean afterCarriageReturn = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' && afterCarriageReturn) {
                afterCarriageReturn = false;
            } else if (c == '\r' || c == '\n') {
                row++;
                col = 1;
                afterCarriageReturn = c == '\r';
            } else {
                col++;
                afterCarriageReturn = false;
            }
        }
        return new SourceLocation(source, row, col);
    }

    @Override public String toString() { return source + ":" + line + ":" + column; }
}

