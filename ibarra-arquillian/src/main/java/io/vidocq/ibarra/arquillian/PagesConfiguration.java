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

import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.module.ResolvedModule;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.lang.model.SourceVersion;
import org.jboss.arquillian.container.spi.ConfigurationException;
import org.jboss.arquillian.container.spi.client.container.ContainerConfiguration;

/**
 * Configuration of the adapter, filled from the {@code arquillian.xml} container properties.
 * The adapter allocates a loopback port independently for each deployment.
 */
public final class PagesConfiguration implements ContainerConfiguration {
    private String applicationLibraries = "";
    private String jdkModules = "";
    private String applicationOpens = "";

    /** The {@code applicationLibraries} property: comma-separated names of the library modules deployed with every application. */
    public String getApplicationLibraries() {
        return applicationLibraries;
    }

    public void setApplicationLibraries(String moduleNames) {
        this.applicationLibraries = moduleNames == null ? "" : moduleNames;
    }

    /** The configured library module names, trimmed, blanks dropped, in their configured order. */
    List<String> applicationLibraries() {
        return names(applicationLibraries);
    }

    /** The {@code jdkModules} property: comma-separated names of the JDK modules added to the server boot layer. */
    public String getJdkModules() {
        return jdkModules;
    }

    public void setJdkModules(String moduleNames) {
        this.jdkModules = moduleNames == null ? "" : moduleNames;
    }

    /** The configured JDK module names, trimmed, blanks dropped, in their configured order. */
    List<String> jdkModules() {
        return names(jdkModules);
    }

    /** The {@code applicationOpens} property: comma-separated names of the application packages opened to every module. */
    public String getApplicationOpens() {
        return applicationOpens;
    }

    public void setApplicationOpens(String packageNames) {
        this.applicationOpens = packageNames == null ? "" : packageNames;
    }

    /** The configured package names to open, trimmed, blanks dropped, in their configured order. */
    List<String> applicationOpens() {
        return names(applicationOpens);
    }

    private static List<String> names(String list) {
        return Arrays.stream(list.split(",")).map(String::trim).filter(name -> !name.isEmpty()).toList();
    }

    /**
     * Refuses a library name that is not a module name or is listed twice, a {@code jdkModules} entry
     * that is not a JDK module, and an {@code applicationOpens} entry that is not a package name.
     */
    @Override
    public void validate() {
        validateLibraries();
        var jdk = ModuleFinder.ofSystem();
        for (String name : jdkModules()) {
            if (jdk.find(name).isEmpty()) {
                throw new ConfigurationException("jdkModules: '" + name + "' is not a JDK module");
            }
            rejectBuildTooling(jdk, name);
        }
        for (String name : applicationOpens()) {
            if (!SourceVersion.isName(name)) {
                throw new ConfigurationException("applicationOpens: '" + name + "' is not a package name");
            }
        }
    }

    /**
     * Refuses a JDK module that is build tooling or that requires it: {@code --limit-modules} puts the whole
     * requires closure in the server boot layer, where the host refuses build tooling.
     */
    private static void rejectBuildTooling(ModuleFinder jdk, String name) {
        var tooling = Configuration.empty().resolve(jdk, ModuleFinder.of(), Set.of(name)).modules().stream()
                .map(ResolvedModule::name).filter(PagesContainer.BUILD_TOOLING::contains).sorted().findFirst();
        if (tooling.isPresent()) {
            String subject = tooling.get().equals(name) ? "'" + name + "'" : "'" + name + "' (it requires " + tooling.get() + ")";
            throw new ConfigurationException("jdkModules: " + subject + PagesContainer.BUILD_TOOLING_REFUSAL);
        }
    }

    private void validateLibraries() {
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
