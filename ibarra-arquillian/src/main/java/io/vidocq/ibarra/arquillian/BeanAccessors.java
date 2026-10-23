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

import java.io.File;
import java.io.IOException;
import java.lang.classfile.*;
import java.lang.constant.*;
import java.lang.module.*;
import java.nio.file.*;
import java.util.*;
import static java.lang.classfile.ClassFile.*;
import static java.lang.constant.ConstantDescs.*;

/** Build-only property tables for the legacy WAR's own public bean types. */
final class BeanAccessors {
    private record Method(String name, MethodTypeDesc type) { }
    private record Property(String name, Method getter, Method setter) { }
    record Result(List<String> providers, List<String> beanPackages, Map<String, String> unsupported) {
        Result {
            providers = List.copyOf(providers);
            beanPackages = beanPackages.stream().distinct().sorted().toList();
            unsupported = Map.copyOf(unsupported);
        }
    }
    private static final class UnavailableType extends IOException {
        UnavailableType(String message) {
            super(message);
        }
    }

    /** The file name suffix of a class file. */
    static final String CLASS_SUFFIX = ".class";
    private static final String MODULE_INFO = "module-info" + CLASS_SUFFIX;
    private static final String PROVIDER = "tck.generated.BeanAccessorProvider";
    private static final String GET_VALUE = "getValue";
    private static final String GET_TYPE = "getType";
    private static final String IS_READ_ONLY = "isReadOnly";
    private static final String SET_VALUE = "setValue";
    private static final List<String> OPERATIONS = List.of(GET_VALUE, GET_TYPE, IS_READ_ONLY, SET_VALUE);
    private static final ClassDesc LIST = ClassDesc.of("java.util.List");
    /** The bean types EL resolves without a property table: maps, lists, resource bundles and records. */
    private static final List<ClassDesc> RESOLVED_ELSEWHERE = List.of(ClassDesc.of("java.util.Map"),
            LIST, ClassDesc.of("java.util.ResourceBundle"), ClassDesc.of("java.lang.Record"));
    private static final ClassDesc CONTEXT = ClassDesc.of("jakarta.el.ELContext");
    private static final ClassDesc TABLE = ClassDesc.of("io.vidocq.peano.spi.AccessorTable");
    private static final ClassDesc EL_EXCEPTION = ClassDesc.of("jakarta.el.ELException");

    private final Path classes;
    private final ModuleDescriptor application;
    private final Map<String, ModuleReference> modules = new TreeMap<>();
    private final Set<String> readable = new HashSet<>();
    private final Map<String, ClassModel> models = new HashMap<>();

    BeanAccessors(Path classes, List<Path> paths) throws IOException {
        this.classes = classes;
        try (var input = Files.newInputStream(classes.resolve(MODULE_INFO))) {
            application = ModuleDescriptor.read(input);
        }
        for (var reference : ModuleFinder.ofSystem().findAll()) {
            modules.put(reference.descriptor().name(), reference);
        }
        for (var reference : ModuleFinder.of(paths.toArray(Path[]::new)).findAll()) {
            modules.put(reference.descriptor().name(), reference);
        }
        resolveReadable();
    }

    /** The modules the application reads: its non-static requirements and their transitive closure. */
    private void resolveReadable() throws IOException {
        var pending = new ArrayDeque<String>();
        for (var requirement : application.requires()) {
            if (!isStatic(requirement)) {
                pending.add(requirement.name());
            }
        }
        while (!pending.isEmpty()) {
            String name = pending.remove();
            if (readable.add(name)) {
                pending.addAll(transitiveRequirements(name));
            }
        }
    }

    private List<String> transitiveRequirements(String name) throws IOException {
        var reference = modules.get(name);
        if (reference == null) {
            throw new IOException("Required bean-accessor module is absent: " + name);
        }
        var names = new ArrayList<String>();
        for (var requirement : reference.descriptor().requires()) {
            if (requirement.modifiers().contains(ModuleDescriptor.Requires.Modifier.TRANSITIVE) && !isStatic(requirement)) {
                names.add(requirement.name());
            }
        }
        return names;
    }

    private static boolean isStatic(ModuleDescriptor.Requires requirement) {
        return requirement.modifiers().contains(ModuleDescriptor.Requires.Modifier.STATIC);
    }

