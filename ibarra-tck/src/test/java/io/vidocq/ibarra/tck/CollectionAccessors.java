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
import java.lang.classfile.*;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.constant.*;
import java.lang.module.ModuleFinder;
import java.nio.file.*;
import java.util.*;
import static java.lang.classfile.ClassFile.*;
import static java.lang.constant.ConstantDescs.*;
import static io.vidocq.ibarra.tck.BeanAccessors.*;

/** Build-only method tables for public java.base collections allocated by WAR bytecode. */
final class CollectionAccessors {
    private record Method(String name,MethodTypeDesc type) { }
    private static final String PACKAGE="tck.generated.collections";
    private static final ClassDesc CONTEXT=ClassDesc.of("jakarta.el.ELContext");
    private static final ClassDesc TABLE=ClassDesc.of("io.vidocq.peano.spi.AccessorTable");
    private static final ClassDesc DESCRIPTOR=ClassDesc.of("io.vidocq.peano.spi.MethodDescriptor");
    private static final ClassDesc INFO=ClassDesc.of("jakarta.el.MethodInfo");
    private static final ClassDesc ANNOTATION=ClassDesc.of("java.lang.annotation.Annotation");
    private static final ClassDesc REFERENCE=ClassDesc.of("jakarta.el.MethodReference");

    static BeanAccessors.Result generate(Path classes,List<Path> modules) throws IOException {
        var metadata=new BeanAccessors(classes,modules);
        var targets=new TreeSet<String>();
        var packages=ModuleFinder.ofSystem().find("java.base").orElseThrow().descriptor().packages();
        try(var paths=Files.walk(classes)) {
            for(Path path:paths.filter(p->p.toString().endsWith(".class")).sorted().toList()) {
                var model=ClassFile.of().parse(Files.readAllBytes(path));
                for(var method:model.methods()) if(method.code().isPresent())
                    for(var instruction:method.code().orElseThrow()) if(instruction instanceof NewObjectInstruction allocation) {
                        String binary=allocation.className().asInternalName().replace('/','.');
                        int dot=binary.lastIndexOf('.');
                        if(binary.startsWith("java.util.") && dot>0 && packages.contains(binary.substring(0,dot))) targets.add(binary);
                    }
            }
        }
        var tables=new ArrayList<ClassDesc>();var unsupported=new TreeMap<String,String>();
        for(String target:targets) {
            var model=metadata.read(target);
            if((model.flags().flagsMask()&(ACC_PUBLIC|ACC_ABSTRACT|ACC_INTERFACE))!=ACC_PUBLIC
                    || !metadata.assignable(ClassDesc.of(target),ClassDesc.of("java.util.Collection"),new HashSet<>())) continue;
            var methods=new TreeMap<String,Method>();var excluded=new TreeMap<String,String>();
            collect(metadata,target,methods,excluded,new HashSet<>());
            for(var method:methods.values()) try {
                metadata.signatureType(method.type().returnType(),PACKAGE+".Accessor");
                for(var parameter:method.type().parameterList()) metadata.signatureType(parameter,PACKAGE+".Accessor");
            } catch(IOException missing) { excluded.put(method.name(),missing.getMessage()); }
            excluded.forEach((name,reason)->unsupported.put(target+"#"+name,reason));
            var selected=methods.values().stream().filter(m->!excluded.containsKey(m.name())).toList();
            if(selected.isEmpty()) continue;
            var owner=ClassDesc.of(PACKAGE+"."+target.replace('.','_')+"$$Methods");
            Path file=classes.resolve(owner.descriptorString().substring(1,owner.descriptorString().length()-1)+".class");
            if(Files.exists(file)) throw new IOException("Generated collection accessor collides with application class: "+owner.displayName());
            Files.createDirectories(file.getParent());Files.write(file,table(owner,ClassDesc.of(target),selected));tables.add(owner);
        }
        if(tables.isEmpty()) return new BeanAccessors.Result(List.of(),unsupported);
        String provider="tck.generated.CollectionAccessorProvider";
        Path file=classes.resolve(provider.replace('.','/')+".class");
        if(Files.exists(file)) throw new IOException("Generated collection provider collides with application class: "+provider);
        Files.createDirectories(file.getParent());Files.write(file,provider(ClassDesc.of(provider),tables));
        return new BeanAccessors.Result(List.of(provider),unsupported);
    }

