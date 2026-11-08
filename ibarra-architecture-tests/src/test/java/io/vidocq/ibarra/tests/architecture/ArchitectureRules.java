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
package io.vidocq.ibarra.tests.architecture;

import java.util.Map;

import io.vidocq.archrules.Jdk;
import io.vidocq.archrules.Layer;
import io.vidocq.archrules.Packages;
import io.vidocq.archrules.PlatformThreads;
import io.vidocq.archrules.VidocqArchRules;

import com.tngtech.archunit.lang.ArchRule;

/**
 * Ibarra's own rules, which no scanner knows, as ArchUnit rules (Vidocq/vidocq#289). They come from the shared
 * {@code vidocq-archrules} library of {@code vidocq-parent}, set to Ibarra's packages; each names the document it
 * comes from. A rule holds for whatever classes it is given: {@link ArchitectureRulesTest} gives it the shipped
 * classes, {@link ArchitectureRulesSelfTest} fixtures that break it.
 *
 * <p>The root package of each shipped module: {@code io.vidocq.ibarra.spi} (ibarra-api),
 * {@code io.vidocq.ibarra.core} (ibarra-core), {@code io.vidocq.ibarra.compiler} (ibarra-compiler) and
 * {@code io.vidocq.ibarra.foy} (ibarra-foy). The Maven plugin and the Arquillian adapter are build-machine and
 * test tooling (AGENTS.md, constraint 10) and are not covered.
 */
final class ArchitectureRules {

    private static final String AGENTS_CONSTRAINTS = "AGENTS.md, Architecture Constraints Not to Violate";

    private static final String[] API = {"io.vidocq.ibarra.spi.."};

    private static final String[] CORE = {"io.vidocq.ibarra.core.."};

    private static final String[] COMPILER = {"io.vidocq.ibarra.compiler.."};

    private static final String[] FOY = {"io.vidocq.ibarra.foy.."};