    static Result generate(Path classes, List<Path> modules) throws IOException {
        var generator = new BeanAccessors(classes, modules);
        var tables = new ArrayList<ClassDesc>();
        var beanPackages = new TreeSet<String>();
        var unsupported = new TreeMap<String, String>();
        for (Path file : applicationClassFiles(classes)) {
            var model = parseApplicationClass(classes, file);
            if (isPublicConcreteClass(model)) {
                String binary = binaryName(model.thisClass().asSymbol());
                var properties = generator.beanProperties(model, binary, unsupported);
                if (properties.stream().anyMatch(property -> !property.name().equals("class"))) {
                    tables.add(writeTable(classes, model, binary, properties));
                    beanPackages.add(packageOf(binary));
                }
            }
        }
        if (tables.isEmpty()) {
            return new Result(List.of(), List.of(), unsupported);
        }
        Path file = classes.resolve(PROVIDER.replace('.', '/') + CLASS_SUFFIX);
        if (Files.exists(file)) {
            throw new IOException("Generated bean provider collides with application class: " + PROVIDER);
        }
        Files.createDirectories(file.getParent());
        Files.write(file, provider(ClassDesc.of(PROVIDER), tables));
        return new Result(List.of(PROVIDER), List.copyOf(beanPackages), unsupported);
    }

    private static List<Path> applicationClassFiles(Path classes) throws IOException {
        try (var walk = Files.walk(classes)) {
            return walk.filter(p -> p.toString().endsWith(CLASS_SUFFIX) && !p.getFileName().toString().equals(MODULE_INFO))
                    .sorted().toList();
        }
    }

    private static ClassModel parseApplicationClass(Path classes, Path file) throws IOException {
        var model = ClassFile.of().parse(Files.readAllBytes(file));
        String relative = classes.relativize(file).toString().replace(File.separatorChar, '/');
        if (!relative.equals(model.thisClass().asInternalName() + CLASS_SUFFIX)) {
            throw new IOException("WAR bean binary name does not match its class path: " + file);
        }
        return model;
    }

    private static boolean isPublicConcreteClass(ClassModel model) {
        return (model.flags().flagsMask() & (ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT)) == ACC_PUBLIC;
    }

    /**
     * The properties of a bean type; none when EL resolves the type elsewhere, or when a type the bean needs is
     * unavailable, which {@code unsupported} then records.
     */
    private List<Property> beanProperties(ClassModel model, String binary, Map<String, String> unsupported)
            throws IOException {
        try {
            if (resolvedElsewhere(model.thisClass().asSymbol())) {
                return List.of();
            }
            var properties = properties(binary);
            for (var property : properties) {
                if (property.getter() != null) {
                    signatureType(property.getter().type().returnType(), binary);
                }
                if (property.setter() != null) {
                    signatureType(property.setter().type().parameterType(0), binary);
                }
            }
            return properties;
        } catch (UnavailableType missing) {
            unsupported.put(binary, missing.getMessage());
            return List.of();
        }
    }

    private boolean resolvedElsewhere(ClassDesc bean) throws IOException {
        for (var type : RESOLVED_ELSEWHERE) {
            if (assignable(bean, type, new HashSet<>())) {
                return true;
            }
        }
        return false;
    }

    private static ClassDesc writeTable(Path classes, ClassModel model, String binary, List<Property> properties)
            throws IOException {
        ClassDesc owner = ClassDesc.of(binary + "$$IbarraProperties");
        Path target = classes.resolve(internalName(owner) + CLASS_SUFFIX);
        if (Files.exists(target)) {
            throw new IOException("Generated bean accessor collides with application class: " + owner.displayName());
        }
        Files.write(target, table(owner, model.thisClass().asSymbol(), properties));
        return owner;
    }

