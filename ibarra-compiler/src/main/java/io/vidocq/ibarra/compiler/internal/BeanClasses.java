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
package io.vidocq.ibarra.compiler.internal;
import io.vidocq.ibarra.compiler.*;
import java.io.IOException;
import java.lang.classfile.*;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;

/** Build-time property lookup using class files, never loaded classes or reflection. */
public final class BeanClasses {
    public record Accessor(String name, String type) { }
    public record Handler(String sourceName, boolean simple, boolean iteration, boolean buffered,
            boolean catchFinally, boolean dynamic) { }
    private final List<Path> paths;
    public BeanClasses(List<Path> paths) { this.paths = List.copyOf(paths); }

    public void constructor(String type, SourceLocation at) {
        constructor(type, at, "Bean");
    }
    private void constructor(String type, SourceLocation at, String role) {
        var model = read(type, at);
        if ((model.flags().flagsMask() & (ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT | ClassFile.ACC_INTERFACE)) != ClassFile.ACC_PUBLIC)
            throw new PageParseException(at, role + " must be a public concrete class: " + type);
        boolean constructor = model.methods().stream().anyMatch(method -> method.methodName().stringValue().equals("<init>")
                && method.methodType().stringValue().equals("()V") && (method.flags().flagsMask() & ClassFile.ACC_PUBLIC) != 0);
        if (!constructor) throw new PageParseException(at, role + " needs a public no-argument constructor: " + type);
    }

    /** Validates without loading classes and returns the source name of a listener constructor. */
    public String listener(String type, SourceLocation at) {
        constructor(type, at, "TLD listener");
        if (!listenerType(type, at, new HashSet<>())) throw new PageParseException(at, "Unsupported Servlet listener type: " + type);
        return sourceName(type, at);
    }
    private boolean listenerType(String type, SourceLocation at, Set<String> visited) {
        if (Set.of("jakarta.servlet.ServletContextListener", "jakarta.servlet.ServletContextAttributeListener",
                "jakarta.servlet.ServletRequestListener", "jakarta.servlet.ServletRequestAttributeListener",
                "jakarta.servlet.http.HttpSessionListener", "jakarta.servlet.http.HttpSessionAttributeListener",
                "jakarta.servlet.http.HttpSessionIdListener").contains(type)) return true;
        if (type.equals("java.lang.Object") || !visited.add(type)) return false;
        var model = read(type, at);
        for (var parent : model.interfaces()) if (listenerType(parent.asInternalName().replace('/', '.'), at, visited)) return true;
        return model.superclass().isPresent() && listenerType(model.superclass().orElseThrow().asInternalName().replace('/', '.'), at, visited);
    }
    private String sourceName(String type, SourceLocation at) {
        var model = read(type, at);
        var innerClasses = model.findAttribute(java.lang.classfile.Attributes.innerClasses());
        if (innerClasses.isPresent()) for (var inner : innerClasses.orElseThrow().classes()) {
            if (inner.innerClass().asInternalName().replace('/', '.').equals(type)
                    && inner.outerClass().isPresent() && inner.innerName().isPresent())
                return sourceName(inner.outerClass().orElseThrow().asInternalName().replace('/', '.'), at)
                        + "." + inner.innerName().orElseThrow().stringValue();
        }
        return type;
    }

    public Accessor getter(String type, String property, SourceLocation at) {
        String suffix = suffix(property, at);
        var methods = methods(type, at);
        for (String name : List.of("is" + suffix, "get" + suffix))
            for (var method : methods) {
                var descriptor = method.methodTypeSymbol();
                if (method.methodName().stringValue().equals(name) && descriptor.parameterCount() == 0
                        && !descriptor.returnType().descriptorString().equals("V")
                        && (!name.startsWith("is") || descriptor.returnType().descriptorString().equals("Z")))
                    return new Accessor(name, javaType(descriptor.returnType().descriptorString()));
            }
        throw new PageParseException(at, "No readable property " + property + " on " + type);
    }

    public Handler handler(String type, SourceLocation at) {
        constructor(type, at, "Tag handler");
        boolean simple = subtype(type, "jakarta.servlet.jsp.tagext.SimpleTag", at, new HashSet<>());
        boolean classic = subtype(type, "jakarta.servlet.jsp.tagext.Tag", at, new HashSet<>());
        if (simple == classic) throw new PageParseException(at, "Handler must implement exactly one of Tag or SimpleTag: " + type);
        return new Handler(sourceName(type, at), simple,
                subtype(type, "jakarta.servlet.jsp.tagext.IterationTag", at, new HashSet<>()),
                subtype(type, "jakarta.servlet.jsp.tagext.BodyTag", at, new HashSet<>()),
                subtype(type, "jakarta.servlet.jsp.tagext.TryCatchFinally", at, new HashSet<>()),
                subtype(type, "jakarta.servlet.jsp.tagext.DynamicAttributes", at, new HashSet<>()));
    }
    private boolean subtype(String type, String target, SourceLocation at, Set<String> visited) {
        if (type.equals(target)) return true;
        if (type.equals("java.lang.Object") || !visited.add(type)) return false;
        var model = read(type, at);
        for (var parent : model.interfaces()) if (subtype(parent.asInternalName().replace('/', '.'), target, at, visited)) return true;
        return model.superclass().isPresent() && subtype(model.superclass().orElseThrow().asInternalName().replace('/', '.'), target, at, visited);
    }
    public Accessor tagSetter(String type, String property, String expected, SourceLocation at) {
        String name = "set" + suffix(property, at);
        for (var method : methods(type, at)) {
            var descriptor = method.methodTypeSymbol();
            if (method.methodName().stringValue().equals(name) && descriptor.parameterCount() == 1
                    && descriptor.returnType().descriptorString().equals("V")) {
                String parameter = javaType(descriptor.parameterType(0).descriptorString());
                if (parameter.equals(expected.replace('$', '.'))) return new Accessor(name, parameter);
            }
        }
        throw new PageParseException(at, "No setter matching TLD type " + expected + " for " + property + " on " + type);
    }

