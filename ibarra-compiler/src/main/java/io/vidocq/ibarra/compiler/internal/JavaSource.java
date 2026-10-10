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
import java.util.LinkedHashMap;
import java.util.Map;

/** Text emission with line mappings; Java escapes never use Unicode preprocessing. */
public final class JavaSource {
    private final StringBuilder text = new StringBuilder();
    private final Map<Integer, SourceLocation> locations = new LinkedHashMap<>();
    private int line = 1;
    public void add(String value, SourceLocation location) {
        if (location != null) locations.put(line, location);
        text.append(value);
        for (int i = 0; i < value.length(); i++)
            if (value.charAt(i) == '\n') {
                line++;
                if (location != null) locations.put(line, location.advance(value.substring(0, i + 1)));
            }
    }
    public void add(String value) { add(value, null); }
    public String text() { return text.toString(); }
    public Map<Integer, SourceLocation> locations() { return Map.copyOf(locations); }
    public void add(JavaSource source) {
        source.locations.forEach((key, location) -> locations.put(line + key - 1, location));
        add(source.text());
    }
    public static String quote(String value) {
        var out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\' -> out.append("\\\\"); case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
                default -> { if (c < 32) out.append(String.format("\\%03o", (int) c)); else out.append(c); }
            }
        }
        return out.append('"').toString();
    }
}

