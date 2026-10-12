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
package io.vidocq.ibarra.tck;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static java.lang.classfile.ClassFile.*;
import static java.lang.constant.ConstantDescs.*;
import static org.junit.jupiter.api.Assertions.*;

class BeanAccessorsTest {
    @TempDir Path work;

    @Test
    void unavailableAndUnexportedHierarchiesDoNotProduceUnusableTables() throws Exception {
        for (boolean reads : List.of(false,true)) {
            Path root=Files.createTempDirectory(work,"case");
            Path support=Files.createDirectories(root.resolve("support"));
            bean(support,"support.Parent",CD_Object);
            descriptor(support,"bean.support",reads ? "" : "exports support;",List.of());
            Path classes=Files.createDirectories(root.resolve("application"));
            bean(classes,"app.Bean",ClassDesc.of("support.Parent"));
            var modules=new ArrayList<>(PagesContainer.modulePaths(true)); modules.add(support);
            descriptor(classes,"tck.application","requires io.vidocq.peano.api;"+(reads ? "requires bean.support;" : ""),modules);
            var result=BeanAccessors.generate(classes,modules);
            assertEquals(List.of(),result.providers());
            assertTrue(result.unsupported().get("app.Bean").contains("not readable/exported"));
            assertFalse(Files.exists(classes.resolve("app/Bean$$IbarraProperties.class")));
        }
    }

    @Test
    void unrelatedUnreadableModuleCannotMaskTheReadableClassInTheSamePackage() throws Exception {
        Path unrelated=Files.createDirectories(work.resolve("unrelated"));
        bean(unrelated,"support.Other",CD_Object);
        descriptor(unrelated,"a.unread","exports support;",List.of());
        Path support=Files.createDirectories(work.resolve("support"));
        bean(support,"support.Parent",CD_Object);
        descriptor(support,"z.support","exports support;",List.of());
        Path classes=Files.createDirectories(work.resolve("application"));
        bean(classes,"app.Bean",ClassDesc.of("support.Parent"));
        var modules=new ArrayList<>(PagesContainer.modulePaths(true)); modules.add(unrelated);modules.add(support);
        descriptor(classes,"tck.application","requires io.vidocq.peano.api;requires z.support;",modules);
        var result=BeanAccessors.generate(classes,modules);
        assertEquals(1,result.providers().size());assertEquals(Map.of(),result.unsupported());
    }

    @Test
    void qualifiedExportsToTheApplicationPermitDirectTablesWithoutInitialization() throws Exception {
        Path support=Files.createDirectories(work.resolve("support"));
        bean(support,"support.Parent",CD_Object);
        descriptor(support,"bean.support","exports support to tck.application;",List.of());
        Path classes=Files.createDirectories(work.resolve("application"));
        bean(classes,"app.Bean",ClassDesc.of("support.Parent"));
        var modules=new ArrayList<>(PagesContainer.modulePaths(true)); modules.add(support);
        descriptor(classes,"tck.application","requires io.vidocq.peano.api;requires bean.support;",modules);
        var result=BeanAccessors.generate(classes,modules);
        assertEquals(1,result.providers().size()); assertEquals(Map.of(),result.unsupported());
        try(var files=Files.walk(classes)) {
            for(Path path:files.filter(p -> p.toString().endsWith(".class")).toList())
                assertEquals(List.of(),ClassFile.of().verify(Files.readAllBytes(path)),path.toString());
        }
    }

    @Test
    void mismatchedBinaryPathsAreRejectedBeforeProviderRegistration() throws Exception {
        Path classes=Files.createDirectories(work.resolve("application"));
        bean(classes,"app.Bean",CD_Object);
        Files.move(classes.resolve("app/Bean.class"),classes.resolve("app/Wrong.class"));
        var modules=PagesContainer.modulePaths(true);
        descriptor(classes,"tck.application","requires io.vidocq.peano.api;",modules);
        var failure=assertThrows(IOException.class,() -> BeanAccessors.generate(classes,modules));
        assertTrue(failure.getMessage().contains("does not match"));
        assertFalse(Files.exists(classes.resolve("tck/generated/BeanAccessorProvider.class")));
    }

    @Test
    void collisionsPreserveExistingApplicationClasses() throws Exception {
        Path classes=Files.createDirectories(work.resolve("application"));
        bean(classes,"app.Bean",CD_Object);
        Path collision=classes.resolve("app/Bean$$IbarraProperties.class");
        bean(classes,"app.Bean$$IbarraProperties",CD_Object);
        byte[] original=Files.readAllBytes(collision);
        var modules=PagesContainer.modulePaths(true);
        descriptor(classes,"tck.application","requires io.vidocq.peano.api;",modules);
        assertThrows(IOException.class,() -> BeanAccessors.generate(classes,modules));
        assertArrayEquals(original,Files.readAllBytes(collision));
    }

    private void descriptor(Path output,String name,String body,List<Path> modules) throws Exception {
        Path source=Files.createTempDirectory(work,"module-source").resolve("module-info.java");
        Files.writeString(source,"module "+name+" {"+body+"}");
        String path=String.join(java.io.File.pathSeparator,modules.stream().map(Path::toString).toList());
        var diagnostics=new java.io.ByteArrayOutputStream();
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,diagnostics,diagnostics,
                "--module-path",path,"--patch-module",name+"="+output,"-d",output.toString(),source.toString()),diagnostics.toString());
    }
    private void bean(Path output,String name,ClassDesc parent) throws IOException {
        ClassDesc owner=ClassDesc.of(name);
        byte[] bytes=ClassFile.of().build(owner,builder -> {
            builder.withFlags(ACC_PUBLIC|ACC_SUPER).withSuperclass(parent);
            builder.withMethodBody("<init>",MethodTypeDesc.of(CD_void),ACC_PUBLIC,
                    code -> code.aload(0).invokespecial(parent,"<init>",MethodTypeDesc.of(CD_void)).return_());
            builder.withMethodBody("getValue",MethodTypeDesc.of(CD_String),ACC_PUBLIC,code -> code.ldc("value").areturn());
            // Any attempt to initialize a fixture during metadata discovery must fail.
            builder.withMethodBody("<clinit>",MethodTypeDesc.of(CD_void),ACC_STATIC,code -> {
                var failure=ClassDesc.of("java.lang.AssertionError");
                code.new_(failure).dup().invokespecial(failure,"<init>",MethodTypeDesc.of(CD_void)).athrow();
            });
        });
        Path file=output.resolve(name.replace('.','/')+".class");
        Files.createDirectories(file.getParent()); Files.write(file,bytes);
    }
}
