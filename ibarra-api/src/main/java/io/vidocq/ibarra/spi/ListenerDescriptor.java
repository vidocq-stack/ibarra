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

package io.vidocq.ibarra.spi;

import java.util.EventListener;
import java.util.Objects;
import java.util.function.Supplier;

/** Immutable constructor factory for one TLD listener; construction belongs to the container. */
public record ListenerDescriptor(Class<? extends EventListener> type, Supplier<? extends EventListener> factory) {
    public ListenerDescriptor { Objects.requireNonNull(type, "type"); Objects.requireNonNull(factory, "factory"); }
    public EventListener createListener() {
        var listener = Objects.requireNonNull(factory.get(), "Listener factory returned null");
        if (listener.getClass() != type) throw new IllegalArgumentException("Listener factory must return its exact declared class");
        return listener;
    }
}