    /** ibarra-api: the Pages, Servlet and EL APIs it brings, nothing else (module-info.java). */
    static final ArchRule API_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            API,
            Packages.of(Jdk.RUNTIME, "jakarta.servlet..", "jakarta.el..", "io.vidocq.ibarra.spi.."),
            AGENTS_CONSTRAINTS + ", 1; module-info.java of ibarra-api");

    /**
     * Constraint 1: ibarra-core depends on ibarra-api and the Jakarta Pages API (with the Servlet and EL APIs it
     * brings). The EL implementation is obtained through {@code ExpressionFactory.newInstance()}, so no Peano class.
     * {@code java.beans} is allowed as a JDK package: its module descriptor reads {@code java.desktop} to flush the
     * introspection caches that the API's {@code BeanELResolver} fills (IntrospectionCaches, IBARRA-158).
     */
    static final ArchRule CORE_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            CORE,
            Packages.of(
                    Jdk.RUNTIME,
                    "jakarta.servlet..",
                    "jakarta.el..",
                    "io.vidocq.ibarra.spi..",
                    "io.vidocq.ibarra.core.."),
            AGENTS_CONSTRAINTS + ", 1; module-info.java of ibarra-core");

    /**
     * ibarra-compiler: the build-time page compiler, on the Class-File API, the XML and compiler APIs, ibarra-api
     * and Peano's compiler (module-info.java; constraint 9, EL compiled through Peano at build time).
     */
    static final ArchRule COMPILER_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            COMPILER,
            Packages.of(
                    Jdk.RUNTIME,
                    "javax.lang.model..",
                    "javax.annotation.processing..",
                    "javax.tools..",
                    "jakarta.servlet..",
                    "jakarta.el..",
                    "io.vidocq.peano.compiler..",
                    "io.vidocq.ibarra.spi..",
                    "io.vidocq.ibarra.compiler.."),
            AGENTS_CONSTRAINTS + ", 3 and 9; module-info.java of ibarra-compiler");

    /**
     * Constraint 2: only ibarra-foy depends on Foy. It reads ibarra-api, the Servlet API, Foy's JSP-engine SPI and
     * the one package of ibarra-core exported to it, the binding facade (module-info.java of ibarra-core).
     */
    static final ArchRule FOY_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            FOY,
            Packages.of(
                    Jdk.RUNTIME,
                    "jakarta.servlet..",
                    "io.vidocq.foy.spi..",
                    "io.vidocq.ibarra.spi..",
                    "io.vidocq.ibarra.core.binding..",
                    "io.vidocq.ibarra.foy.."),
            AGENTS_CONSTRAINTS + ", 2; module-info.java of ibarra-foy and ibarra-core");

    /**
     * AGENTS.md, Architecture: ibarra-api is the bottom layer; ibarra-core and ibarra-compiler build on it;
     * ibarra-foy binds the core to Foy and nothing builds on it. The compiler and the core do not know each other.
     */
    static final ArchRule LAYERS = VidocqArchRules.layers(
            AGENTS_CONSTRAINTS + "; AGENTS.md, Architecture; the module-info.java files",
            Layer.named("API").in(API).accessesNoLayer().accessibleOnlyBy("Core", "Compiler", "Foy adapter"),
            Layer.named("Core").in(CORE).accessibleOnlyBy("Foy adapter"),
            Layer.named("Compiler").in(COMPILER).accessibleByNone(),
            Layer.named("Foy adapter").in(FOY).accessibleByNone());

    /** Workspace AGENTS.md, dependency graph and layering: ibarra-api never depends on another module. */
    static final ArchRule API_NEVER_ON_CORE = VidocqArchRules.neverDependOn(
            API,
            join(CORE, COMPILER, FOY),
            "workspace AGENTS.md, dependency graph; module-info.java of ibarra-api");

    /**
     * Constraints 1 and 2: the container-neutral core and the compiler know neither Foy nor, for the core, Peano
     * ("Peano is the default provider, not a hard dependency").
     */
    static final ArchRule CORE_STAYS_NEUTRAL = VidocqArchRules.neverDependOn(
            join(API, CORE),
            new String[] {"io.vidocq.foy..", "io.vidocq.peano.."},
            AGENTS_CONSTRAINTS + ", 1 and 2");

    /** Workspace AGENTS.md, dependency graph: Ibarra composes Foy and Peano, never the other bricks' engines. */
    static final ArchRule FOUNDATIONAL_BRICKS = VidocqArchRules.neverDependOn(
            new String[] {"io.vidocq.ibarra.."},
            new String[] {
                "io.vidocq.chappe..", "io.vidocq.champollion..", "io.vidocq.vauban..", "io.vidocq.foy.internal.."
            },
            "workspace AGENTS.md, dependency graph");

    /**
     * The documented reflective fallbacks, each with its reason. Nested classes share the entry of their
     * top-level class.
     */
    static final Map<String, String> REFLECTIVE_FALLBACKS = Map.of();

    /** Constraint 4 and workspace AGENTS.md, "Maximum static code generation". */
    static final ArchRule NO_REFLECTION = VidocqArchRules.noReflection(
            REFLECTIVE_FALLBACKS, "workspace AGENTS.md, static code generation; " + AGENTS_CONSTRAINTS + ", 3 and 4");

    /** Workspace AGENTS.md, "Virtual Threads everywhere for I/O". */
    static final ArchRule NO_PLATFORM_THREADS = VidocqArchRules.noPlatformThreads(
            PlatformThreads.UNLESS_THREAD_FACTORY_GIVEN, "workspace AGENTS.md, Virtual Threads");

    /** Constraint 5: no ThreadLocal; page state travels in the PageContext. */
    static final ArchRule NO_THREAD_LOCAL = VidocqArchRules.noThreadLocal(
            "page state travels in the PageContext: a ThreadLocal neither follows a request across virtual threads"
                    + " nor caches anything",
            AGENTS_CONSTRAINTS + ", 5");

    private ArchitectureRules() {
        // Rules only.
    }

    private static String[] join(String[]... packages) {
        return java.util.stream.Stream.of(packages).flatMap(java.util.stream.Stream::of).toArray(String[]::new);
    }
}