    private List<Property> properties(String binary) throws IOException {
        var methods = new ArrayList<Method>();
        collect(binary, methods, new HashSet<>());
        var getters = booleanGetters(methods);
        var setters = new TreeMap<String, List<Method>>();
        for (var method : methods) {
            if (isAccessor(method, "get", 0) && !method.type().returnType().equals(CD_void)) {
                selectGetter(getters, property(method.name().substring(3)), method);
            }
            if (isAccessor(method, "set", 1) && method.type().returnType().equals(CD_void)) {
                setters.computeIfAbsent(property(method.name().substring(3)), ignored -> new ArrayList<>()).add(method);
            }
        }
        var names = new TreeSet<>(getters.keySet());
        names.addAll(setters.keySet());
        var result = new ArrayList<Property>();
        for (String name : names) {
            var getter = getters.get(name);
            var setter = selectSetter(getter, setters.getOrDefault(name, List.of()));
            if (getter != null || setter != null) {
                result.add(new Property(name, getter, setter));
            }
        }
        return List.copyOf(result);
    }

    /** The {@code is} getters returning {@code boolean}, by property name; the first one found wins. */
    private static Map<String, Method> booleanGetters(List<Method> methods) {
        var getters = new TreeMap<String, Method>();
        for (var method : methods) {
            if (isAccessor(method, "is", 0) && method.type().returnType().equals(CD_boolean)) {
                getters.putIfAbsent(property(method.name().substring(2)), method);
            }
        }
        return getters;
    }

    /** Whether {@code method} is named {@code prefix} plus a property name and takes {@code parameters} parameters. */
    private static boolean isAccessor(Method method, String prefix, int parameters) {
        return method.name().startsWith(prefix) && method.name().length() > prefix.length()
                && method.type().parameterCount() == parameters;
    }

    private void selectGetter(Map<String, Method> getters, String name, Method candidate) throws IOException {
        var previous = getters.get(name);
        if (previous != null && previous.name().startsWith("is") && previous.type().returnType().equals(CD_boolean)) {
            return;
        }
        if (previous == null || assignable(candidate.type().returnType(), previous.type().returnType(), new HashSet<>())) {
            getters.put(name, candidate);
        }
    }

    /** The most specific setter the getter type accepts, or {@code null}. */
    private Method selectSetter(Method getter, List<Method> candidates) throws IOException {
        var unique = new LinkedHashMap<ClassDesc, Method>();
        for (var setter : candidates) {
            if (getter == null || assignable(setter.type().parameterType(0), getter.type().returnType(), new HashSet<>())) {
                unique.putIfAbsent(setter.type().parameterType(0), setter);
            }
        }
        for (var candidate : unique.values()) {
            if (mostSpecific(candidate, unique.values())) {
                return candidate;
            }
        }
        return null;
    }

    /** Whether every other setter accepts {@code candidate}'s parameter; every comparison runs, so each type is read. */
    private boolean mostSpecific(Method candidate, Collection<Method> setters) throws IOException {
        boolean mostSpecific = true;
        for (var other : setters) {
            if (!assignable(candidate.type().parameterType(0), other.type().parameterType(0), new HashSet<>())) {
                mostSpecific = false;
            }
        }
        return mostSpecific;
    }

    private void collect(String binary, List<Method> result, Set<String> visited) throws IOException {
        if (!visited.add(binary)) {
            return;
        }
        var model = read(binary);
        for (var method : model.methods()) {
            if ((method.flags().flagsMask() & (ACC_PUBLIC | ACC_STATIC | ACC_SYNTHETIC)) == ACC_PUBLIC) {
                result.add(new Method(method.methodName().stringValue(), method.methodTypeSymbol()));
            }
        }
        for (var parent : model.interfaces()) {
            collect(binaryName(parent.asSymbol()), result, visited);
        }
        if (model.superclass().isPresent()) {
            collect(binaryName(model.superclass().get().asSymbol()), result, visited);
        }
    }

    boolean assignable(ClassDesc value, ClassDesc target, Set<String> visited) throws IOException {
        if (value.equals(target)) {
            return true;
        }
        if (value.isPrimitive() || target.isPrimitive()) {
            return false;
        }
        if (target.equals(CD_Object)) {
            return true;
        }
        if (value.isArray()) {
            if (target.isArray()) {
                return assignable(value.componentType(), target.componentType(), visited);
            }
            return target.equals(ClassDesc.of("java.lang.Cloneable")) || target.equals(ClassDesc.of("java.io.Serializable"));
        }
        if (target.isArray() || !visited.add(value.descriptorString())) {
            return false;
        }
        var model = read(binaryName(value));
        for (var parent : model.interfaces()) {
            if (assignable(parent.asSymbol(), target, visited)) {
                return true;
            }
        }
        return model.superclass().isPresent() && assignable(model.superclass().get().asSymbol(), target, visited);
    }

