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
import java.lang.module.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;

/** Translation-time type imports; application classes are inspected, never loaded. */
public final class ImportClasses {
    private static final String CLASS_SUFFIX = ".class";
    private record Source(ModuleDescriptor descriptor, ModuleReference reference, Path directory) {}
    private final List<Source> sources;
    private final ModuleDescriptor target;
    private final Set<String> readable = new HashSet<>();
    private final Map<String, ClassModel> models = new HashMap<>();
    private final Map<String, Set<String>> packages = new HashMap<>();

    public ImportClasses(List<Path> paths, ModuleDescriptor target) {
        this.target = target;
        var named = new LinkedHashMap<String, Source>();
        for (var reference : ModuleFinder.ofSystem().findAll())
            named.put(reference.descriptor().name(), new Source(reference.descriptor(), reference, null));
        for (var resolved : ModuleLayer.boot().configuration().modules())
            named.put(resolved.name(), new Source(resolved.reference().descriptor(), resolved.reference(), null));
        var loose = new ArrayList<Source>();
        try {
            for (var path : paths) addPath(path, named, loose);
        } catch (IOException failure) { throw new IllegalArgumentException("Cannot inspect import modules", failure); }
        sources = new ArrayList<>(named.values());
        sources.addAll(loose);
        if (target != null) addReadable(named);
    }

    private static void addPath(Path path, Map<String, Source> named, List<Source> loose) throws IOException {
        if (Files.isDirectory(path)) {
            Path info = path.resolve("module-info.class");
            if (Files.isRegularFile(info)) {
                var descriptor = ModuleDescriptor.read(ByteBuffer.wrap(Files.readAllBytes(info)));
                named.put(descriptor.name(), new Source(descriptor, null, path));
            } else loose.add(new Source(null, null, path));
        } else if (Files.isRegularFile(path)) {
            for (var reference : ModuleFinder.of(path).findAll())
                named.put(reference.descriptor().name(), new Source(reference.descriptor(), reference, null));
        }
    }

    /** The target module, java.base, its direct requirements and their transitive closure. */
    private void addReadable(Map<String, Source> named) {
        readable.add(target.name());
        readable.add("java.base");
        for (var requirement : target.requires()) {
            if (named.containsKey(requirement.name())) readable.add(requirement.name());
        }
        boolean changed;
        do {
            changed = false;
            for (var name : List.copyOf(readable)) changed |= addTransitive(named.get(name), named);
        } while (changed);
    }

    private boolean addTransitive(Source source, Map<String, Source> named) {
        if (source == null) return false;
        boolean changed = false;
        for (var requirement : source.descriptor().requires()) {
            if (requirement.modifiers().contains(ModuleDescriptor.Requires.Modifier.TRANSITIVE)
                    && named.containsKey(requirement.name())) changed |= readable.add(requirement.name());
        }
        return changed;
    }

    public Map<String, Set<String>> bindings(List<String> imports, SourceLocation at) {
        var result = new TreeMap<String, Set<String>>();
        var wildcard = new LinkedHashSet<>(List.of("java.lang", "jakarta.servlet", "jakarta.servlet.http", "jakarta.servlet.jsp"));
        var explicit = new LinkedHashMap<String, String>();
        for (String name : imports) {
            if (name.endsWith(".*")) wildcard.add(name.substring(0, name.length() - 2));
            else {
                String source = explicit(name, at);
                String simple = source.substring(source.lastIndexOf('.') + 1);
                String previous = explicit.putIfAbsent(simple, source);
                if (previous != null && !previous.equals(source)) throw new PageParseException(at, "Conflicting class imports: " + simple);
            }
        }
        for (String name : wildcard) {
            for (String type : packageTypes(name, at))
                result.computeIfAbsent(type.substring(type.lastIndexOf('.') + 1), ignored -> new TreeSet<>()).add(type);
        }
        explicit.forEach((name, type) -> result.put(name, Set.of(type)));
        return result;
    }

    private boolean accessible(Source source, String pkg) {
        var descriptor = source.descriptor();
        if (descriptor == null) return true;
        if (target != null && descriptor.name().equals(target.name())) return true;
        if (target != null && !readable.contains(descriptor.name())) return false;
        if (descriptor.isAutomatic()) return true;
        return descriptor.exports().stream().anyMatch(export -> export.source().equals(pkg)
                && (!export.isQualified() || target != null && export.targets().contains(target.name())));
    }