    public Accessor setter(String type, String property, SourceLocation at) {
        String name = "set" + suffix(property, at);
        var candidates = new LinkedHashMap<String, Accessor>();
        for (var method : methods(type, at)) {
            var descriptor = method.methodTypeSymbol();
            if (method.methodName().stringValue().equals(name) && descriptor.parameterCount() == 1
                    && descriptor.returnType().descriptorString().equals("V")) {
                String parameter = javaType(descriptor.parameterType(0).descriptorString());
                candidates.putIfAbsent(parameter, new Accessor(name, parameter));
            }
        }
        if (candidates.size() > 1) {
            try {
                var getter = getter(type, property, at);
                if (candidates.containsKey(getter.type())) return candidates.get(getter.type());
            } catch (PageParseException ignored) { /* Report the setter ambiguity below. */ }
        }
        if (candidates.size() != 1) throw new PageParseException(at, "Missing or ambiguous writable property " + property + " on " + type);
        return candidates.values().iterator().next();
    }

    public Set<String> writableProperties(String type, SourceLocation at) {
        var properties = new LinkedHashSet<String>();
        for (var method : methods(type, at)) {
            String name = method.methodName().stringValue();
            var descriptor = method.methodTypeSymbol();
            if (name.startsWith("set") && name.length() > 3 && descriptor.parameterCount() == 1
                    && descriptor.returnType().descriptorString().equals("V")) {
                String suffix = name.substring(3);
                String property = suffix.length() > 1 && Character.isUpperCase(suffix.charAt(0)) && Character.isUpperCase(suffix.charAt(1))
                        ? suffix : Character.toLowerCase(suffix.charAt(0)) + suffix.substring(1);
                properties.add(property);
            }
        }
        return Collections.unmodifiableSet(properties);
    }

    private List<MethodModel> methods(String type, SourceLocation at) {
        var result = new ArrayList<MethodModel>();
        var visited = new HashSet<String>();
        collect(type, at, result, visited);
        return result;
    }
    private void collect(String type, SourceLocation at, List<MethodModel> result, Set<String> visited) {
        if (!visited.add(type)) return;
        var model = read(type, at);
        model.methods().stream().filter(method -> (method.flags().flagsMask()
                & (ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC)) == ClassFile.ACC_PUBLIC).forEach(result::add);
        model.superclass().ifPresent(parent -> collect(parent.asInternalName().replace('/', '.'), at, result, visited));
        for (var parent : model.interfaces()) collect(parent.asInternalName().replace('/', '.'), at, result, visited);
    }
    private ClassModel read(String type, SourceLocation at) {
        String file = type.replace('.', '/') + ".class";
        try {
            for (var path : paths) {
                if (Files.isDirectory(path) && Files.isRegularFile(path.resolve(file))) return ClassFile.of().parse(path.resolve(file));
                if (Files.isRegularFile(path)) {
                    try (var jar = new JarFile(path.toFile())) {
                        var entry = jar.getJarEntry(file);
                        if (entry != null) {
                            try (var stream = jar.getInputStream(entry)) { return ClassFile.of().parse(stream.readAllBytes()); }
                        }
                    }
                }
            }
            var jrt = FileSystems.getFileSystem(java.net.URI.create("jrt:/"));
            if (type.startsWith("java.")) {
                var filePath = jrt.getPath("/modules/java.base/" + file);
                if (Files.isRegularFile(filePath)) return ClassFile.of().parse(filePath);
            }
        } catch (IOException | IllegalArgumentException failure) {
            throw new PageParseException(at, "Cannot read bean class " + type, failure);
        }
        throw new PageParseException(at, "Bean class is absent from the build module path: " + type);
    }
    private static String suffix(String property, SourceLocation at) {
        if (property.isEmpty()) throw new PageParseException(at, "Empty bean property");
        return Character.toUpperCase(property.charAt(0)) + property.substring(1);
    }
    private static String javaType(String descriptor) {
        if (descriptor.startsWith("[")) return javaType(descriptor.substring(1)) + "[]";
        return switch (descriptor) {
            case "Z" -> "boolean"; case "B" -> "byte"; case "C" -> "char"; case "D" -> "double";
            case "F" -> "float"; case "I" -> "int"; case "J" -> "long"; case "S" -> "short";
            default -> descriptor.substring(1, descriptor.length() - 1).replace('/', '.').replace('$', '.');
        };
    }
}
