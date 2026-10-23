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
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.core.spi.LoadableExtension;
import org.jboss.arquillian.core.spi.context.Context;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Arquillian entry point: its service registration and what it registers. */
class PagesExtensionTest {
    private static final String SERVICE_FILE = "META-INF/services/" + LoadableExtension.class.getName();

    @Test
    void theServiceFileOnTheClassPathNamesTheExtension() throws IOException {
        var providers = new ArrayList<String>();
        for (URL resource : Collections.list(PagesExtension.class.getClassLoader().getResources(SERVICE_FILE))) {
            try (InputStream input = resource.openStream()) {
                new String(input.readAllBytes(), StandardCharsets.UTF_8).lines().map(String::trim)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#")).forEach(providers::add);
            }
        }

        assertTrue(providers.contains(PagesExtension.class.getName()), providers.toString());
    }

    @Test
    void registerDeclaresThePagesContainerAsTheDeployableContainer() {
        var builder = new RecordingBuilder();

        new PagesExtension().register(builder);

        assertEquals(List.of("service " + DeployableContainer.class.getName() + " -> " + PagesContainer.class.getName()),
                builder.calls);
    }

    /** Records every registration as one line. */
    private static final class RecordingBuilder implements LoadableExtension.ExtensionBuilder {
        final List<String> calls = new ArrayList<>();

        @Override
        public <T> LoadableExtension.ExtensionBuilder service(Class<T> service, Class<? extends T> impl) {
            calls.add("service " + service.getName() + " -> " + impl.getName());
            return this;
        }

        @Override
        public <T> LoadableExtension.ExtensionBuilder override(Class<T> service, Class<? extends T> oldServiceImpl,
                Class<? extends T> newServiceImpl) {
            calls.add("override " + service.getName());
            return this;
        }

        @Override
        public LoadableExtension.ExtensionBuilder observer(Class<?> handler) {
            calls.add("observer " + handler.getName());
            return this;
        }

        @Override
        public LoadableExtension.ExtensionBuilder context(Class<? extends Context> context) {
            calls.add("context " + context.getName());
            return this;
        }
    }
}
