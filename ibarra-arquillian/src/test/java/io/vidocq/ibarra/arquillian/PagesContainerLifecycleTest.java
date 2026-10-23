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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.container.LifecycleException;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Server-process cleanup paths, driven by a hand-written {@link Process} double. */
class PagesContainerLifecycleTest {
    private static final Path WORK = Path.of("/w");
    private static final String NAME = "app.war";

    @AfterEach
    void clearInterruptFlag() {
        // The doubles interrupt waits; never leak the flag into the next test.
        Thread.interrupted();
    }

    @Test
    void anInterruptedExitWaitStopsWaitingAndRestoresTheFlag() {
        var process = new FakeProcess(true, false);

        PagesContainer.awaitExit(process);

        assertTrue(Thread.interrupted(), "interrupt flag restored");
        assertEquals(1, process.waits, "the wait stops at the first interrupt");
    }

    @Test
    void aFailingInputCloseIsIgnored() {
        var process = new FakeProcess(false, true);

        assertDoesNotThrow(() -> PagesContainer.closeInput(process));
        assertDoesNotThrow(() -> PagesContainer.terminate(process));
        assertEquals(0, process.kills, "a dead process is not killed again");
    }

    @Test
    void aSurvivingServerFailsTermination() {
        var process = new FakeProcess(true, false);

        var failure = assertThrows(LifecycleException.class, () -> PagesContainer.terminate(process));

        assertTrue(failure.getMessage().contains("42"), failure.getMessage());
        assertEquals(1, process.kills);
    }

    @Test
    void anAbandonedServerThatSurvivesStaysRegisteredUntilReleased() {
        var container = new PagesContainer();
        var stuck = new FakeProcess(true, false);
        var cause = new IOException("boom");

        var failure = container.abandon("app.war", stuck, WORK, cause);

        assertSame(cause, failure.getCause());
        assertEquals("Cannot precompile/deploy app.war (diagnostics: " + WORK + ")", failure.getMessage());
        assertInstanceOf(LifecycleException.class, cause.getSuppressed()[0]);
        Thread.interrupted();
        assertThrows(LifecycleException.class, container::stop, "the surviving server is still registered");

        Thread.interrupted();
        var undeployFailure = new IOException("shutdown");
        container.release("app.war", new FakeProcess(false, false), undeployFailure);
        assertEquals(0, undeployFailure.getSuppressed().length);
        assertDoesNotThrow(container::stop, "a released server is no longer registered");
    }

    @Test
    void aReleaseThatCannotTerminateKeepsTheCleanupFailure() {
        var container = new PagesContainer();
        var failure = new IOException("shutdown");

        container.release("app.war", new FakeProcess(true, false), failure);

        assertInstanceOf(LifecycleException.class, failure.getSuppressed()[0]);
    }

