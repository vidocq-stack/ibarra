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

import java.util.*;
import javax.lang.model.SourceVersion;

/** Immutable expected return/parameter types; the documented method name is not a target. */
public record DeferredMethodSignature(String returnType, List<String> parameters) {
    public DeferredMethodSignature { Objects.requireNonNull(returnType); parameters = List.copyOf(parameters); }

    static DeferredMethodSignature parse(String source, SourceLocation at) {
        var matcher = java.util.regex.Pattern.compile("\\s*(.+?)\\s+([\\p{L}_$][\\p{L}\\p{N}_$]*)\\s*\\((.*?)\\)\\s*").matcher(source);
        if (!matcher.matches() || SourceVersion.isKeyword(matcher.group(2)))
            throw new PageParseException(at, "Invalid deferred method signature: " + source);
        String result = matcher.group(1).replaceAll("\\s+", "");
        List<String> parameters = matcher.group(3).isBlank() ? List.of()
                : Arrays.stream(matcher.group(3).split(",", -1)).map(value -> value.replaceAll("\\s+", "")).toList();
        if (!validType(result, true) || parameters.stream().anyMatch(type -> !validType(type, false)))
            throw new PageParseException(at, "Invalid deferred method signature types: " + source);
        return new DeferredMethodSignature(result, parameters);
    }

    static boolean validType(String type, boolean allowVoid) {
        if (allowVoid && type.equals("void")) return true;
        while (type.endsWith("[]")) type = type.substring(0, type.length() - 2);
        return Set.of("boolean", "byte", "short", "int", "long", "float", "double", "char").contains(type)
                || SourceVersion.isName(type);
    }
}
