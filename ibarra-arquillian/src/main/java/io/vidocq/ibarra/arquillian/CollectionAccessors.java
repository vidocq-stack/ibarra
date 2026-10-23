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
import java.lang.classfile.*;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.constant.*;
import java.lang.module.ModuleFinder;
import java.nio.file.*;
import java.util.*;
import static java.lang.classfile.ClassFile.*;
import static java.lang.constant.ConstantDescs.*;
import static io.vidocq.ibarra.arquillian.BeanAccessors.*;

/** Build-only method tables for public java.base collections allocated by WAR bytecode. */
final class CollectionAccessors {
    private record Method(String name, MethodTypeDesc type) { }
    private static final String PACKAGE = "tck.generated.collections";
    private static final String ACCESSOR = PACKAGE + ".Accessor";
    private static final String PROVIDER = "tck.generated.CollectionAccessorProvider";
    private static final String METHODS = "METHODS";
    private static final String METHOD_NOT_FOUND = "jakarta.el.MethodNotFoundException";
    private static final String GET_VALUE = "getValue";
    private static final String GET_TYPE = "getType";
    private static final String IS_READ_ONLY = "isReadOnly";
    private static final String SET_VALUE = "setValue";
    private static final String GET_COMMON_PROPERTY_TYPE = "getCommonPropertyType";
    /** The property operations, which a collection table never resolves. */
    private static final List<String> PROPERTY_OPERATIONS = List.of(GET_VALUE, GET_TYPE, IS_READ_ONLY, SET_VALUE,
            GET_COMMON_PROPERTY_TYPE);
    private static final ClassDesc CONTEXT = ClassDesc.of("jakarta.el.ELContext");
    private static final ClassDesc TABLE = ClassDesc.of("io.vidocq.peano.spi.AccessorTable");
    private static final ClassDesc DESCRIPTOR = ClassDesc.of("io.vidocq.peano.spi.MethodDescriptor");
    private static final ClassDesc INFO = ClassDesc.of("jakarta.el.MethodInfo");
    private static final ClassDesc ANNOTATION = ClassDesc.of("java.lang.annotation.Annotation");
    private static final ClassDesc REFERENCE = ClassDesc.of("jakarta.el.MethodReference");
    private static final ClassDesc COLLECTION = ClassDesc.of("java.util.Collection");

    private CollectionAccessors() { }

    static BeanAccessors.Result generate(Path classes, List<Path> modules) throws IOException {
        var metadata = new BeanAccessors(classes, modules);
        var tables = new ArrayList<ClassDesc>();
        var unsupported = new TreeMap<String, String>();
        for (String target : allocatedCollections(classes)) {
            collectionTable(metadata, classes, target, unsupported).ifPresent(tables::add);
        }
        if (tables.isEmpty()) {
            return new BeanAccessors.Result(List.of(), List.of(), unsupported);
        }
        Path file = classes.resolve(PROVIDER.replace('.', '/') + CLASS_SUFFIX);
        if (Files.exists(file)) {
            throw new IOException("Generated collection provider collides with application class: " + PROVIDER);
        }
        Files.createDirectories(file.getParent());
        Files.write(file, provider(ClassDesc.of(PROVIDER), tables));
        return new BeanAccessors.Result(List.of(PROVIDER), List.of(), unsupported);
    }