    ClassModel read(String binary) throws IOException {
        var cached = models.get(binary);
        if (cached != null) {
            return cached;
        }
        String relative = binary.replace('.', '/') + CLASS_SUFFIX;
        Path local = classes.resolve(relative);
        var model = Files.isRegularFile(local) ? ClassFile.of().parse(Files.readAllBytes(local)) : readFromModules(binary, relative);
        models.put(binary, model);
        return model;
    }

    /** Reads a class the application can access from the modules; an inaccessible copy only explains the failure. */
    private ClassModel readFromModules(String binary, String relative) throws IOException {
        String pkg = packageOf(binary);
        String inaccessible = null;
        for (var reference : modules.values()) {
            var bytes = reference.descriptor().packages().contains(pkg) ? classBytes(reference, relative) : Optional.<byte[]>empty();
            if (bytes.isPresent()) {
                if (accessible(reference.descriptor(), pkg)) {
                    return ClassFile.of().parse(bytes.get());
                }
                inaccessible = "Bean type is not readable/exported to " + application.name() + ": " + binary;
            }
        }
        if (inaccessible != null) {
            throw new UnavailableType(inaccessible);
        }
        throw new UnavailableType("Bean type absent from build module path: " + binary);
    }

    private static Optional<byte[]> classBytes(ModuleReference reference, String relative) throws IOException {
        try (var reader = reference.open()) {
            var found = reader.open(relative);
            if (found.isEmpty()) {
                return Optional.empty();
            }
            try (var input = found.get()) {
                return Optional.of(input.readAllBytes());
            }
        }
    }

    private boolean accessible(ModuleDescriptor descriptor, String pkg) {
        return readable.contains(descriptor.name()) && descriptor.exports().stream().anyMatch(export -> export.source().equals(pkg)
                && (!export.isQualified() || export.targets().contains(application.name())));
    }

    void signatureType(ClassDesc type, String bean) throws IOException {
        var element = type;
        while (element.isArray()) {
            element = element.componentType();
        }
        if (element.isPrimitive()) {
            return;
        }
        String binary = binaryName(element);
        var model = read(binary);
        if ((model.flags().flagsMask() & ACC_PUBLIC) == 0 && !packageOf(binary).equals(packageOf(bean))) {
            throw new UnavailableType("Bean signature type is inaccessible to its accessor package: " + binary);
        }
    }

    private static String property(String suffix) {
        return suffix.length() > 1 && Character.isUpperCase(suffix.charAt(0)) && Character.isUpperCase(suffix.charAt(1))
                ? suffix : Character.toLowerCase(suffix.charAt(0)) + suffix.substring(1);
    }

    /** The internal name of a class or interface type: {@code a/b/C}. */
    static String internalName(ClassDesc type) {
        return type.descriptorString().substring(1, type.descriptorString().length() - 1);
    }

    /** The binary name of a class or interface type: {@code a.b.C}. */
    static String binaryName(ClassDesc type) {
        return internalName(type).replace('/', '.');
    }

    private static String packageOf(String binary) {
        return binary.substring(0, binary.lastIndexOf('.'));
    }

    private static byte[] table(ClassDesc owner, ClassDesc bean, List<Property> properties) {
        return ClassFile.of().build(owner, builder -> {
            builder.withFlags(ACC_PUBLIC | ACC_FINAL | ACC_SUPER).withSuperclass(TABLE);
            builder.withMethodBody(INIT_NAME, MTD_void, ACC_PUBLIC,
                    code -> code.aload(0).invokespecial(TABLE, INIT_NAME, MTD_void).return_());
            builder.withMethodBody("targetType", MethodTypeDesc.of(CD_Class), ACC_PUBLIC, code -> code.ldc(bean).areturn());
            for (String operation : OPERATIONS) {
                ClassDesc result = resultType(operation);
                var type = operation.equals(SET_VALUE) ? MethodTypeDesc.of(result, CONTEXT, CD_Object, CD_Object, CD_Object)
                        : MethodTypeDesc.of(result, CONTEXT, CD_Object, CD_Object);
                builder.withMethodBody(operation, type, ACC_PUBLIC,
                        code -> propertyOperation(code, operation, result, bean, properties));
            }
            builder.withMethodBody("getCommonPropertyType", MethodTypeDesc.of(CD_Class, CONTEXT, CD_Object), ACC_PUBLIC, code -> {
                var fallback = code.newLabel();
                accepts(code, bean, fallback, false);
                code.ldc(CD_String).areturn();
                code.labelBinding(fallback).aconst_null().areturn();
            });
        });
    }

