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

import java.beans.Introspector;

/**
 * The {@code java.beans} introspection caches that page expressions fill.
 *
 * <p>The API {@code BeanELResolver} introspects every bean class it reads and the JDK keeps the result for the life of
 * the JVM, in a cache per thread group: each bean class of an application layer {@code vidocq dev} discards stays in
 * it, and so does the layer's class loader (BUG-20261102-02). The resolver cannot be asked to forget a class, so the
 * caches are flushed when the page application of a layer closes.
 */
final class IntrospectionCaches {
    private IntrospectionCaches() {
    }

    /**
     * Flushes the introspection caches of the calling thread's group and of the group of the virtual threads, which
     * serve the requests and so fill the caches; the thread that closes an application is usually a platform one.
     */
    static void flush() {
        Introspector.flushCaches();
        if (Thread.currentThread().isVirtual()) return;
        Thread worker = Thread.ofVirtual().unstarted(Introspector::flushCaches);
        worker.start();
        try {
            worker.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
