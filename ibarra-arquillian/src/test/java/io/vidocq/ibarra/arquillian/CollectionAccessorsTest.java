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
import java.lang.constant.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static java.lang.classfile.ClassFile.*;
import static java.lang.constant.ConstantDescs.*;
import static org.junit.jupiter.api.Assertions.*;

class CollectionAccessorsTest {
    @TempDir Path work;
    private List<Path> modules;
    private Path classes;

    @Test
    void allocatedCollectionTablesVerifyWithoutInitializingApplicationClasses() throws Exception {
        prepare();
        var result=CollectionAccessors.generate(classes,modules);
        assertEquals(List.of("tck.generated.CollectionAccessorProvider"),result.providers());
        assertEquals(Map.of(),result.unsupported());
        assertTrue(Files.exists(classes.resolve("tck/generated/collections/java_util_Vector$$Methods.class")));
        assertTrue(Files.exists(classes.resolve("tck/generated/collections/java_util_ArrayList$$Methods.class")));
        assertTrue(Files.exists(classes.resolve("tck/generated/collections/java_util_HashSet$$Methods.class")));
        try(var paths=Files.walk(classes)) {
            for(Path path:paths.filter(p->p.toString().endsWith(".class")).toList())
                assertEquals(List.of(),ClassFile.of().verify(Files.readAllBytes(path)),path.toString());
        }
    }

    @Test
    void tableAndProviderCollisionsPreserveApplicationBytes() throws Exception {
        for(String name:List.of("tck.generated.collections.java_util_Vector$$Methods","tck.generated.CollectionAccessorProvider")) {
            prepare();
            byte[] original=ClassFile.of().build(ClassDesc.of(name),b->b.withFlags(ACC_PUBLIC|ACC_SUPER));
            Path file=classes.resolve(name.replace('.','/')+".class");Files.createDirectories(file.getParent());Files.write(file,original);
            var failure=assertThrows(IOException.class,()->CollectionAccessors.generate(classes,modules));
            assertTrue(failure.getMessage().contains("collides"));assertArrayEquals(original,Files.readAllBytes(file));
        }
    }

