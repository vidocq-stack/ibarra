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
    record Result(List<String> providers,Map<String,String> unsupported) {
        Result { providers=List.copyOf(providers); unsupported=Map.copyOf(unsupported); }
    }
    private static final class UnavailableType extends IOException {
        UnavailableType(String message) { super(message); }
    }
    private final Path classes;
    private final ModuleDescriptor application;
    private final Map<String,ModuleReference> modules = new TreeMap<>();
    private final Set<String> readable = new HashSet<>();
    private final Map<String, ClassModel> models = new HashMap<>();
    private BeanAccessors(Path classes, List<Path> paths) throws IOException {
        this.classes=classes;
        try(var input=Files.newInputStream(classes.resolve("module-info.class"))) { application=ModuleDescriptor.read(input); }
        for(var reference:ModuleFinder.ofSystem().findAll()) modules.put(reference.descriptor().name(),reference);
        for(var reference:ModuleFinder.of(paths.toArray(Path[]::new)).findAll()) modules.put(reference.descriptor().name(),reference);
        var pending=new ArrayDeque<String>();
        for(var requirement:application.requires())
            if(!requirement.modifiers().contains(ModuleDescriptor.Requires.Modifier.STATIC)) pending.add(requirement.name());
        while(!pending.isEmpty()) {
            String name=pending.remove(); if(!readable.add(name)) continue;
            var reference=modules.get(name);
            if(reference==null) throw new IOException("Required bean-accessor module is absent: "+name);
            for(var requirement:reference.descriptor().requires())
                if(requirement.modifiers().contains(ModuleDescriptor.Requires.Modifier.TRANSITIVE)
                        && !requirement.modifiers().contains(ModuleDescriptor.Requires.Modifier.STATIC)) pending.add(requirement.name());
        }
    }

    static Result generate(Path classes, List<Path> modules) throws IOException {
        var generator = new BeanAccessors(classes, modules);
        var tables = new ArrayList<ClassDesc>();
        var unsupported = new TreeMap<String,String>();
        List<Path> files;
        try (var walk = Files.walk(classes)) {
            files = walk.filter(p -> p.toString().endsWith(".class") && !p.getFileName().toString().equals("module-info.class")).sorted().toList();
        }
        for (Path file : files) {
            var model = ClassFile.of().parse(Files.readAllBytes(file));
            if (!classes.relativize(file).toString().replace(java.io.File.separatorChar, '/').equals(model.thisClass().asInternalName()+".class"))
                throw new IOException("WAR bean binary name does not match its class path: "+file);
            if ((model.flags().flagsMask() & (ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT)) != ACC_PUBLIC) continue;
            String binary = model.thisClass().asInternalName().replace('/', '.');
            List<Property> properties;
            try {
                if(generator.assignable(model.thisClass().asSymbol(),ClassDesc.of("java.util.Map"),new HashSet<>())
                        || generator.assignable(model.thisClass().asSymbol(),ClassDesc.of("java.util.List"),new HashSet<>())
                        || generator.assignable(model.thisClass().asSymbol(),ClassDesc.of("java.util.ResourceBundle"),new HashSet<>())
                        || generator.assignable(model.thisClass().asSymbol(),ClassDesc.of("java.lang.Record"),new HashSet<>())) continue;
                properties=generator.properties(binary);
                for(var property:properties) {
                    if(property.getter()!=null) generator.signatureType(property.getter().type().returnType(),binary);
                    if(property.setter()!=null) generator.signatureType(property.setter().type().parameterType(0),binary);
                }
            } catch(UnavailableType missing) { unsupported.put(binary,missing.getMessage()); continue; }
            if (properties.isEmpty() || properties.stream().allMatch(p -> p.name().equals("class"))) continue;
            ClassDesc owner = ClassDesc.of(binary + "$$IbarraProperties");
            Path target = classes.resolve(owner.descriptorString().substring(1, owner.descriptorString().length()-1) + ".class");
            if (Files.exists(target)) throw new IOException("Generated bean accessor collides with application class: " + owner.displayName());
            Files.write(target, table(owner, model.thisClass().asSymbol(), properties));
            tables.add(owner);
        }
        if (tables.isEmpty()) return new Result(List.of(),unsupported);
        String provider = "tck.generated.BeanAccessorProvider";
        Path file = classes.resolve(provider.replace('.', '/') + ".class");
        if (Files.exists(file)) throw new IOException("Generated bean provider collides with application class: " + provider);
        Files.createDirectories(file.getParent()); Files.write(file, provider(ClassDesc.of(provider), tables));
        return new Result(List.of(provider),unsupported);
    }

    private List<Property> properties(String binary) throws IOException {
        var methods = new ArrayList<Method>(); collect(binary, methods, new HashSet<>());
        var getters = new TreeMap<String, Method>(); var setters = new TreeMap<String, List<Method>>();
        for (var method : methods) {
            String name = method.name(); var type = method.type();
            if (name.startsWith("is") && name.length()>2 && type.parameterCount()==0 && type.returnType().equals(CD_boolean)) {
                getters.putIfAbsent(property(name.substring(2)), method);
            }
        }
        for (var method : methods) {
            String name = method.name(); var type = method.type();
            if (name.startsWith("get") && name.length()>3 && type.parameterCount()==0 && !type.returnType().equals(CD_void))
                selectGetter(getters,property(name.substring(3)),method);
            if (name.startsWith("set") && name.length()>3 && type.parameterCount()==1 && type.returnType().equals(CD_void))
                setters.computeIfAbsent(property(name.substring(3)), ignored -> new ArrayList<>()).add(method);
        }
        var names = new TreeSet<>(getters.keySet()); names.addAll(setters.keySet());
        var result = new ArrayList<Property>();
        for (String name : names) {
            var getter = getters.get(name); var candidates = setters.getOrDefault(name, List.of());
            var unique = new LinkedHashMap<ClassDesc, Method>();
            for (var setter : candidates)
                if (getter==null || assignable(setter.type().parameterType(0), getter.type().returnType(), new HashSet<>()))
                    unique.putIfAbsent(setter.type().parameterType(0), setter);
            Method selected = null;
            for (var candidate : unique.values()) {
                boolean mostSpecific = true;
                for (var other : unique.values())
                    if (!assignable(candidate.type().parameterType(0), other.type().parameterType(0), new HashSet<>())) mostSpecific=false;
                if (mostSpecific) { selected=candidate; break; }
            }
            if (getter!=null || selected!=null) result.add(new Property(name,getter,selected));
        }
        return List.copyOf(result);
    }

    private void selectGetter(Map<String,Method> getters,String name,Method candidate) throws IOException {
        var previous=getters.get(name);
        if(previous!=null && previous.name().startsWith("is") && previous.type().returnType().equals(CD_boolean)) return;
        if(previous==null || assignable(candidate.type().returnType(),previous.type().returnType(),new HashSet<>())) getters.put(name,candidate);
    }

    private void collect(String binary, List<Method> result, Set<String> visited) throws IOException {
        if (!visited.add(binary)) return;
        var model = read(binary);
        for (var method : model.methods())
            if ((method.flags().flagsMask() & (ACC_PUBLIC | ACC_STATIC | ACC_SYNTHETIC)) == ACC_PUBLIC)
                result.add(new Method(method.methodName().stringValue(),method.methodTypeSymbol()));
        for (var parent : model.interfaces()) collect(parent.asInternalName().replace('/', '.'), result, visited);
        if (model.superclass().isPresent()) collect(model.superclass().get().asInternalName().replace('/', '.'),result,visited);
    }
    private boolean assignable(ClassDesc value, ClassDesc target, Set<String> visited) throws IOException {
        if (value.equals(target)) return true;
        if (value.isPrimitive() || target.isPrimitive()) return false;
        if (target.equals(CD_Object)) return true;
        if (value.isArray()) {
            if (target.isArray()) return assignable(value.componentType(),target.componentType(),visited);
            return target.equals(ClassDesc.of("java.lang.Cloneable")) || target.equals(ClassDesc.of("java.io.Serializable"));
        }
        if (target.isArray() || !visited.add(value.descriptorString())) return false;
        var model = read(value.descriptorString().substring(1,value.descriptorString().length()-1).replace('/','.'));
        for (var parent : model.interfaces()) if (assignable(parent.asSymbol(),target,visited)) return true;
        return model.superclass().isPresent() && assignable(model.superclass().get().asSymbol(),target,visited);
    }
    private ClassModel read(String binary) throws IOException {
        if (models.containsKey(binary)) return models.get(binary);
        String relative = binary.replace('.','/') + ".class";
        if(Files.isRegularFile(classes.resolve(relative))) {
            var model=ClassFile.of().parse(Files.readAllBytes(classes.resolve(relative))); models.put(binary,model); return model;
        }
        String pkg=binary.substring(0,binary.lastIndexOf('.'));
        String inaccessible=null;
        for(var reference:modules.values()) {
            var descriptor=reference.descriptor();
            if(!descriptor.packages().contains(pkg)) continue;
            try(var reader=reference.open();var input=reader.open(relative).orElse(null)) {
                if(input==null) continue;
                if(!readable.contains(descriptor.name()) || descriptor.exports().stream().noneMatch(e -> e.source().equals(pkg)
                        && (!e.isQualified() || e.targets().contains(application.name())))) {
                    inaccessible="Bean type is not readable/exported to "+application.name()+": "+binary;continue;
                }
                var model=ClassFile.of().parse(input.readAllBytes()); models.put(binary,model); return model;
            }
        }
        if(inaccessible!=null) throw new UnavailableType(inaccessible);
        throw new UnavailableType("Bean type absent from build module path: "+binary);
    }
    private void signatureType(ClassDesc type,String bean) throws IOException {
        while(type.isArray()) type=type.componentType();
        if(type.isPrimitive()) return;
        String binary=type.descriptorString().substring(1,type.descriptorString().length()-1).replace('/','.');
        var model=read(binary);
        if((model.flags().flagsMask()&ACC_PUBLIC)==0
                && !binary.substring(0,binary.lastIndexOf('.')).equals(bean.substring(0,bean.lastIndexOf('.'))))
            throw new UnavailableType("Bean signature type is inaccessible to its accessor package: "+binary);
    }
    private static String property(String suffix) {
        return suffix.length()>1 && Character.isUpperCase(suffix.charAt(0)) && Character.isUpperCase(suffix.charAt(1))
                ? suffix : Character.toLowerCase(suffix.charAt(0))+suffix.substring(1);
    }

    private static final ClassDesc CONTEXT=ClassDesc.of("jakarta.el.ELContext");
    private static final ClassDesc TABLE=ClassDesc.of("io.vidocq.peano.spi.AccessorTable");
    private static final ClassDesc EL_EXCEPTION=ClassDesc.of("jakarta.el.ELException");

    private static byte[] table(ClassDesc owner, ClassDesc bean, List<Property> properties) {
        return ClassFile.of().build(owner,builder -> {
            builder.withFlags(ACC_PUBLIC|ACC_FINAL|ACC_SUPER).withSuperclass(TABLE);
            builder.withMethodBody("<init>",MethodTypeDesc.of(CD_void),ACC_PUBLIC,
                    code -> code.aload(0).invokespecial(TABLE,"<init>",MethodTypeDesc.of(CD_void)).return_());
            builder.withMethodBody("targetType",MethodTypeDesc.of(CD_Class),ACC_PUBLIC,code -> code.ldc(bean).areturn());
            for (String operation : List.of("getValue","getType","isReadOnly","setValue")) {
                ClassDesc result=operation.equals("getType") ? CD_Class : operation.equals("isReadOnly") ? CD_boolean : operation.equals("setValue") ? CD_void : CD_Object;
                var type=operation.equals("setValue") ? MethodTypeDesc.of(result,CONTEXT,CD_Object,CD_Object,CD_Object)
                        : MethodTypeDesc.of(result,CONTEXT,CD_Object,CD_Object);
                builder.withMethodBody(operation,type,ACC_PUBLIC,code -> {
                    var fallback=code.newLabel(); accepts(code,bean,fallback,true);
                    var start=code.newLabel(); var end=code.newLabel(); var caught=code.newLabel();
                    code.labelBinding(start);
                    for (var property : properties) {
                        var next=code.newLabel();
                        code.ldc(property.name()).aload(3).invokevirtual(CD_String,"equals",MethodTypeDesc.of(CD_boolean,CD_Object)).ifeq(next);
                        code.aload(1).aload(2).aload(3).invokevirtual(CONTEXT,"setPropertyResolved",MethodTypeDesc.of(CD_void,CD_Object,CD_Object));
                        switch(operation) {
                            case "getValue" -> {
                                if(property.getter()==null) fail(code,"jakarta.el.PropertyNotFoundException","Write-only property");
                                else { code.aload(2).checkcast(bean).invokevirtual(bean,property.getter().name(),property.getter().type());
                                    box(code,property.getter().type().returnType()); code.areturn(); }
                            }
                            case "getType" -> { if(property.setter()==null) code.aconst_null(); else classLiteral(code,property.setter().type().parameterType(0)); code.areturn(); }
                            case "isReadOnly" -> code.loadConstant(property.setter()==null ? 1 : 0).ireturn();
                            case "setValue" -> {
                                if(property.setter()==null) fail(code,"jakarta.el.PropertyNotWritableException","Read-only property");
                                else {
                                    var input=property.setter().type().parameterType(0);
                                    code.aload(2).checkcast(bean).aload(1).aload(4); classLiteral(code,input);
                                    code.invokevirtual(CONTEXT,"convertToType",MethodTypeDesc.of(CD_Object,CD_Object,CD_Class));
                                    unbox(code,input); code.invokevirtual(bean,property.setter().name(),property.setter().type()).return_();
                                }
                            }
                        }
                        code.labelBinding(next);
                    }
                    code.labelBinding(end).goto_(fallback);
                    code.labelBinding(caught).astore(5);
                    var pass=code.newLabel(); code.aload(5).instanceOf(EL_EXCEPTION).ifeq(pass).aload(5).athrow();
                    code.labelBinding(pass).new_(EL_EXCEPTION).dup().ldc("Generated bean property operation failed").aload(5)
                            .invokespecial(EL_EXCEPTION,"<init>",MethodTypeDesc.of(CD_void,CD_String,CD_Throwable)).athrow();
                    code.exceptionCatch(start,end,caught,CD_Throwable);
                    code.labelBinding(fallback); fallback(code,result);
                });
            }
            builder.withMethodBody("getCommonPropertyType",MethodTypeDesc.of(CD_Class,CONTEXT,CD_Object),ACC_PUBLIC,code -> {
                var fallback=code.newLabel(); accepts(code,bean,fallback,false); code.ldc(CD_String).areturn();
                code.labelBinding(fallback).aconst_null().areturn();
            });
        });
    }
    private static void accepts(CodeBuilder code, ClassDesc bean, Label fallback, boolean property) {
        code.aload(1).invokestatic(ClassDesc.of("java.util.Objects"),"requireNonNull",MethodTypeDesc.of(CD_Object,CD_Object)).pop();
        code.aload(2).ifnull(fallback).aload(2).invokevirtual(CD_Object,"getClass",MethodTypeDesc.of(CD_Class)).ldc(bean).if_acmpne(fallback);
        if(property) code.aload(3).instanceOf(CD_String).ifeq(fallback);
    }
    private static void fallback(CodeBuilder code,ClassDesc type) {
        if(type.equals(CD_void)) code.return_(); else if(type.equals(CD_boolean)) code.iconst_0().ireturn(); else code.aconst_null().areturn();
    }
    private static void fail(CodeBuilder code,String type,String message) {
        var exception=ClassDesc.of(type); code.new_(exception).dup().ldc(message).invokespecial(exception,"<init>",MethodTypeDesc.of(CD_void,CD_String)).athrow();
    }
    private static ClassDesc wrapper(ClassDesc type) {
        return ClassDesc.of(switch(type.descriptorString()) {
            case "Z" -> "java.lang.Boolean"; case "B" -> "java.lang.Byte"; case "C" -> "java.lang.Character";
            case "S" -> "java.lang.Short"; case "I" -> "java.lang.Integer"; case "J" -> "java.lang.Long";
            case "F" -> "java.lang.Float"; case "D" -> "java.lang.Double"; default -> throw new IllegalArgumentException("Unsupported primitive "+type);
        });
    }
    private static void classLiteral(CodeBuilder code,ClassDesc type) {
        if(type.isPrimitive()) code.getstatic(wrapper(type),"TYPE",CD_Class); else code.ldc(type);
    }
    private static void box(CodeBuilder code,ClassDesc type) {
        if(type.isPrimitive()) code.invokestatic(wrapper(type),"valueOf",MethodTypeDesc.of(wrapper(type),type));
    }
    private static void unbox(CodeBuilder code,ClassDesc type) {
        if(!type.isPrimitive()) { code.checkcast(type); return; }
        var boxed=wrapper(type);
        String method=switch(type.descriptorString()) {case "I" -> "intValue";case "C" -> "charValue";case "Z" -> "booleanValue";
            case "B" -> "byteValue";case "S" -> "shortValue";case "J" -> "longValue";case "F" -> "floatValue";case "D" -> "doubleValue";default -> throw new IllegalArgumentException();};
        code.checkcast(boxed).invokevirtual(boxed,method,MethodTypeDesc.of(type));
    }
    private static byte[] provider(ClassDesc owner,List<ClassDesc> tables) {
        return ClassFile.of().build(owner,builder -> {
            builder.withFlags(ACC_PUBLIC|ACC_FINAL|ACC_SUPER).withInterfaceSymbols(ClassDesc.of("io.vidocq.peano.spi.AccessorProvider"));
            builder.withMethodBody("<init>",MethodTypeDesc.of(CD_void),ACC_PUBLIC,
                    code -> code.aload(0).invokespecial(CD_Object,"<init>",MethodTypeDesc.of(CD_void)).return_());
            builder.withMethodBody("accessors",MethodTypeDesc.of(ClassDesc.of("java.util.Collection")),ACC_PUBLIC,code -> {
                code.loadConstant(tables.size()).anewarray(TABLE);
                for(int i=0;i<tables.size();i++) code.dup().loadConstant(i).new_(tables.get(i)).dup()
                        .invokespecial(tables.get(i),"<init>",MethodTypeDesc.of(CD_void)).aastore();
                code.invokestatic(ClassDesc.of("java.util.List"),"of",MethodTypeDesc.of(ClassDesc.of("java.util.List"),CD_Object.arrayType()),true).areturn();
            });
        });
    }
}
