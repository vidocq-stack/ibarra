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
            if (!SourceVersion.isName(name) || name.indexOf('.') < 0)
                throw new IOException("Invalid component binary name: " + name);
            Path file = classes.resolve(name.replace('.', '/') + ".class");
            if (!Files.isRegularFile(file)) throw new IOException("Component class is missing from WEB-INF/classes: " + name);
            var model = ClassFile.of().parse(Files.readAllBytes(file));
            if (!model.thisClass().asSymbol().equals(ClassDesc.of(name))
                    || !model.flags().has(AccessFlag.PUBLIC) || model.flags().has(AccessFlag.ABSTRACT) || model.flags().has(AccessFlag.INTERFACE)
                    || model.methods().stream().noneMatch(m -> m.methodName().equalsString("<init>")
                            && m.methodType().equalsString("()V") && m.flags().has(AccessFlag.PUBLIC)))
                throw new IOException("Component factory requires a public concrete class with a public no-arg constructor: " + name);
            String provider = "tck.generated.Component" + providers.size();
            Path target = classes.resolve(provider.replace('.', '/') + ".class");
            if (Files.exists(target)) throw new IOException("Generated component factory collides with application class: " + provider);
            ClassDesc owner = ClassDesc.of(provider);
            ClassDesc component = ClassDesc.of(name);
            byte[] bytes = ClassFile.of().build(owner, builder -> {
                builder.withFlags(ACC_PUBLIC | ACC_FINAL | ACC_SUPER).withInterfaceSymbols(COMPONENT);
                builder.withMethodBody("<init>", MethodTypeDesc.of(CD_void), ACC_PUBLIC,
                        code -> code.aload(0).invokespecial(CD_Object, "<init>", MethodTypeDesc.of(CD_void)).return_());
                builder.withMethodBody("type", MethodTypeDesc.of(CD_Class), ACC_PUBLIC,
                        code -> code.ldc(component).areturn());
                builder.withMethodBody("descriptor", MethodTypeDesc.of(DESCRIPTOR), ACC_PUBLIC,
                        code -> code.invokestatic(DESCRIPTOR, "plain", MethodTypeDesc.of(DESCRIPTOR)).areturn());
                builder.withMethodBody("newInstance", MethodTypeDesc.of(CD_Object), ACC_PUBLIC,
                        code -> {
                            var start = code.newLabel(); var end = code.newLabel();
                            var unchecked = code.newLabel(); var checked = code.newLabel();
                            code.labelBinding(start).new_(component).dup()
                                    .invokespecial(component, "<init>", MethodTypeDesc.of(CD_void))
                                    .labelBinding(end).areturn();
                            code.labelBinding(unchecked).athrow();
                            var failure = ClassDesc.of("java.lang.IllegalStateException");
                            code.labelBinding(checked).astore(1).new_(failure).dup()
                                    .ldc("Cannot instantiate component " + name).aload(1)
                                    .invokespecial(failure, "<init>", MethodTypeDesc.of(CD_void, CD_String, CD_Throwable)).athrow();
                            code.exceptionCatch(start, end, unchecked, ClassDesc.of("java.lang.RuntimeException"));
                            code.exceptionCatch(start, end, unchecked, ClassDesc.of("java.lang.Error"));
                            code.exceptionCatch(start, end, checked, CD_Throwable);
                        });
            });
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
            providers.add(provider);
        }
        return List.copyOf(providers);
    }
}
