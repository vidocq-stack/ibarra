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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable syntax nodes shared by standard JSP and XML document syntax. */
public sealed interface PageNode {
    /** Start in the original source, including the opening delimiter of scripts and expressions. */
    SourceLocation location();

    record Text(String value, SourceLocation location) implements PageNode {
        public Text { Objects.requireNonNull(value); Objects.requireNonNull(location); }
    }

    /** Uninterpreted tag-dependent body data; quoting, EL and directives stay literal. */
    record RawText(String value, SourceLocation location) implements PageNode {
        public RawText { Objects.requireNonNull(value); Objects.requireNonNull(location); }
    }

    record Expression(String code, boolean deferred, SourceLocation location) implements PageNode {
        public Expression { Objects.requireNonNull(code); Objects.requireNonNull(location); }
    }

    enum ScriptKind { DECLARATION, SCRIPTLET, EXPRESSION }

    record Script(ScriptKind kind, String code, SourceLocation location) implements PageNode {
        public Script { Objects.requireNonNull(kind); Objects.requireNonNull(code); Objects.requireNonNull(location); }
    }

    record Directive(String name, Map<String, String> attributes, SourceLocation location) implements PageNode {
        public Directive {
            Objects.requireNonNull(name); Objects.requireNonNull(location);
            attributes = ordered(attributes);
        }
    }

    record Element(String name, String namespace, Map<String, String> attributes,
            List<PageNode> children, boolean selfClosing, SourceLocation location) implements PageNode {
        public Element {
            Objects.requireNonNull(name); Objects.requireNonNull(namespace); Objects.requireNonNull(location);
            attributes = ordered(attributes);
            children = List.copyOf(children);
        }
    }

    private static Map<String, String> ordered(Map<String, String> attributes) {
        var copy = new LinkedHashMap<>(attributes);
        copy.forEach((name, value) -> { Objects.requireNonNull(name); Objects.requireNonNull(value); });
        return Collections.unmodifiableMap(copy);
    }
}

