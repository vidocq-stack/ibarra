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
package io.vidocq.ibarra.compiler.internal;

import io.vidocq.ibarra.compiler.SourceLocation;
import java.util.ArrayList;
import java.util.Arrays;

/** Maps original offsets without repeatedly scanning the complete source. */
public final class SourceDocument {
    public final String name;
    public final String text;
    private final int[] lines;

    public SourceDocument(String name, String text) {
        this.name = name;
        this.text = text;
        var starts = new ArrayList<Integer>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                starts.add(i + 1);
            }
        }
        lines = starts.stream().mapToInt(Integer::intValue).toArray();
    }

    public SourceLocation at(int offset) {
        offset = Math.max(0, Math.min(offset, text.length()));
        int line = Arrays.binarySearch(lines, offset);
        if (line < 0) line = -line - 2;
        return new SourceLocation(name, line + 1, offset - lines[line] + 1);
    }
}