    @Test
    void privateModuleTablesPreserveResolutionMetadataCoercionAndFailureContracts() throws Exception {
        prepare();var result=CollectionAccessors.generate(classes,modules);
        Path source=Files.createDirectories(work.resolve("probe"));
        Files.writeString(source.resolve("Main.java"),"""
                package app;
                public class Main {
                    static final class Context extends jakarta.el.ELContext {
                        int converted;
                        public jakarta.el.ELResolver getELResolver(){ return new jakarta.el.CompositeELResolver(); }
                        public jakarta.el.FunctionMapper getFunctionMapper(){ return null; }
                        public jakarta.el.VariableMapper getVariableMapper(){ return null; }
                        @SuppressWarnings("unchecked")
                        public <T> T convertToType(Object value,Class<T> target) {
                            converted++;
                            return (T)(target==int.class?Integer.valueOf(value.toString()):value);
                        }
                    }
                    static void check(boolean condition){ if(!condition) throw new AssertionError(); }
                    public static void main(String[] arguments){
                        var table=java.util.ServiceLoader.load(io.vidocq.peano.spi.AccessorProvider.class).stream()
                            .flatMap(p->p.get().accessors().stream()).filter(t->t.targetType()==java.util.Vector.class).findFirst().orElseThrow();
                        var context=new Context();var base=new java.util.Vector<Object>();
                        Object[] args={"x"};
                        var ref=table.getMethodReference(context,base,"add",new Class<?>[]{Object.class},args);
                        check(ref.getBase()==base && ref.getMethodInfo().getReturnType()==boolean.class && base.isEmpty());
                        args[0]="changed";check(ref.getEvaluatedParameters()[0].equals("x"));
                        var info=ref.getMethodInfo();var types=info.getParamTypes();types[0]=String.class;
                        check(table.getMethodReference(context,base,"add",new Class<?>[]{Object.class},null).getMethodInfo().getParamTypes()[0]==Object.class);
                        context.setPropertyResolved(false);
                        check(table.invoke(context,base,"add",new Class<?>[]{Object.class},new Object[]{null}).equals(true));
                        check(context.isPropertyResolved() && base.size()==1 && base.getFirst()==null);
                        context.setPropertyResolved(false);
                        check(table.invoke(context,base,"add",new Class<?>[]{int.class,Object.class},new Object[]{"0","first"})==null);
                        check(context.isPropertyResolved() && base.getFirst().equals("first") && context.converted==3);
                        context.setPropertyResolved(false);
                        check(table.getValue(context,base,0)==null && table.getType(context,base,0)==null && !table.isReadOnly(context,base,0));
                        table.setValue(context,base,0,"ignored");check(!context.isPropertyResolved() && base.getFirst().equals("first"));
                        check(table.getCommonPropertyType(context,base)==null);
                        for(int operation=0;operation<5;operation++) {
                            try {
                                switch(operation) {
                                    case 0->table.getValue(null,base,0);
                                    case 1->table.getType(null,base,0);
                                    case 2->table.isReadOnly(null,base,0);
                                    case 3->table.setValue(null,base,0,"ignored");
                                    case 4->table.getCommonPropertyType(null,base);
                                }
                                throw new AssertionError("Null context accepted");
                            } catch(NullPointerException expected){ }
                        }
                        check(table.invoke(context,base,"unknown",null,null)==null && !context.isPropertyResolved());
                        check(table.getMethodReference(context,base,"unknown",null,null)==null);
                        check(table.invoke(context,new java.util.Vector<Object>(){},"add",null,new Object[]{"x"})==null && !context.isPropertyResolved());
                        try { table.invoke(context,base,"get",new Class<?>[]{int.class},new Object[]{99});throw new AssertionError(); }
                        catch(jakarta.el.ELException failure){ check(failure.getCause() instanceof IndexOutOfBoundsException); }
                        try { table.invoke(context,base,"add",new Class<?>[]{Object.class},new Object[0]);throw new AssertionError(); }
                        catch(jakarta.el.MethodNotFoundException expected){ }
                        System.out.println("collection contracts pass");
                    }
                }
                """);
        descriptor("uses io.vidocq.peano.spi.AccessorProvider;provides io.vidocq.peano.spi.AccessorProvider with "+String.join(",",result.providers())+";");
        compile(source.resolve("Main.java"));
        Path log=work.resolve("run.log");
        var paths=new ArrayList<>(modules);paths.add(classes);
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "--module-path",join(paths),"-m","tck.application/app.Main").redirectErrorStream(true).redirectOutput(log.toFile()).start();
        assertTrue(process.waitFor(30,TimeUnit.SECONDS));assertEquals(0,process.exitValue(),Files.readString(log));
        assertTrue(Files.readString(log).contains("collection contracts pass"));
    }

    private void prepare() throws Exception {
        modules=PagesContainer.modulePaths(true);classes=Files.createTempDirectory(work,"application");
        Path file=classes.resolve("app/Allocation.class");Files.createDirectories(file.getParent());
        Files.write(file,ClassFile.of().build(ClassDesc.of("app.Allocation"),b->{
            b.withFlags(ACC_PUBLIC|ACC_SUPER);
            b.withMethodBody("allocate",MethodTypeDesc.of(CD_void),ACC_PUBLIC|ACC_STATIC,c->{
                for(String type:List.of("java.util.Vector","java.util.ArrayList","java.util.HashSet")) {
                    var target=ClassDesc.of(type);c.new_(target).dup().invokespecial(target,"<init>",MethodTypeDesc.of(CD_void)).pop();
                }
                c.return_();
            });
            b.withMethodBody("<clinit>",MethodTypeDesc.of(CD_void),ACC_STATIC,c->{
                var error=ClassDesc.of("java.lang.AssertionError");c.new_(error).dup().invokespecial(error,"<init>",MethodTypeDesc.of(CD_void)).athrow();
            });
        }));
        descriptor("");
    }
    private void descriptor(String services) throws Exception {
        Path source=Files.createTempDirectory(work,"descriptor").resolve("module-info.java");
        Files.writeString(source,"module tck.application { requires io.vidocq.peano.api;"+services+" }");compile(source);
    }
    private void compile(Path source) {
        var diagnostics=new java.io.ByteArrayOutputStream();
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,diagnostics,diagnostics,"--module-path",join(modules),
                "--patch-module","tck.application="+classes,"-d",classes.toString(),source.toString()),diagnostics.toString());
    }
    private static String join(List<Path> paths) { return String.join(java.io.File.pathSeparator,paths.stream().map(Path::toString).toList()); }
}
