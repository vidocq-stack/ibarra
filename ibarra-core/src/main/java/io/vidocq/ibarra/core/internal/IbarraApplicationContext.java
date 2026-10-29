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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import jakarta.el.*;
import jakarta.servlet.jsp.*;

/** Application-owned EL registrations, frozen atomically when first used. */
final class IbarraApplicationContext implements JspApplicationContext {
    private record ForwardRegistration(io.vidocq.ibarra.core.binding.PageForwardDispatcher dispatcher, boolean used) { }
    private final AtomicReference<ForwardRegistration> forward = new AtomicReference<>(
            new ForwardRegistration(io.vidocq.ibarra.core.binding.PageForwardDispatcher.DIRECT, false));
    // One bounded, application-owned lock also coordinates distinct session facades.
    final java.util.concurrent.locks.ReentrantLock beanScopeLock = new java.util.concurrent.locks.ReentrantLock();
    private record Registration(List<ELResolver> resolvers, List<ELContextListener> listeners, boolean used) { }
    private final AtomicReference<Registration> registration =
            new AtomicReference<>(new Registration(List.of(), List.of(), false));
    private final FutureTask<ExpressionFactory> expressionFactory;

    IbarraApplicationContext() { this(ExpressionFactory::newInstance); }

    IbarraApplicationContext(ExpressionFactory factory) {
        Objects.requireNonNull(factory);
        this(() -> factory);
    }

    IbarraApplicationContext(Supplier<ExpressionFactory> source) {
        Objects.requireNonNull(source);
        expressionFactory = new FutureTask<>(() -> Objects.requireNonNull(source.get()));
    }

    void configureForwardDispatcher(io.vidocq.ibarra.core.binding.PageForwardDispatcher dispatcher) {
        Objects.requireNonNull(dispatcher);
        forward.updateAndGet(current -> {
            if (current.used()) throw new IllegalStateException("Forward dispatcher must be installed before serving pages");
            return new ForwardRegistration(dispatcher, false);
        });
    }

    io.vidocq.ibarra.core.binding.PageForwardDispatcher forwardDispatcher() {
        return forward.updateAndGet(current -> new ForwardRegistration(current.dispatcher(), true)).dispatcher();
    }

    @Override
    public void addELResolver(ELResolver resolver) {
        Objects.requireNonNull(resolver);
        registration.updateAndGet(current -> {
            if (current.used()) throw new IllegalStateException("EL resolvers must be registered before serving pages");
            var resolvers = new ArrayList<>(current.resolvers());
            resolvers.add(resolver);
            return new Registration(List.copyOf(resolvers), current.listeners(), false);
        });
    }

    @Override
    public void addELContextListener(ELContextListener listener) {
        Objects.requireNonNull(listener);
        registration.updateAndGet(current -> {
            var listeners = new ArrayList<>(current.listeners());
            listeners.add(listener);
            return new Registration(current.resolvers(), List.copyOf(listeners), current.used());
        });
    }

    @Override
    public ExpressionFactory getExpressionFactory() {
        expressionFactory.run();
        try {
            return expressionFactory.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ELException("Interrupted while initializing the expression factory", interrupted);
        } catch (ExecutionException failed) {
            throw new ELException("Cannot initialize the expression factory", failed.getCause());
        }
    }

    ELContext createELContext(PageContext page) {
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        var loader = page.getPage().getClass().getClassLoader();
        try {
            if (loader != null) thread.setContextClassLoader(loader);
            return createContext(page);
        } finally { thread.setContextClassLoader(previous); }
    }