    /** The {@code java.util} classes of {@code java.base} that the application bytecode allocates with {@code new}. */
    private static SortedSet<String> allocatedCollections(Path classes) throws IOException {
        var targets = new TreeSet<String>();
        var packages = ModuleFinder.ofSystem().find("java.base").orElseThrow().descriptor().packages();
        try (var paths = Files.walk(classes)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(CLASS_SUFFIX)).sorted().toList()) {
                addAllocations(ClassFile.of().parse(Files.readAllBytes(path)), packages, targets);
            }
        }
        return targets;
    }

    private static void addAllocations(ClassModel model, Set<String> packages, Set<String> targets) {
        for (var method : model.methods()) {
            method.code().ifPresent(code -> code.forEach(instruction -> {
                if (instruction instanceof NewObjectInstruction allocation) {
                    String binary = allocation.className().asInternalName().replace('/', '.');
                    int dot = binary.lastIndexOf('.');
                    if (binary.startsWith("java.util.") && dot > 0 && packages.contains(binary.substring(0, dot))) {
                        targets.add(binary);
                    }
                }
            }));
        }
    }

    /** Writes the method table of one public concrete collection class, when it has a supported method. */
    private static Optional<ClassDesc> collectionTable(BeanAccessors metadata, Path classes, String target,
            Map<String, String> unsupported) throws IOException {
        var model = metadata.read(target);
        if ((model.flags().flagsMask() & (ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE)) != ACC_PUBLIC
                || !metadata.assignable(ClassDesc.of(target), COLLECTION, new HashSet<>())) {
            return Optional.empty();
        }
        var methods = new TreeMap<String, Method>();
        var excluded = new TreeMap<String, String>();
        collect(metadata, target, methods, excluded, new HashSet<>());
        for (var method : methods.values()) {
            excludeInaccessible(metadata, method, excluded);
        }
        excluded.forEach((name, reason) -> unsupported.put(target + "#" + name, reason));
        var selected = methods.values().stream().filter(m -> !excluded.containsKey(m.name())).toList();
        if (selected.isEmpty()) {
            return Optional.empty();
        }
        var owner = ClassDesc.of(PACKAGE + "." + target.replace('.', '_') + "$$Methods");
        Path file = classes.resolve(internalName(owner) + CLASS_SUFFIX);
        if (Files.exists(file)) {
            throw new IOException("Generated collection accessor collides with application class: " + owner.displayName());
        }
        Files.createDirectories(file.getParent());
        Files.write(file, table(owner, ClassDesc.of(target), selected));
        return Optional.of(owner);
    }

    /** Excludes a method whose signature names a type the generated accessor package cannot access. */
    private static void excludeInaccessible(BeanAccessors metadata, Method method, Map<String, String> excluded) {
        try {
            metadata.signatureType(method.type().returnType(), ACCESSOR);
            for (var parameter : method.type().parameterList()) {
                metadata.signatureType(parameter, ACCESSOR);
            }
        } catch (IOException missing) {
            excluded.put(method.name(), missing.getMessage());
        }
    }

    private static void collect(BeanAccessors metadata, String binary, Map<String, Method> methods,
            Map<String, String> excluded, Set<String> visited) throws IOException {
        if (binary.equals("java.lang.Object") || !visited.add(binary)) {
            return;
        }
        var model = metadata.read(binary);
        for (var method : model.methods()) {
            if ((method.flags().flagsMask() & (ACC_PUBLIC | ACC_STATIC | ACC_SYNTHETIC)) == ACC_PUBLIC
                    && !method.methodName().stringValue().startsWith("<")) {
                addMethod(metadata, method, methods, excluded);
            }
        }
        for (var parent : model.interfaces()) {
            collect(metadata, binaryName(parent.asSymbol()), methods, excluded, visited);
        }
        var superclass = model.superclass();
        if (superclass.isPresent()) {
            collect(metadata, binaryName(superclass.orElseThrow().asSymbol()), methods, excluded, visited);
        }
    }

    /** Keeps the most specific override of each signature; varargs and annotated methods are excluded. */
    private static void addMethod(BeanAccessors metadata, MethodModel method, Map<String, Method> methods,
            Map<String, String> excluded) throws IOException {
        var candidate = new Method(method.methodName().stringValue(), method.methodTypeSymbol());
        if ((method.flags().flagsMask() & ACC_VARARGS) != 0 || method.findAttribute(Attributes.runtimeVisibleAnnotations()).isPresent()) {
            excluded.put(candidate.name(), "Varargs or runtime method annotations need additional generated metadata");
            return;
        }
        String key = candidate.name() + candidate.type().parameterList();
        var previous = methods.get(key);
        if (previous == null || metadata.assignable(candidate.type().returnType(), previous.type().returnType(), new HashSet<>())) {
            methods.put(key, candidate);
        }
    }

    private static byte[] table(ClassDesc owner, ClassDesc bean, List<Method> methods) {
        return ClassFile.of().build(owner, builder -> {
            builder.withFlags(ACC_PUBLIC | ACC_FINAL | ACC_SUPER).withSuperclass(TABLE);
            builder.withField(METHODS, DESCRIPTOR.arrayType(), ACC_PRIVATE | ACC_STATIC | ACC_FINAL);
            builder.withMethodBody(INIT_NAME, MTD_void, ACC_PUBLIC,
                    c -> c.aload(0).invokespecial(TABLE, INIT_NAME, MTD_void).return_());
            builder.withMethodBody("targetType", MethodTypeDesc.of(CD_Class), ACC_PUBLIC, c -> c.ldc(bean).areturn());
            builder.withMethodBody(CLASS_INIT_NAME, MTD_void, ACC_STATIC, c -> classInitializer(c, owner, methods));
            for (String operation : PROPERTY_OPERATIONS) {
                var type = propertyOperationType(operation);
                builder.withMethodBody(operation, type, ACC_PUBLIC, c -> {
                    c.aload(1).invokestatic(ClassDesc.of("java.util.Objects"), "requireNonNull", MethodTypeDesc.of(CD_Object, CD_Object)).pop();
                    fallback(c, type.returnType());
                });
            }
            builder.withMethodBody("getMethodReference",
                    MethodTypeDesc.of(REFERENCE, CONTEXT, CD_Object, CD_String, CD_Class.arrayType(), CD_Object.arrayType()),
                    ACC_PUBLIC, c -> methodReference(c, owner, bean, methods));
            builder.withMethodBody("invoke",
                    MethodTypeDesc.of(CD_Object, CONTEXT, CD_Object, CD_Object, CD_Class.arrayType(), CD_Object.arrayType()),
                    ACC_PUBLIC, c -> invoke(c, owner, bean, methods));
        });
    }

    private static MethodTypeDesc propertyOperationType(String operation) {
        return switch (operation) {
            case GET_TYPE -> MethodTypeDesc.of(CD_Class, CONTEXT, CD_Object, CD_Object);
            case IS_READ_ONLY -> MethodTypeDesc.of(CD_boolean, CONTEXT, CD_Object, CD_Object);
            case SET_VALUE -> MethodTypeDesc.of(CD_void, CONTEXT, CD_Object, CD_Object, CD_Object);
            case GET_COMMON_PROPERTY_TYPE -> MethodTypeDesc.of(CD_Class, CONTEXT, CD_Object);
            default -> MethodTypeDesc.of(CD_Object, CONTEXT, CD_Object, CD_Object);
        };
    }

    /** Fills the {@code METHODS} array with one {@code MethodDescriptor} per method. */
    private static void classInitializer(CodeBuilder c, ClassDesc owner, List<Method> methods) {
        c.loadConstant(methods.size()).anewarray(DESCRIPTOR);
        for (int i = 0; i < methods.size(); i++) {
            var method = methods.get(i);
            c.dup().loadConstant(i).new_(DESCRIPTOR).dup().ldc(method.name());
            classLiteral(c, method.type().returnType());
            c.loadConstant(method.type().parameterCount()).anewarray(CD_Class);
            for (int j = 0; j < method.type().parameterCount(); j++) {
                c.dup().loadConstant(j);
                classLiteral(c, method.type().parameterType(j));
                c.aastore();
            }
            c.iconst_0().iconst_0().iconst_0().anewarray(ANNOTATION)
                    .invokespecial(DESCRIPTOR, INIT_NAME, MethodTypeDesc.of(CD_void, CD_String, CD_Class, CD_Class.arrayType(),
                            CD_boolean, CD_boolean, ANNOTATION.arrayType()))
                    .aastore();
        }
        c.putstatic(owner, METHODS, DESCRIPTOR.arrayType()).return_();
    }

    /** {@code getMethodReference}: the selected method's reference, with a copy of the evaluated parameters. */
    private static void methodReference(CodeBuilder c, ClassDesc owner, ClassDesc bean, List<Method> methods) {
        var fallback = c.newLabel();
        accepts(c, bean, fallback, true);
        knownName(c, methods, fallback);
        select(c, owner, 5);
        c.istore(6).getstatic(owner, METHODS, DESCRIPTOR.arrayType()).iload(6).aaload().astore(7);
        c.new_(REFERENCE).dup().aload(2).aload(7).invokevirtual(DESCRIPTOR, "methodInfo", MethodTypeDesc.of(INFO))
                .aload(7).invokevirtual(DESCRIPTOR, "annotations", MethodTypeDesc.of(ANNOTATION.arrayType()));
        var absent = c.newLabel();
        var done = c.newLabel();
        c.aload(5).ifnull(absent).aload(5)
                .invokevirtual(CD_Object.arrayType(), "clone", MethodTypeDesc.of(CD_Object)).checkcast(CD_Object.arrayType()).goto_(done);
        c.labelBinding(absent).iconst_0().anewarray(CD_Object).labelBinding(done)
                .invokespecial(REFERENCE, INIT_NAME, MethodTypeDesc.of(CD_void, CD_Object, INFO, ANNOTATION.arrayType(), CD_Object.arrayType()))
                .areturn();
        c.labelBinding(fallback).aconst_null().areturn();
    }

    /** {@code invoke}: a branch per method index, failures wrapped in an {@code ELException}. */
    private static void invoke(CodeBuilder c, ClassDesc owner, ClassDesc bean, List<Method> methods) {
        var fallback = c.newLabel();
        accepts(c, bean, fallback, true);
        knownName(c, methods, fallback);
        c.aload(1).aload(2).aload(3).invokevirtual(CONTEXT, "setPropertyResolved", MethodTypeDesc.of(CD_void, CD_Object, CD_Object));
        var absent = c.newLabel();
        var args = c.newLabel();
        c.aload(5).ifnull(absent).aload(5).goto_(args);
        c.labelBinding(absent).iconst_0().anewarray(CD_Object).labelBinding(args).astore(6);
        select(c, owner, 6);
        c.istore(7);
        var start = c.newLabel();
        var end = c.newLabel();
        var caught = c.newLabel();
        c.labelBinding(start);
        for (int i = 0; i < methods.size(); i++) {
            invokeCase(c, bean, methods.get(i), i);
        }
        fail(c, METHOD_NOT_FOUND, "Unknown generated method descriptor");
        c.labelBinding(end);
        c.labelBinding(caught).astore(8);
        rethrowAsElException(c, 8, "Generated collection method invocation failed");
        c.exceptionCatch(start, end, caught, CD_Throwable);
        c.labelBinding(fallback).aconst_null().areturn();
    }

    /** The branch of method {@code index}: argument count check, coerced arguments, direct call, boxed result. */
    private static void invokeCase(CodeBuilder c, ClassDesc bean, Method method, int index) {
        var next = c.newLabel();
        c.iload(7).loadConstant(index).if_icmpne(next);
        var count = c.newLabel();
        c.aload(6).arraylength().loadConstant(method.type().parameterCount()).if_icmpeq(count);
        fail(c, METHOD_NOT_FOUND, "Invalid argument count");
        c.labelBinding(count).aload(2).checkcast(bean);
        for (int j = 0; j < method.type().parameterCount(); j++) {
            var type = method.type().parameterType(j);
            c.aload(1).aload(6).loadConstant(j).aaload();
            classLiteral(c, type);
            c.invokevirtual(CONTEXT, "convertToType", MethodTypeDesc.of(CD_Object, CD_Object, CD_Class));
            unbox(c, type);
        }
        c.invokevirtual(bean, method.name(), method.type());
        if (method.type().returnType().equals(CD_void)) {
            c.aconst_null();
        } else {
            box(c, method.type().returnType());
        }
        c.areturn().labelBinding(next);
    }

    private static void knownName(CodeBuilder c, List<Method> methods, Label fallback) {
        var found = c.newLabel();
        for (String name : methods.stream().map(Method::name).distinct().toList()) {
            c.ldc(name).aload(3).invokevirtual(CD_String, "equals", MethodTypeDesc.of(CD_boolean, CD_Object)).ifne(found);
        }
        c.goto_(fallback).labelBinding(found);
    }

    private static void select(CodeBuilder c, ClassDesc owner, int arguments) {
        c.aload(1).getstatic(owner, METHODS, DESCRIPTOR.arrayType()).aload(3).checkcast(CD_String).aload(4).aload(arguments).iconst_0()
                .invokestatic(ClassDesc.of("io.vidocq.peano.spi.MethodSelection"), "select", MethodTypeDesc.of(CD_int, CONTEXT,
                        DESCRIPTOR.arrayType(), CD_String, CD_Class.arrayType(), CD_Object.arrayType(), CD_boolean));
    }
}
