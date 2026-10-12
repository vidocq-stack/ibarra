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
public final class PropertyBean extends PropertyParent implements PropertyView {
    private int count;
    public String getName() {
        var module = getClass().getModule();
        if (!module.isNamed() || module.isExported(getClass().getPackageName()) || module.isOpen(getClass().getPackageName()))
            throw new AssertionError("Bean package must remain private");
        return "private";
    }
    public int getCount() { return count; }
    public void setCount(int value) { count = value; }
    public void setReadOnly(Integer value) { throw new AssertionError("Incompatible setter selected"); }
    public boolean isActive() { return true; }
    public boolean getActive() { return false; }
    public String getEmpty() { return null; }
}