    private ELContext createContext(PageContext page) {
        var current = registration.getAndUpdate(value -> new Registration(value.resolvers(), value.listeners(), true));
        var resolvers = new PageResolverChain();
        resolvers.addLookup(new PagesImplicitObjectResolver(page));
        for (var resolver : current.resolvers()) resolvers.add(resolver);
        resolvers.addLookup(new JspPropertyELResolver());
        resolvers.addLookup(new ServletStaticFieldELResolver());
        var providerResolver = getExpressionFactory().getStreamELResolver();
        if (providerResolver != null) resolvers.add(providerResolver);
        resolvers.addLookup(new StaticFieldELResolver());
        resolvers.addLookup(new MapELResolver());
        resolvers.addLookup(new ResourceBundleELResolver());
        resolvers.addLookup(new ListELResolver());
        resolvers.addLookup(new ArrayELResolver());
        resolvers.addLookup(new RecordELResolver());
        resolvers.add(new OptionalELResolver());
        resolvers.addLookup(new BeanELResolver());
        resolvers.addLookup(new ScopedAttributeResolver(page));
        resolvers.addLookup(new jakarta.servlet.jsp.el.ImportELResolver());
        boolean strict = page.getPage() instanceof io.vidocq.ibarra.spi.PageOptions options && options.errorOnELNotFound();
        resolvers.addLookup(new NotFoundELResolver(strict));
        var imports = page.getPage() instanceof io.vidocq.ibarra.spi.PageOptions options && options.usesStaticELImports()
                ? new StaticImports(options) : null;
        var context = new StandardELContext(getExpressionFactory()) {
            @Override public ELResolver getELResolver() { return resolvers; }
            @Override public ImportHandler getImportHandler() { return imports == null ? super.getImportHandler() : imports; }
        };
        if (imports == null) {
            for (String name : java.util.List.of("jakarta.servlet", "jakarta.servlet.http", "jakarta.servlet.jsp"))
                context.getImportHandler().importPackage(name);
        }
        context.putContext(JspContext.class, page);
        context.putContext(PageContext.class, page);
        return context;
    }
    private static final class StaticImports extends ImportHandler {
        private final io.vidocq.ibarra.spi.PageOptions page;
        StaticImports(io.vidocq.ibarra.spi.PageOptions page) { this.page = page; }
        @Override public Class<?> resolveClass(String name) { return page.resolveELClass(name); }
        @Override public Class<?> resolveStatic(String name) { return null; }
        @Override public void importClass(String name) { throw new ELException("Runtime import changes are unsupported for precompiled pages"); }
        @Override public void importStatic(String name) { throw new ELException("Runtime import changes are unsupported for precompiled pages"); }
        @Override public void importPackage(String name) {
            if (page == null) super.importPackage(name);
            else throw new ELException("Runtime import changes are unsupported for precompiled pages");
        }
    }

    /** Serves implicit Servlet API static constants without shadowing generated application accessors. */
    private static final class ServletStaticFieldELResolver extends StaticFieldELResolver {
        private static boolean servletApi(Object base) {
            if (!(base instanceof ELClass type)) return false;
            String module = type.getKlass().getModule().getName();
            return "jakarta.servlet".equals(module) || "jakarta.servlet.jsp".equals(module);
        }

        @Override public Object getValue(ELContext context, Object base, Object property) {
            return servletApi(base) ? super.getValue(context, base, property) : null;
        }
        @Override public Class<?> getType(ELContext context, Object base, Object property) {
            return servletApi(base) ? super.getType(context, base, property) : null;
        }
        @Override public void setValue(ELContext context, Object base, Object property, Object value) {
            if (servletApi(base)) super.setValue(context, base, property, value);
        }
        @Override public boolean isReadOnly(ELContext context, Object base, Object property) {
            return servletApi(base) && super.isReadOnly(context, base, property);
        }
        @Override public Object invoke(ELContext context, Object base, Object method, Class<?>[] types, Object[] arguments) {
            return servletApi(base) ? super.invoke(context, base, method, types, arguments) : null;
        }
        @Override public Class<?> getCommonPropertyType(ELContext context, Object base) {
            return servletApi(base) ? super.getCommonPropertyType(context, base) : null;
        }
    }

    void notifyListeners(ELContext context) {
        var event = new ELContextEvent(context);
        for (var listener : registration.get().listeners()) listener.contextCreated(event);
    }

    private static final class ScopedAttributeResolver extends ELResolver {
        private final PageContext page;
        ScopedAttributeResolver(PageContext page) { this.page = page; }

        @Override
        public Object getValue(ELContext context, Object base, Object property) {
            if (base != null || property == null) return null;
            var value = page.findAttribute(property.toString());
            if (value != null) context.setPropertyResolved(true);
            return value;
        }

        @Override
        public Class<?> getType(ELContext context, Object base, Object property) {
            if (base != null || property == null) return null;
            context.setPropertyResolved(true);
            return Object.class;
        }

        @Override
        public void setValue(ELContext context, Object base, Object property, Object value) {
            if (base == null && property != null) {
                String name = property.toString();
                int scope = page.getAttributesScope(name);
                page.setAttribute(name, value, scope == 0 ? PageContext.PAGE_SCOPE : scope);
                context.setPropertyResolved(true);
            }
        }

        @Override
        public boolean isReadOnly(ELContext context, Object base, Object property) {
            if (base == null && property != null) context.setPropertyResolved(true);
            return false;
        }

        @Override
        public Class<?> getCommonPropertyType(ELContext context, Object base) {
            return base == null ? String.class : null;
        }
    }
}

