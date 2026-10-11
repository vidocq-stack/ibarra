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
package io.vidocq.ibarra.example;

import jakarta.el.MethodExpression;
import jakarta.el.ValueExpression;
import jakarta.servlet.jsp.tagext.SimpleTagSupport;
import java.io.IOException;

/** Demonstrates typed deferred values and literal methods without runtime parsing. */
public final class DeferredGreeting extends SimpleTagSupport {
    private ValueExpression target;
    private MethodExpression title;
    /** Receives the value expression without evaluating it. */
    public void setTarget(ValueExpression target) { this.target = target; }
    /** Receives the compiled method expression. */
    public void setTitle(MethodExpression title) { this.title = title; }
    @Override public void doTag() throws IOException {
        var context = getJspContext().getELContext();
        getJspContext().getOut().write(title.invoke(context, new Object[0]) + ", " + target.getValue(context));
    }
}
