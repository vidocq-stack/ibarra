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
package io.vidocq.ibarra.arquillian;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import javax.lang.model.SourceVersion;
import org.jboss.arquillian.container.spi.ConfigurationException;
import org.jboss.arquillian.container.spi.client.container.ContainerConfiguration;

/**
 * Configuration of the adapter, filled from the {@code arquillian.xml} container properties.
 * The adapter allocates a loopback port independently for each deployment.
 */
public final class PagesConfiguration implements ContainerConfiguration {
    private String applicationLibraries = "";

    /** The {@code applicationLibraries} property: comma-separated names of the library modules deployed with every application. */
    public String getApplicationLibraries() {
        return applicationLibraries;
    }

    public void setApplicationLibraries(String moduleNames) {
        this.applicationLibraries = moduleNames == null ? "" : moduleNames;
    }

    /** The configured library module names, trimmed, blanks dropped, in their configured order. */
    List<String> applicationLibraries() {
        return Arrays.stream(applicationLibraries.split(",")).map(String::trim).filter(name -> !name.isEmpty()).toList();
    }

    /** Refuses a library name that is not a module name, and a library listed twice. */
    @Override
    public void validate() {
        var seen = new HashSet<String>();
        for (String name : applicationLibraries()) {
            if (!SourceVersion.isName(name)) {
                throw new ConfigurationException("applicationLibraries: '" + name + "' is not a module name");
            }
            if (!seen.add(name)) {
                throw new ConfigurationException("applicationLibraries: '" + name + "' is listed twice");
            }
        }
    }
}
