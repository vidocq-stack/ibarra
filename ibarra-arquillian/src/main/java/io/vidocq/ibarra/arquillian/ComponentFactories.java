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
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeBuilder;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import javax.lang.model.SourceVersion;
import static java.lang.classfile.ClassFile.ACC_FINAL;
import static java.lang.classfile.ClassFile.ACC_PUBLIC;
import static java.lang.classfile.ClassFile.ACC_SUPER;
import static java.lang.constant.ConstantDescs.*;

/** Build-only direct constructor providers for components explicitly declared in web.xml. */
final class ComponentFactories {
    private static final ClassDesc COMPONENT = ClassDesc.of("io.vidocq.foy.spi.gen.WebComponent");
    private static final ClassDesc DESCRIPTOR = ClassDesc.of("io.vidocq.foy.spi.gen.WebComponentDescriptor");

    private ComponentFactories() { }

    static List<String> generate(Path classes, Collection<String> names) throws IOException {
        var providers = new ArrayList<String>();
        for (String name : new TreeSet<>(names)) {
            checkComponent(classes, name);
            String provider = "tck.generated.Component" + providers.size();
            Path target = classes.resolve(provider.replace('.', '/') + BeanAccessors.CLASS_SUFFIX);
            if (Files.exists(target)) {
                throw new IOException("Generated component factory collides with application class: " + provider);
            }
            byte[] bytes = factory(ClassDesc.of(provider), ClassDesc.of(name), name);
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
            providers.add(provider);
        }
        return List.copyOf(providers);
    }

    /** Refuses a name that is not a binary name in a package, or a class that is not a public concrete one with a public no-arg constructor. */
    private static void checkComponent(Path classes, String name) throws IOException {
        if (!SourceVersion.isName(name) || name.indexOf('.') < 0) {
            throw new IOException("Invalid component binary name: " + name);
        }
        Path file = classes.resolve(name.replace('.', '/') + BeanAccessors.CLASS_SUFFIX);
        if (!Files.isRegularFile(file)) {
            throw new IOException("Component class is missing from WEB-INF/classes: " + name);
        }
        var model = ClassFile.of().parse(Files.readAllBytes(file));
        if (!model.thisClass().asSymbol().equals(ClassDesc.of(name)) || !isPublicConcreteClass(model) || !hasPublicNoArgConstructor(model)) {
            throw new IOException("Component factory requires a public concrete class with a public no-arg constructor: " + name);
        }
    }

    private static boolean isPublicConcreteClass(ClassModel model) {
        return model.flags().has(AccessFlag.PUBLIC) && !model.flags().has(AccessFlag.ABSTRACT) && !model.flags().has(AccessFlag.INTERFACE);
    }

    private static boolean hasPublicNoArgConstructor(ClassModel model) {
        return model.methods().stream().anyMatch(m -> m.methodName().equalsString(INIT_NAME)
                && m.methodType().equalsString("()V") && m.flags().has(AccessFlag.PUBLIC));
    }

    /** The factory class: {@code type()}, a plain {@code descriptor()} and {@code newInstance()} calling the constructor. */
    private static byte[] factory(ClassDesc owner, ClassDesc component, String name) {
        return ClassFile.of().build(owner, builder -> {
            builder.withFlags(ACC_PUBLIC | ACC_FINAL | ACC_SUPER).withInterfaceSymbols(COMPONENT);
            builder.withMethodBody(INIT_NAME, MTD_void, ACC_PUBLIC,
                    code -> code.aload(0).invokespecial(CD_Object, INIT_NAME, MTD_void).return_());
            builder.withMethodBody("type", MethodTypeDesc.of(CD_Class), ACC_PUBLIC,
                    code -> code.ldc(component).areturn());
            builder.withMethodBody("descriptor", MethodTypeDesc.of(DESCRIPTOR), ACC_PUBLIC,
                    code -> code.invokestatic(DESCRIPTOR, "plain", MethodTypeDesc.of(DESCRIPTOR)).areturn());
            builder.withMethodBody("newInstance", MethodTypeDesc.of(CD_Object), ACC_PUBLIC,
                    code -> newInstance(code, component, name));
        });
    }

    /** Calls the constructor; runtime exceptions and errors pass through, checked throwables are wrapped. */
    private static void newInstance(CodeBuilder code, ClassDesc component, String name) {
        var start = code.newLabel();
        var end = code.newLabel();
        var unchecked = code.newLabel();
        var checked = code.newLabel();
        code.labelBinding(start).new_(component).dup()
                .invokespecial(component, INIT_NAME, MTD_void)
                .labelBinding(end).areturn();
        code.labelBinding(unchecked).athrow();
        var failure = ClassDesc.of("java.lang.IllegalStateException");
        code.labelBinding(checked).astore(1).new_(failure).dup()
                .ldc("Cannot instantiate component " + name).aload(1)
                .invokespecial(failure, INIT_NAME, MethodTypeDesc.of(CD_void, CD_String, CD_Throwable)).athrow();
        code.exceptionCatch(start, end, unchecked, ClassDesc.of("java.lang.RuntimeException"));
        code.exceptionCatch(start, end, unchecked, ClassDesc.of("java.lang.Error"));
        code.exceptionCatch(start, end, checked, CD_Throwable);
    }
}
