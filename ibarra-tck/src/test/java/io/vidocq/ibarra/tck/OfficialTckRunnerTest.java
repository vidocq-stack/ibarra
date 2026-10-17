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
package io.vidocq.ibarra.tck;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OfficialTckRunnerTest {
    private static final String FULL_SUITE = "-Dtest=ee.jakarta.tck.pages.**.*IT";

    @TempDir Path work;

    @Test
    void allSelectionRunsWithoutAdditionalArguments() throws Exception {
        String output = invokeRunner(List.of("all"));

        assertTrue(output.contains("--invocation-end--"));
        assertTrue(Files.readString(work.resolve("maven-args.log")).contains(FULL_SUITE));
    }

    @Test
    void forwardsAdditionalMavenArguments() throws Exception {
        String output = invokeRunner(List.of("all", "-Dtest=ee.jakarta.tck.pages.ExtraIT"));

        assertTrue(output.contains("--invocation-end--"));
        assertTrue(Files.readString(work.resolve("maven-args.log")).contains("-Dtest=ee.jakarta.tck.pages.ExtraIT"));
    }

    private String invokeRunner(List<String> arguments) throws Exception {
        Path runnerDirectory = Files.createDirectories(work.resolve("runner"));
        Path runner = Files.copy(projectRoot().resolve("run-official-tck-pages-4.0.sh"),
                runnerDirectory.resolve("run-official-tck-pages-4.0.sh"));
        Path argumentLog = work.resolve("maven-args.log");
        Path mavenWrapper = runnerDirectory.resolve("mvnw");
        Files.writeString(mavenWrapper,
                "#!/bin/sh\nprintf '<%s>\\n' \"$@\" >> \"$MAVEN_ARGS_LOG\"\nprintf '%s\\n' '--invocation-end--' >> \"$MAVEN_ARGS_LOG\"\n",
                StandardCharsets.UTF_8);
        assertTrue(mavenWrapper.toFile().setExecutable(true));

        var command = new java.util.ArrayList<String>();
        command.add("/bin/bash");
        command.add(runner.toString());
        command.add("--no-prepare");
        command.add("--no-install");
        command.addAll(arguments);
        ProcessBuilder processBuilder = new ProcessBuilder(command).directory(runnerDirectory.toFile())
                .redirectErrorStream(true);
        processBuilder.environment().put("MAVEN_ARGS_LOG", argumentLog.toString());
        Process process = processBuilder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();

        assertEquals(0, exitCode, output);
        assertEquals(2, Files.readAllLines(argumentLog).stream().filter("--invocation-end--"::equals).count());
        return Files.readString(argumentLog);
    }

    private Path projectRoot() throws IOException {
        String multiModuleDirectory = System.getProperty("maven.multiModuleProjectDirectory");
        if (multiModuleDirectory != null) {
            Path candidate = Path.of(multiModuleDirectory).resolve("run-official-tck-pages-4.0.sh");
            if (Files.isRegularFile(candidate)) return candidate.getParent();
        }
        Path userDirectory = Path.of(System.getProperty("user.dir"));
        for (Path candidate : List.of(userDirectory.resolve("run-official-tck-pages-4.0.sh"),
                userDirectory.resolve("../run-official-tck-pages-4.0.sh").normalize())) {
            if (Files.isRegularFile(candidate)) return candidate.getParent();
        }
        throw new IOException("Cannot locate run-official-tck-pages-4.0.sh from " + userDirectory);
    }
}
