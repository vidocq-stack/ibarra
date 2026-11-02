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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.vidocq.ibarra.core.internal.leak.LeakedBean;
import io.vidocq.ibarra.spi.PageDescriptor;
import io.vidocq.ibarra.spi.PageIndex;
import jakarta.el.BeanELResolver;
import jakarta.el.ELContext;
import jakarta.el.ELResolver;
import jakarta.el.FunctionMapper;
import jakarta.el.VariableMapper;
import jakarta.servlet.jsp.JspFactory;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

/**
 * The page expressions read beans with the API {@code BeanELResolver}, whose {@code java.beans} introspection caches
 * are JVM-wide: each bean class of a layer {@code vidocq dev} discards stays in them, and so does its class loader
 * (BUG-20261102-02). Closing the page application of a layer must drop them.
 */
class IntrospectionLeakTest {
    @Test
    void closingThePageApplicationReleasesTheClassLoaderOfTheBeansItsPagesRead() throws Exception {
        JspFactory previous = JspFactory.getDefaultFactory();
        JspFactory.setDefaultFactory(null);
        var application = IbarraPageApplication.open(new ServletDoubles.Context(),
                new PrecompiledPages(List.of((PageIndex) () -> List.<PageDescriptor>of())));
        try {
            WeakReference<ClassLoader> layer = readABeanOfANewLayer();
            application.close();
            assertNull(awaitCollected(layer), "the layer's class loader is still held by the java.beans caches");
        } finally {
            application.close();
            JspFactory.setDefaultFactory(previous);
        }
    }

    /** Reads {@code name} from a bean of a fresh layer on a virtual thread, as the request threads of Foy do. */
    private static WeakReference<ClassLoader> readABeanOfANewLayer() throws Exception {
        var loader = new LayerLoader();
        var read = new AtomicReference<Object>();
        var failure = new AtomicReference<Throwable>();
        Thread thread = Thread.ofVirtual().start(() -> {
            try {
                Object bean = loader.loadClass(LeakedBean.class.getName()).getConstructor().newInstance();
                var context = new BareContext();
                read.set(new BeanELResolver().getValue(context, bean, "name"));
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        thread.join();
        assertNull(failure.get());
        assertNotNull(read.get());
        assertEquals("layer", read.get());
        return new WeakReference<>(loader);
    }

    /** Waits for the collector to take {@code reference}; the class loader while it is still reachable, else null. */
    private static ClassLoader awaitCollected(WeakReference<ClassLoader> reference) {
        for (int attempt = 0; attempt < 100 && reference.get() != null; attempt++) {
            System.gc();
            LockSupport.parkNanos(50_000_000L);
        }
        return reference.get();
    }

    /** An EL context with no resolver of its own: the test calls the bean resolver directly. */
    private static final class BareContext extends ELContext {
        @Override
        public ELResolver getELResolver() {
            return null;
        }

        @Override
        public FunctionMapper getFunctionMapper() {
            return null;
        }

        @Override
        public VariableMapper getVariableMapper() {
            return null;
        }
    }

    /** An application layer: its own copy of {@link LeakedBean}. */
    private static final class LayerLoader extends ClassLoader {
        LayerLoader() {
            super(IntrospectionLeakTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!LeakedBean.class.getName().equals(name)) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded != null) return loaded;
                try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                    if (in == null) throw new ClassNotFoundException(name);
                    byte[] bytes = in.readAllBytes();
                    return defineClass(name, bytes, 0, bytes.length);
                } catch (IOException e) {
                    throw new ClassNotFoundException(name, e);
                }
            }
        }
    }
}