    private static void collect(BeanAccessors metadata,String binary,Map<String,Method> methods,
            Map<String,String> excluded,Set<String> visited) throws IOException {
        if(binary.equals("java.lang.Object") || !visited.add(binary)) return;
        var model=metadata.read(binary);
        for(var method:model.methods()) {
            if((method.flags().flagsMask()&(ACC_PUBLIC|ACC_STATIC|ACC_SYNTHETIC))!=ACC_PUBLIC
                    || method.methodName().stringValue().startsWith("<")) continue;
            var candidate=new Method(method.methodName().stringValue(),method.methodTypeSymbol());
            if((method.flags().flagsMask()&ACC_VARARGS)!=0 || method.findAttribute(Attributes.runtimeVisibleAnnotations()).isPresent()) {
                excluded.put(candidate.name(),"Varargs or runtime method annotations need additional generated metadata");continue;
            }
            String key=candidate.name()+candidate.type().parameterList();var previous=methods.get(key);
            if(previous==null || metadata.assignable(candidate.type().returnType(),previous.type().returnType(),new HashSet<>())) methods.put(key,candidate);
        }
        for(var parent:model.interfaces()) collect(metadata,parent.asInternalName().replace('/','.'),methods,excluded,visited);
        if(model.superclass().isPresent()) collect(metadata,model.superclass().orElseThrow().asInternalName().replace('/','.'),methods,excluded,visited);
    }

