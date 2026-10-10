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
package io.vidocq.ibarra.maven;

import io.vidocq.ibarra.compiler.*;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;

/** Compiles pages after application classes, registering the generated module service. */
@Mojo(name = "precompile", defaultPhase = LifecyclePhase.PROCESS_CLASSES,
        requiresDependencyResolution = ResolutionScope.COMPILE, threadSafe = true)
public final class PrecompileMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;
    @Parameter(defaultValue = "${project.basedir}/src/main/webapp", property = "ibarra.webSourceDirectory")
    File webSourceDirectory;
    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true, required = true)
    File outputDirectory;
    @Parameter(property = "ibarra.generatedPackage")
    String generatedPackage;
    @Parameter(defaultValue = "false", property = "ibarra.skip")
    boolean skip;

    @Override public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) { getLog().info("Ibarra precompilation skipped"); return; }
        if (!Files.isRegularFile(outputDirectory.toPath().resolve("module-info.class")))
            throw new MojoFailureException("Ibarra requires compiled module-info.class; bind precompile to process-classes");
        try {
            Path output = outputDirectory.toPath().toAbsolutePath().normalize();
            var paths = project.getCompileClasspathElements().stream().map(Path::of).map(path -> path.toAbsolutePath().normalize())
                    .filter(path -> !path.equals(output)).toList();
            var groups = WebXmlProperties.read(webSourceDirectory.toPath().resolve("WEB-INF/web.xml"));
            var result = new PagePrecompiler().precompile(webSourceDirectory.toPath(), output, paths, generatedPackage,
                    new GenerationOptions(groups, Map.of()));
            getLog().info("Precompiled " + result.pagePaths().size() + " pages in module " + result.moduleName());
        } catch (IllegalArgumentException failure) {
            throw new MojoFailureException(failure.getMessage(), failure);
        } catch (Exception failure) {
            throw new MojoExecutionException("Cannot precompile pages: " + failure.getMessage(), failure);
        }
    }
}