    private static ClassDesc resultType(String operation) {
        return switch (operation) {
            case GET_TYPE -> CD_Class;
            case IS_READ_ONLY -> CD_boolean;
            case SET_VALUE -> CD_void;
            default -> CD_Object;
        };
    }

    /** One property operation: a branch per property name, failures wrapped in an {@code ELException}. */
    private static void propertyOperation(CodeBuilder code, String operation, ClassDesc result, ClassDesc bean,
            List<Property> properties) {
        var fallback = code.newLabel();
        accepts(code, bean, fallback, true);
        var start = code.newLabel();
        var end = code.newLabel();
        var caught = code.newLabel();
        code.labelBinding(start);
        for (var property : properties) {
            var next = code.newLabel();
            code.ldc(property.name()).aload(3).invokevirtual(CD_String, "equals", MethodTypeDesc.of(CD_boolean, CD_Object)).ifeq(next);
            code.aload(1).aload(2).aload(3).invokevirtual(CONTEXT, "setPropertyResolved", MethodTypeDesc.of(CD_void, CD_Object, CD_Object));
            propertyCase(code, operation, bean, property);
            code.labelBinding(next);
        }
        code.labelBinding(end).goto_(fallback);
        code.labelBinding(caught).astore(5);
        rethrowAsElException(code, 5, "Generated bean property operation failed");
        code.exceptionCatch(start, end, caught, CD_Throwable);
        code.labelBinding(fallback);
        fallback(code, result);
    }

    private static void propertyCase(CodeBuilder code, String operation, ClassDesc bean, Property property) {
        switch (operation) {
            case GET_VALUE -> getValue(code, bean, property);
            case GET_TYPE -> {
                if (property.setter() == null) {
                    code.aconst_null();
                } else {
                    classLiteral(code, property.setter().type().parameterType(0));
                }
                code.areturn();
            }
            case IS_READ_ONLY -> code.loadConstant(property.setter() == null ? 1 : 0).ireturn();
            case SET_VALUE -> setValue(code, bean, property);
            default -> throw new IllegalArgumentException("Unknown property operation " + operation);
        }
    }

    private static void getValue(CodeBuilder code, ClassDesc bean, Property property) {
        if (property.getter() == null) {
            fail(code, "jakarta.el.PropertyNotFoundException", "Write-only property");
            return;
        }
        code.aload(2).checkcast(bean).invokevirtual(bean, property.getter().name(), property.getter().type());
        box(code, property.getter().type().returnType());
        code.areturn();
    }

    private static void setValue(CodeBuilder code, ClassDesc bean, Property property) {
        if (property.setter() == null) {
            fail(code, "jakarta.el.PropertyNotWritableException", "Read-only property");
            return;
        }
        var input = property.setter().type().parameterType(0);
        code.aload(2).checkcast(bean).aload(1).aload(4);
        classLiteral(code, input);
        code.invokevirtual(CONTEXT, "convertToType", MethodTypeDesc.of(CD_Object, CD_Object, CD_Class));
        unbox(code, input);
        code.invokevirtual(bean, property.setter().name(), property.setter().type()).return_();
    }

    /** Rethrows the throwable in local {@code slot} when it is an {@code ELException}, wraps it in one otherwise. */
    static void rethrowAsElException(CodeBuilder code, int slot, String message) {
        var wrap = code.newLabel();
        code.aload(slot).instanceOf(EL_EXCEPTION).ifeq(wrap).aload(slot).athrow();
        code.labelBinding(wrap).new_(EL_EXCEPTION).dup().ldc(message).aload(slot)
                .invokespecial(EL_EXCEPTION, INIT_NAME, MethodTypeDesc.of(CD_void, CD_String, CD_Throwable)).athrow();
    }