    private static byte[] table(ClassDesc owner,ClassDesc bean,List<Method> methods) {
        return ClassFile.of().build(owner,builder->{
            builder.withFlags(ACC_PUBLIC|ACC_FINAL|ACC_SUPER).withSuperclass(TABLE);
            builder.withField("METHODS",DESCRIPTOR.arrayType(),ACC_PRIVATE|ACC_STATIC|ACC_FINAL);
            builder.withMethodBody("<init>",MethodTypeDesc.of(CD_void),ACC_PUBLIC,
                    c->c.aload(0).invokespecial(TABLE,"<init>",MethodTypeDesc.of(CD_void)).return_());
            builder.withMethodBody("targetType",MethodTypeDesc.of(CD_Class),ACC_PUBLIC,c->c.ldc(bean).areturn());
            builder.withMethodBody("<clinit>",MethodTypeDesc.of(CD_void),ACC_STATIC,c->{
                c.loadConstant(methods.size()).anewarray(DESCRIPTOR);
                for(int i=0;i<methods.size();i++) {
                    var method=methods.get(i);c.dup().loadConstant(i).new_(DESCRIPTOR).dup().ldc(method.name());classLiteral(c,method.type().returnType());
                    c.loadConstant(method.type().parameterCount()).anewarray(CD_Class);
                    for(int j=0;j<method.type().parameterCount();j++) { c.dup().loadConstant(j);classLiteral(c,method.type().parameterType(j));c.aastore(); }
                    c.iconst_0().iconst_0().iconst_0().anewarray(ANNOTATION)
                            .invokespecial(DESCRIPTOR,"<init>",MethodTypeDesc.of(CD_void,CD_String,CD_Class,CD_Class.arrayType(),CD_boolean,CD_boolean,ANNOTATION.arrayType())).aastore();
                }
                c.putstatic(owner,"METHODS",DESCRIPTOR.arrayType()).return_();
            });
            for(String operation:List.of("getValue","getType","isReadOnly","setValue","getCommonPropertyType")) {
                ClassDesc result=operation.equals("getType")||operation.equals("getCommonPropertyType")?CD_Class:operation.equals("isReadOnly")?CD_boolean:operation.equals("setValue")?CD_void:CD_Object;
                var type=operation.equals("setValue")?MethodTypeDesc.of(result,CONTEXT,CD_Object,CD_Object,CD_Object)
                        :operation.equals("getCommonPropertyType")?MethodTypeDesc.of(result,CONTEXT,CD_Object):MethodTypeDesc.of(result,CONTEXT,CD_Object,CD_Object);
                builder.withMethodBody(operation,type,ACC_PUBLIC,c->{
                    c.aload(1).invokestatic(ClassDesc.of("java.util.Objects"),"requireNonNull",MethodTypeDesc.of(CD_Object,CD_Object)).pop();
                    if(result.equals(CD_void)) c.return_();else if(result.equals(CD_boolean)) c.iconst_0().ireturn();else c.aconst_null().areturn();
                });
            }
            builder.withMethodBody("getMethodReference",MethodTypeDesc.of(REFERENCE,CONTEXT,CD_Object,CD_String,CD_Class.arrayType(),CD_Object.arrayType()),ACC_PUBLIC,c->{
                var fallback=c.newLabel();accepts(c,bean,fallback,true);knownName(c,methods,fallback);
                select(c,owner,5);c.istore(6).getstatic(owner,"METHODS",DESCRIPTOR.arrayType()).iload(6).aaload().astore(7);
                c.new_(REFERENCE).dup().aload(2).aload(7).invokevirtual(DESCRIPTOR,"methodInfo",MethodTypeDesc.of(INFO))
                        .aload(7).invokevirtual(DESCRIPTOR,"annotations",MethodTypeDesc.of(ANNOTATION.arrayType()));
                var absent=c.newLabel();var done=c.newLabel();c.aload(5).ifnull(absent).aload(5)
                        .invokevirtual(CD_Object.arrayType(),"clone",MethodTypeDesc.of(CD_Object)).checkcast(CD_Object.arrayType()).goto_(done);
                c.labelBinding(absent).iconst_0().anewarray(CD_Object).labelBinding(done)
                        .invokespecial(REFERENCE,"<init>",MethodTypeDesc.of(CD_void,CD_Object,INFO,ANNOTATION.arrayType(),CD_Object.arrayType())).areturn();
                c.labelBinding(fallback).aconst_null().areturn();
            });
            builder.withMethodBody("invoke",MethodTypeDesc.of(CD_Object,CONTEXT,CD_Object,CD_Object,CD_Class.arrayType(),CD_Object.arrayType()),ACC_PUBLIC,c->{
                var fallback=c.newLabel();accepts(c,bean,fallback,true);knownName(c,methods,fallback);
                c.aload(1).aload(2).aload(3).invokevirtual(CONTEXT,"setPropertyResolved",MethodTypeDesc.of(CD_void,CD_Object,CD_Object));
                var absent=c.newLabel();var args=c.newLabel();c.aload(5).ifnull(absent).aload(5).goto_(args);
                c.labelBinding(absent).iconst_0().anewarray(CD_Object).labelBinding(args).astore(6);
                select(c,owner,6);c.istore(7);
                var start=c.newLabel();var end=c.newLabel();var caught=c.newLabel();c.labelBinding(start);
                for(int i=0;i<methods.size();i++) {
                    var method=methods.get(i);var next=c.newLabel();c.iload(7).loadConstant(i).if_icmpne(next);
                    var count=c.newLabel();c.aload(6).arraylength().loadConstant(method.type().parameterCount()).if_icmpeq(count);
                    fail(c,"jakarta.el.MethodNotFoundException","Invalid argument count");c.labelBinding(count).aload(2).checkcast(bean);
                    for(int j=0;j<method.type().parameterCount();j++) {
                        var type=method.type().parameterType(j);c.aload(1).aload(6).loadConstant(j).aaload();classLiteral(c,type);
                        c.invokevirtual(CONTEXT,"convertToType",MethodTypeDesc.of(CD_Object,CD_Object,CD_Class));unbox(c,type);
                    }
                    c.invokevirtual(bean,method.name(),method.type());
                    if(method.type().returnType().equals(CD_void)) c.aconst_null();else box(c,method.type().returnType());
                    c.areturn().labelBinding(next);
                }
                fail(c,"jakarta.el.MethodNotFoundException","Unknown generated method descriptor");c.labelBinding(end);
                c.labelBinding(caught).astore(8);var wrap=c.newLabel();var exception=ClassDesc.of("jakarta.el.ELException");
                c.aload(8).instanceOf(exception).ifeq(wrap).aload(8).athrow();c.labelBinding(wrap).new_(exception).dup()
                        .ldc("Generated collection method invocation failed").aload(8).invokespecial(exception,"<init>",MethodTypeDesc.of(CD_void,CD_String,CD_Throwable)).athrow();
                c.exceptionCatch(start,end,caught,CD_Throwable);c.labelBinding(fallback).aconst_null().areturn();
            });
        });
    }
    private static void knownName(CodeBuilder c,List<Method> methods,Label fallback) {
        var found=c.newLabel();
        for(String name:methods.stream().map(Method::name).distinct().toList()) c.ldc(name).aload(3).invokevirtual(CD_String,"equals",MethodTypeDesc.of(CD_boolean,CD_Object)).ifne(found);
        c.goto_(fallback).labelBinding(found);
    }
    private static void select(CodeBuilder c,ClassDesc owner,int arguments) {
        c.aload(1).getstatic(owner,"METHODS",DESCRIPTOR.arrayType()).aload(3).checkcast(CD_String).aload(4).aload(arguments).iconst_0()
                .invokestatic(ClassDesc.of("io.vidocq.peano.spi.MethodSelection"),"select",MethodTypeDesc.of(CD_int,CONTEXT,DESCRIPTOR.arrayType(),CD_String,CD_Class.arrayType(),CD_Object.arrayType(),CD_boolean));
    }
}