    @Test
    void anAbandonedDeploymentWithoutServerOrWithAStoppedOneIsNotRegistered() throws Exception {
        var container = new PagesContainer();
        var cause = new IOException("boom");

        container.abandon("none.war", null, WORK, cause);
        container.abandon("stopped.war", new FakeProcess(false, false), WORK, cause);

        assertEquals(0, cause.getSuppressed().length);
        container.stop();
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void theReadyLineOfTheServerLogGivesThePort(@TempDir Path work) throws Exception {
        Path log = Files.writeString(work.resolve("server.log"), "booting\nREADY: 8123\n");

        assertEquals(8123, PagesContainer.awaitReady(new ScriptedProcess(), log));
    }

    @Test
    void aServerThatExitsBeforeReadyFailsWithItsLog(@TempDir Path work) throws IOException {
        Path log = Files.writeString(work.resolve("server.log"), "booting\n");
        var process = new ScriptedProcess().aliveFor(2);

        var failure = assertThrows(IOException.class, () -> PagesContainer.awaitReady(process, log));

        assertEquals("Server did not start: booting\n", failure.getMessage());
    }

    @Test
    void anUndeployWhoseShutdownTimesOutKillsTheServerAndUnregistersIt(@TempDir Path work) {
        var process = new ScriptedProcess();
        var container = registered(process, work);

        var failure = assertThrows(DeploymentException.class, () -> container.undeploy(archive()));

        assertEquals("Cannot undeploy " + NAME, failure.getMessage());
        assertEquals("Server shutdown timed out", failure.getCause().getMessage());
        assertEquals(1, process.kills, "the server is killed");
        assertEquals(1, process.shutdownSignals);
        assertDoesNotThrow(container::stop, "the killed server is no longer registered");
    }

    @Test
    void anUndeployWhoseServerExitsWithAFailureReportsItsLog(@TempDir Path work) throws Exception {
        Files.writeString(work.resolve("server.log"), "server log tail");
        var process = new ScriptedProcess();
        var container = registered(process, work);
        process.exitsOnShutdown = true;
        process.exitCode = 3;

        var failure = assertThrows(DeploymentException.class, () -> container.undeploy(archive()));

        assertEquals("Server failed: server log tail", failure.getCause().getMessage());
        assertEquals(0, process.kills, "an exited server is not killed");
        assertDoesNotThrow(container::stop, "the exited server is no longer registered");
    }

    @Test
    void anUndeployWhoseServerExitsCleanlyUnregistersIt(@TempDir Path work) throws Exception {
        var process = new ScriptedProcess();
        var container = registered(process, work);
        process.exitsOnShutdown = true;

        container.undeploy(archive());

        assertEquals(0, process.kills);
        assertDoesNotThrow(() -> container.undeploy(archive()), "undeploying an unknown archive is a no-op");
        assertDoesNotThrow(container::stop);
    }

    @Test
    void anInterruptedUndeployRestoresTheFlagAndKeepsASurvivingServerRegistered(@TempDir Path work) {
        var process = new ScriptedProcess();
        var container = registered(process, work);
        process.stubborn = true;

        var failure = assertThrows(DeploymentException.class, () -> container.undeploy(archive()));

        assertInstanceOf(InterruptedException.class, failure.getCause());
        assertInstanceOf(LifecycleException.class, failure.getCause().getSuppressed()[0]);
        assertTrue(Thread.interrupted(), "interrupt flag restored");
        assertThrows(LifecycleException.class, container::stop, "the surviving server is still registered");
    }

    /** A container holding {@code process} as the server of {@link #NAME}, registered by a failed cleanup. */
    private static PagesContainer registered(ScriptedProcess process, Path work) {
        var container = new PagesContainer();
        container.abandon(NAME, process, work, new IOException("deployment failed"));
        // The stubborn waits of that cleanup interrupted this thread.
        Thread.interrupted();
        process.stubborn = false;
        process.kills = 0;
        return container;
    }

    private static WebArchive archive() {
        return ShrinkWrap.create(WebArchive.class, NAME);
    }

    /**
     * A scripted server process. While stubborn, kills are ignored and waits interrupted; otherwise a kill stops
     * it, and a shutdown wait succeeds only when {@link #exitsOnShutdown} is set.
     */
    private static final class ScriptedProcess extends Process {
        private int aliveAnswers = Integer.MAX_VALUE;
        private boolean alive = true;
        boolean stubborn = true;
        boolean exitsOnShutdown;
        int exitCode;
        int kills;
        int shutdownSignals;

        /** Alive for the next {@code answers} {@link #isAlive()} calls only. */
        ScriptedProcess aliveFor(int answers) {
            aliveAnswers = answers;
            return this;
        }

        @Override
        public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override
                public void write(int value) {
                    shutdownSignals++;
                }
            };
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() throws InterruptedException {
            waitFor(0, TimeUnit.MILLISECONDS);
            return exitCode;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            if (stubborn) {
                throw new InterruptedException("test interrupt");
            }
            if (exitsOnShutdown) {
                alive = false;
            }
            return !alive;
        }

        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException("still running");
            }
            return exitCode;
        }

        @Override
        public void destroy() {
            destroyForcibly();
        }

        @Override
        public Process destroyForcibly() {
            kills++;
            if (!stubborn) {
                alive = false;
            }
            return this;
        }

        @Override
        public boolean isAlive() {
            if (alive && aliveAnswers-- <= 0) {
                alive = false;
            }
            return alive;
        }

        @Override
        public long pid() {
            return 43;
        }
    }

    /** A server process: alive forever with interrupted waits, or already exited. */
    private static final class FakeProcess extends Process {
        private final boolean alive;
        private final boolean failingClose;
        int waits;
        int kills;

        FakeProcess(boolean alive, boolean failingClose) {
            this.alive = alive;
            this.failingClose = failingClose;
        }

        @Override
        public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override
                public void write(int value) {
                    // The double has no server behind its standard input.
                }

                @Override
                public void close() throws IOException {
                    if (failingClose) {
                        throw new IOException("Stream closed");
                    }
                }
            };
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() throws InterruptedException {
            return waitFor(0, TimeUnit.MILLISECONDS) ? 0 : 1;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            waits++;
            if (alive) {
                throw new InterruptedException("test interrupt");
            }
            return true;
        }

        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException("still running");
            }
            return 0;
        }

        @Override
        public void destroy() {
            kills++;
        }

        @Override
        public Process destroyForcibly() {
            kills++;
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public long pid() {
            return 42;
        }
    }
}