    static void accepts(CodeBuilder code, ClassDesc bean, Label fallback, boolean property) {
        code.aload(1).invokestatic(ClassDesc.of("java.util.Objects"), "requireNonNull", MethodTypeDesc.of(CD_Object, CD_Object)).pop();
        code.aload(2).ifnull(fallback).aload(2).invokevirtual(CD_Object, "getClass", MethodTypeDesc.of(CD_Class)).ldc(bean).if_acmpne(fallback);
        if (property) {
            code.aload(3).instanceOf(CD_String).ifeq(fallback);
        }
    }

    /** Returns the default value of {@code type}: nothing, {@code false} or {@code null}. */
    static void fallback(CodeBuilder code, ClassDesc type) {
        if (type.equals(CD_void)) {
            code.return_();
        } else if (type.equals(CD_boolean)) {
            code.iconst_0().ireturn();
        } else {
            code.aconst_null().areturn();
        }
    }

    static void fail(CodeBuilder code, String type, String message) {
        var exception = ClassDesc.of(type);
        code.new_(exception).dup().ldc(message).invokespecial(exception, INIT_NAME, MethodTypeDesc.of(CD_void, CD_String)).athrow();
    }

    private static ClassDesc wrapper(ClassDesc type) {
        return ClassDesc.of(switch (type.descriptorString()) {
            case "Z" -> "java.lang.Boolean";
            case "B" -> "java.lang.Byte";
            case "C" -> "java.lang.Character";
            case "S" -> "java.lang.Short";
            case "I" -> "java.lang.Integer";
            case "J" -> "java.lang.Long";
            case "F" -> "java.lang.Float";
            case "D" -> "java.lang.Double";
            default -> throw new IllegalArgumentException("Unsupported primitive " + type);
        });
    }

    static void classLiteral(CodeBuilder code, ClassDesc type) {
        if (type.equals(CD_void)) {
            code.getstatic(ClassDesc.of("java.lang.Void"), "TYPE", CD_Class);
        } else if (type.isPrimitive()) {
            code.getstatic(wrapper(type), "TYPE", CD_Class);
        } else {
            code.ldc(type);
        }
    }

    static void box(CodeBuilder code, ClassDesc type) {
        if (type.isPrimitive()) {
            code.invokestatic(wrapper(type), "valueOf", MethodTypeDesc.of(wrapper(type), type));
        }
    }

    static void unbox(CodeBuilder code, ClassDesc type) {
        if (!type.isPrimitive()) {
            code.checkcast(type);
            return;
        }
        var boxed = wrapper(type);
        String method = switch (type.descriptorString()) {
            case "I" -> "intValue";
            case "C" -> "charValue";
            case "Z" -> "booleanValue";
            case "B" -> "byteValue";
            case "S" -> "shortValue";
            case "J" -> "longValue";
            case "F" -> "floatValue";
            case "D" -> "doubleValue";
            default -> throw new IllegalArgumentException("Unsupported primitive " + type);
        };
        code.checkcast(boxed).invokevirtual(boxed, method, MethodTypeDesc.of(type));
    }

    /** A provider class whose {@code accessors()} returns one new instance of each table. */
    static byte[] provider(ClassDesc owner, List<ClassDesc> tables) {
        return ClassFile.of().build(owner, builder -> {
            builder.withFlags(ACC_PUBLIC | ACC_FINAL | ACC_SUPER).withInterfaceSymbols(ClassDesc.of("io.vidocq.peano.spi.AccessorProvider"));
            builder.withMethodBody(INIT_NAME, MTD_void, ACC_PUBLIC,
                    code -> code.aload(0).invokespecial(CD_Object, INIT_NAME, MTD_void).return_());
            builder.withMethodBody("accessors", MethodTypeDesc.of(ClassDesc.of("java.util.Collection")), ACC_PUBLIC, code -> {
                code.loadConstant(tables.size()).anewarray(TABLE);
                for (int i = 0; i < tables.size(); i++) {
                    code.dup().loadConstant(i).new_(tables.get(i)).dup().invokespecial(tables.get(i), INIT_NAME, MTD_void).aastore();
                }
                code.invokestatic(LIST, "of",
                        MethodTypeDesc.of(LIST, CD_Object.arrayType()), true).areturn();
            });
        });
    }
}
