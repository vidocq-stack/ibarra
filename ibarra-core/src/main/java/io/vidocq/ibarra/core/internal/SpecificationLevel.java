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

/**
 * The specification level Ibarra implements.
 *
 * <p>Scaffold marker: the first real engine class arrives with milestone I1
 * (see PLAN.md). This class only pins the targeted specification so that the
 * reactor builds and has one passing test.
 */
public final class SpecificationLevel {

    /** Official name of the implemented specification. */
    public static final String NAME = "Jakarta Pages";

    /** Implemented specification version. */
    public static final String VERSION = "4.0";

    private SpecificationLevel() {
    }

    /**
     * Human-readable description, e.g. {@code "Jakarta Pages 4.0"}.
     *
     * @return the specification name followed by its version
     */
    public static String describe() {
        return NAME + " " + VERSION;
    }
}