    private Set<String> packageTypes(String pkg, SourceLocation at) {
        if (packages.containsKey(pkg)) return packages.get(pkg);
        var types = new TreeSet<String>();
        String prefix = pkg.replace('.', '/') + "/";
        try {
            for (var source : sources) {
                if (accessible(source, pkg)) addPackageTypes(source, pkg, prefix, types);
            }
        } catch (IOException _) { throw new PageParseException(at, "Cannot inspect imported package " + pkg); }
        packages.put(pkg, Set.copyOf(types));
        return types;
    }

    private void addPackageTypes(Source source, String pkg, String prefix, Set<String> types) throws IOException {
        if (source.directory() != null) {
            Path directory = source.directory().resolve(prefix);
            if (!Files.isDirectory(directory)) return;
            try (var files = Files.list(directory)) {
                for (var path : files.filter(file -> file.toString().endsWith(CLASS_SUFFIX)).toList())
                    addType(types, ClassFile.of().parse(Files.readAllBytes(path)));
            }
        } else if (source.descriptor().packages().contains(pkg)) {
            try (var reader = source.reference().open(); var entries = reader.list()) {
                for (String entry : entries.filter(name -> name.startsWith(prefix) && name.endsWith(CLASS_SUFFIX)
                        && name.indexOf('/', prefix.length()) < 0).toList()) {
                    try (var stream = reader.open(entry).orElseThrow()) { addType(types, ClassFile.of().parse(stream.readAllBytes())); }
                }
            }
        }
    }

    private void addType(Set<String> types, ClassModel model) {
        String name = model.thisClass().asInternalName().replace('/', '.');
        models.putIfAbsent(name, model);
        if ((model.flags().flagsMask() & ClassFile.ACC_PUBLIC) == 0) return;
        boolean preview = java.util.stream.Stream.concat(
                model.findAttribute(Attributes.runtimeInvisibleAnnotations()).stream().flatMap(attribute -> attribute.annotations().stream()),
                model.findAttribute(Attributes.runtimeVisibleAnnotations()).stream().flatMap(attribute -> attribute.annotations().stream()))
                .anyMatch(annotation -> annotation.className().stringValue().equals("Ljdk/internal/javac/PreviewFeature;"));
        if (preview) return;
        var inners = model.findAttribute(Attributes.innerClasses());
        if (inners.isPresent() && inners.orElseThrow().classes().stream().anyMatch(inner ->
                inner.innerClass().equals(model.thisClass()) && inner.outerClass().isPresent())) return;
        types.add(name);
    }

    private ClassModel read(String binary, SourceLocation at) {
        if (models.containsKey(binary)) return models.get(binary);
        if (binary.lastIndexOf('.') < 0) return null;
        String entry = binary.replace('.', '/') + CLASS_SUFFIX;
        String pkg = binary.substring(0, binary.lastIndexOf('.'));
        try {
            for (var source : sources) {
                var bytes = accessible(source, pkg) ? bytes(source, entry) : Optional.<byte[]>empty();
                if (bytes.isPresent()) {
                    var model = ClassFile.of().parse(bytes.get());
                    models.put(binary, model);
                    return model;
                }
            }
        } catch (IOException _) { throw new PageParseException(at, "Cannot inspect imported type " + binary); }
        return null;
    }

    /** The class-file bytes of {@code entry} in {@code source}, or empty when the source has no such entry. */
    private static Optional<byte[]> bytes(Source source, String entry) throws IOException {
        if (source.directory() != null) {
            Path file = source.directory().resolve(entry);
            return Files.isRegularFile(file) ? Optional.of(Files.readAllBytes(file)) : Optional.empty();
        }
        try (var reader = source.reference().open()) {
            var stream = reader.open(entry);
            if (stream.isEmpty()) return Optional.empty();
            try (var input = stream.orElseThrow()) { return Optional.of(input.readAllBytes()); }
        }
    }

    private String explicit(String source, SourceLocation at) {
        String binary = source;
        ClassModel model;
        while ((model = read(binary, at)) == null) {
            int dot = binary.lastIndexOf('.');
            if (dot < 0) throw new PageParseException(at, "Imported type is unavailable or inaccessible: " + source);
            binary = binary.substring(0, dot) + "$" + binary.substring(dot + 1);
        }
        if ((model.flags().flagsMask() & ClassFile.ACC_PUBLIC) == 0)
            throw new PageParseException(at, "Imported type must be public: " + source);
        var inners = model.findAttribute(Attributes.innerClasses());
        if (inners.isPresent()) {
            for (var inner : inners.orElseThrow().classes()) {
                if (inner.innerClass().equals(model.thisClass()) && inner.outerClass().isPresent() && inner.innerName().isPresent())
                    return explicit(inner.outerClass().orElseThrow().asInternalName().replace('/', '.'), at) + "." + inner.innerName().orElseThrow().stringValue();
            }
        }
        return binary;
    }
}
