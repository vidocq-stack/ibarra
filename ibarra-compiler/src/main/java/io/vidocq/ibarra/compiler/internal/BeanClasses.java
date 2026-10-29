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
    public record Function(String owner, String method, String returnType, List<String> parameters) {
        public Function { parameters = List.copyOf(parameters); }
    }
    public record TagVariable(String name, String className, boolean declare, int scope) { }
    public record Handler(String sourceName, boolean simple, boolean iteration, boolean buffered,
            boolean catchFinally, boolean dynamic, boolean jspIdConsumer) { }
    private static final Set<String> PRIMITIVES = Set.of("void", "boolean", "byte", "short", "int", "long", "float", "double", "char");
    private static final String ABSENT = "absent from the build module path";
    private static final String OBJECT = "java.lang.Object";
    private static final String BEAN_INFO = "BeanInfo";
    private final List<Path> paths;
    private final Map<String, Map<String, String>> beanEditors = new HashMap<>();
    public BeanClasses(List<Path> paths) { this.paths = List.copyOf(paths); }

    /** Resolves public expression signature types from class files, without initialization. */
    public String expressionType(String type, SourceLocation at) {
        int dimensions = 0;
        String element = type;
        while (element.endsWith("[]")) {
            dimensions++;
            element = element.substring(0, element.length() - 2);
        }
        String suffix = "[]".repeat(dimensions);
        if (PRIMITIVES.contains(element)) return element + suffix;
        String candidate = element.contains(".") ? element : "java.lang." + element;
        while (true) {
            ClassModel model;
            try { model = read(candidate, at); }
            catch (PageParseException absent) {
                int dot = candidate.lastIndexOf('.');
                if (dot < 0 || !absent(absent)) throw absent;
                candidate = candidate.substring(0, dot) + "$" + candidate.substring(dot + 1); continue;
            }
            if ((model.flags().flagsMask() & ClassFile.ACC_PUBLIC) == 0)
                throw new PageParseException(at, "Expression signature type must be public: " + element);
            return sourceName(candidate, at) + suffix;
        }
    }

    private static boolean absent(PageParseException failure) {
        return failure.getMessage().contains(ABSENT);
    }

    public Function function(TldFunction definition, SourceLocation at) {
        var signature = java.util.regex.Pattern.compile("\\s*(.+?)\\s+([\\p{L}_$][\\p{L}\\p{N}_$]*)\\s*\\((.*?)\\)\\s*").matcher(definition.signature());
        if (!signature.matches()) throw new PageParseException(at, "Invalid function signature: " + definition.signature());
        String result = signature.group(1).replaceAll("\\s+", "");
        String name = signature.group(2);
        List<String> parameters = signature.group(3).isBlank() ? List.of() : Arrays.stream(signature.group(3).split(",", -1)).map(value -> value.replaceAll("\\s+", "")).toList();
        String type = definition.functionClass();
        var owner = read(type, at);
        if ((owner.flags().flagsMask() & ClassFile.ACC_PUBLIC) == 0) throw new PageParseException(at, "Function owner must be public: " + type);
        var visited = new HashSet<String>();
        String current = type;
        while (current != null && visited.add(current)) {
            var model = read(current, at);
            var method = functionMethod(model, name, parameters, at);
            if (method != null) {
                checkFunction(method, result, definition, at);
                return new Function(sourceName(type, at), name, result, parameters);
            }
            current = model.superclass().map(parent -> parent.asInternalName().replace('/', '.')).orElse(null);
        }
        throw new PageParseException(at, "No public static function with signature " + definition.signature());
    }

    /** The first method of {@code model} with the function's name and parameter types, if any. */
    private MethodModel functionMethod(ClassModel model, String name, List<String> parameters, SourceLocation at) {
        for (var method : model.methods()) {
            if (method.methodName().stringValue().equals(name) && method.methodTypeSymbol().parameterList().stream()
                    .map(parameter -> functionType(parameter.descriptorString(), at)).toList().equals(parameters))
                return method;
        }
        return null;
    }

    private void checkFunction(MethodModel method, String result, TldFunction definition, SourceLocation at) {
        if ((method.flags().flagsMask() & (ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC)) != (ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC)
                || !functionType(method.methodTypeSymbol().returnType().descriptorString(), at).equals(result))
            throw new PageParseException(at, "Function signature must match a public static method: " + definition.signature());
        if ((method.flags().flagsMask() & ClassFile.ACC_VARARGS) != 0) throw new PageParseException(at, "Variable-arity TLD functions are not supported yet");
    }

    private String functionType(String descriptor, SourceLocation at) {
        if (descriptor.startsWith("[")) return functionType(descriptor.substring(1), at) + "[]";
        if (descriptor.startsWith("L")) return sourceName(descriptor.substring(1, descriptor.length()-1).replace('/', '.'), at);
        return javaType(descriptor);
    }

    public void constructor(String type, SourceLocation at) {
        constructor(type, at, "Bean");
    }

    /** Checks a class hierarchy for an inherited final instance method without loading its classes. */
    public boolean hasFinalMethod(String type, String name, String descriptor, SourceLocation at) {
        var visited = new HashSet<String>();
        String current = type;
        while (current != null && visited.add(current)) {
            ClassModel model;
            try {
                model = read(current, at);
            } catch (PageParseException missing) {
                if (absent(missing)) return false;
                throw missing;
            }
            var method = model.methods().stream().filter(candidate -> candidate.methodName().equalsString(name)
                    && candidate.methodType().stringValue().equals(descriptor)).findFirst();
            if (method.isPresent())
                return (method.orElseThrow().flags().flagsMask() & ClassFile.ACC_FINAL) != 0;
            current = model.superclass().map(parent -> parent.asInternalName().replace('/', '.')).orElse(null);
        }
        return false;
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
        if (type.equals(OBJECT) || !visited.add(type)) return false;
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
                subtype(type, "jakarta.servlet.jsp.tagext.DynamicAttributes", at, new HashSet<>()),
                subtype(type, "jakarta.servlet.jsp.tagext.JspIdConsumer", at, new HashSet<>()));
    }

    /** Reads statically declared TEI variables without loading the application class. */
    public List<TagVariable> tagVariables(String type, SourceLocation at) {
        constructor(type, at, "Tag extra info");
        if (!subtype(type, "jakarta.servlet.jsp.tagext.TagExtraInfo", at, new HashSet<>()))
            throw new PageParseException(at, "Tag extra info class does not extend TagExtraInfo: " + type);
        var visited = new HashSet<String>();
        String current = type;
        while (visited.add(current)) {
            var model = read(current, at);
            try {
                var variables = TagExtraInfoVariables.read(model);
                if (variables.isPresent()) return variables.orElseThrow().stream()
                        .map(value -> new TagVariable(value.name(), value.className(), value.declare(), value.scope())).toList();
            } catch (RuntimeException failure) {
                throw new PageParseException(at, "Tag extra info variable metadata is not a supported static contract: " + type, failure);
            }
            if (model.superclass().isEmpty()) break;
            current = model.superclass().orElseThrow().asInternalName().replace('/', '.');
        }
        return List.of();
    }

    private boolean subtype(String type, String target, SourceLocation at, Set<String> visited) {
        if (type.equals(target)) return true;
        if (type.equals(OBJECT) || !visited.add(type)) return false;
        var model = read(type, at);
        for (var parent : model.interfaces()) if (subtype(parent.asInternalName().replace('/', '.'), target, at, visited)) return true;
        return model.superclass().isPresent() && subtype(model.superclass().orElseThrow().asInternalName().replace('/', '.'), target, at, visited);
    }
    public Accessor tagSetter(String type, String property, String expected, SourceLocation at) {
        return tagSetter(type, property, expected, at, false);
    }
    public Accessor tagFragmentSetter(String type, String property, String expected, SourceLocation at) {
        return tagSetter(type, property, expected, at, true);
    }
    private Accessor tagSetter(String type, String property, String expected, SourceLocation at, boolean allowSupertype) {
        String name = "set" + suffix(property, at);
        Accessor compatible = null;
        for (var method : methods(type, at)) {
            if (setterShape(method, name)) {
                String parameterDescriptor = method.methodTypeSymbol().parameterType(0).descriptorString();
                String parameter = javaType(parameterDescriptor);
                if (parameter.equals(expected.replace('$', '.'))) return new Accessor(name, parameter);
                if (allowSupertype && compatible == null && supertypeParameter(parameterDescriptor, expected, at))
                    compatible = new Accessor(name, parameter);
            }
        }
        if (compatible != null) return compatible;
        throw new PageParseException(at, "No setter matching TLD type " + expected + " for " + property + " on " + type);
    }

    /** Whether a reference-typed setter parameter accepts the expected reference type. */
    private boolean supertypeParameter(String parameterDescriptor, String expected, SourceLocation at) {
        if (!parameterDescriptor.startsWith("L") || !referenceType(expected)) return false;
        String parameterClass = parameterDescriptor.substring(1, parameterDescriptor.length() - 1).replace('/', '.');
        return subtype(expected.replace('/', '.'), parameterClass, at, new HashSet<>());
    }

    private static boolean setterShape(MethodModel method, String name) {
        var descriptor = method.methodTypeSymbol();
        return method.methodName().stringValue().equals(name) && descriptor.parameterCount() == 1
                && descriptor.returnType().descriptorString().equals("V");
    }

    private static boolean referenceType(String type) {
        return !type.endsWith("[]") && !PRIMITIVES.contains(type);
    }

    public Accessor setter(String type, String property, SourceLocation at) {
        return setter(type, property, at, false);
    }

    /** Selects a declared property editor entirely from build-time class-file metadata. */
    public String propertyEditor(String type, String property, SourceLocation at) {
        var editors = beanEditors.get(type);
        if (editors == null) {
            editors = beanInfoEditors(type, at);
            beanEditors.put(type, editors);
        }
        String editor = editors.get(property);
        if (editor == null) return null;
        constructor(editor, at, "Property editor");
        if (!subtype(editor, "java.beans.PropertyEditor", at, new HashSet<>()))
            throw new PageParseException(at, "Property editor must implement java.beans.PropertyEditor: " + editor);
        return sourceName(editor, at);
    }

    /** The property editors a bean's BeanInfo declares, or none when it has no BeanInfo. */
    private Map<String, String> beanInfoEditors(String type, SourceLocation at) {
        String infoType = type + BEAN_INFO;
        ClassModel info;
        try { info = read(infoType, at); }
        catch (PageParseException absent) {
            if (!absent(absent)) throw absent;
            rejectInheritedBeanInfo(type, at);
            return Map.of();
        }
        constructor(infoType, at, BEAN_INFO);
        if (!subtype(infoType, "java.beans.BeanInfo", at, new HashSet<>()))
            throw new PageParseException(at, "BeanInfo must implement java.beans.BeanInfo: " + type);
        boolean descriptors = info.methods().stream().anyMatch(method -> method.methodName().equalsString("getPropertyDescriptors")
                && method.methodType().equalsString("()[Ljava/beans/PropertyDescriptor;")
                && (method.flags().flagsMask() & (ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC)) == ClassFile.ACC_PUBLIC);
        if (!descriptors) throw new PageParseException(at, "Unsupported build-time BeanInfo descriptor method on " + type);
        try {
            var metadata = BeanInfoEditors.read(type, info);
            for (String name : metadata.properties()) {
                var getter = getter(type, name, at);
                var setter = setter(type, name, at);
                if (!getter.type().equals(setter.type()))
                    throw new PageParseException(at, "Unsupported build-time BeanInfo property types for " + name);
            }
            return metadata.editors();
        }
        catch (RuntimeException unsupported) {
            throw new PageParseException(at, "Unsupported build-time BeanInfo metadata for " + type
                    + ": " + unsupported.getMessage(), unsupported);
        }
    }

    private void rejectInheritedBeanInfo(String type, SourceLocation at) {
        var visited = new HashSet<String>();
        var model = read(type, at);
        while (model.superclass().isPresent()) {
            String parent = model.superclass().orElseThrow().asInternalName().replace('/', '.');
            if (parent.equals(OBJECT) || !visited.add(parent)) return;
            String info = parent + BEAN_INFO;
            boolean found;
            try { read(info, at); found = true; }
            catch (PageParseException absent) {
                if (!absent(absent)) throw absent;
                found = false;
            }
            if (found) throw new PageParseException(at, "Unsupported inherited build-time BeanInfo metadata: " + info);
            model = read(parent, at);
        }
    }

    /** Ordinary tag values bind JavaBeans properties independently of TLD EL type defaults. */
    public Accessor tagPropertySetter(String type, String property, SourceLocation at) {
        return setter(type, property, at, true);
    }

    private Accessor setter(String type, String property, SourceLocation at, boolean tagProperty) {
        String name = "set" + suffix(property, at);
        var candidates = new LinkedHashMap<String, Accessor>();
        var parameters = new HashMap<String, String>();
        var methods = methods(type, at);
        for (var method : methods) {
            if (setterShape(method, name)) {
                String parameterDescriptor = method.methodTypeSymbol().parameterType(0).descriptorString();
                String parameter = javaType(parameterDescriptor);
                candidates.putIfAbsent(parameter, new Accessor(name, parameter));
                parameters.putIfAbsent(parameter, parameterDescriptor);
            }
        }
        Accessor selected = tagProperty ? tagPropertyCandidate(property, at, methods, candidates, parameters)
                : getterTypedCandidate(type, property, at, candidates);
        if (selected != null) return selected;
        if (candidates.size() != 1) throw new PageParseException(at, "Missing or ambiguous writable property " + property + " on " + type);
        return candidates.values().iterator().next();
    }

    /**
     * Keeps the setters accepting the property's read type and selects the one whose parameter every other
     * candidate's parameter can be assigned to; {@code null} when none is most general.
     */
    private Accessor tagPropertyCandidate(String property, SourceLocation at, List<MethodModel> methods,
            Map<String, Accessor> candidates, Map<String, String> parameters) {
        String readType = readType(property, at, methods);
        if (readType != null) candidates.entrySet().removeIf(entry -> !assignable(parameters.get(entry.getKey()), readType, at));
        for (var candidate : candidates.values()) {
            String value = parameters.get(candidate.type());
            if (candidates.keySet().stream().allMatch(other -> assignable(value, parameters.get(other), at))) return candidate;
        }
        return null;
    }

    private String readType(String property, SourceLocation at, List<MethodModel> methods) {
        String readType = getterType("is" + suffix(property, at), methods, at);
        return readType != null ? readType : getterType("get" + suffix(property, at), methods, at);
    }

    private String getterType(String getter, List<MethodModel> methods, SourceLocation at) {
        String readType = null;
        for (var method : methods) {
            var descriptor = method.methodTypeSymbol();
            String returned = descriptor.returnType().descriptorString();
            if (method.methodName().stringValue().equals(getter) && descriptor.parameterCount() == 0
                    && !returned.equals("V") && (!getter.startsWith("is") || returned.equals("Z"))
                    && (readType == null || assignable(returned, readType, at))) readType = returned;
        }
        return readType;
    }

    /** Among several setters, the one whose type matches the property getter, if any. */
    private Accessor getterTypedCandidate(String type, String property, SourceLocation at, Map<String, Accessor> candidates) {
        if (candidates.size() <= 1) return null;
        try {
            return candidates.get(getter(type, property, at).type());
        } catch (PageParseException _) {
            return null; // The caller reports the setter ambiguity.
        }
    }

    private boolean assignable(String value, String target, SourceLocation at) {
        if (value.equals(target)) return true;
        boolean valueReference = value.startsWith("L") || value.startsWith("[");
        boolean targetReference = target.startsWith("L") || target.startsWith("[");
        if (!valueReference || !targetReference) return false;
        if (target.equals("Ljava/lang/Object;")) return true;
        if (value.startsWith("[")) {
            if (target.startsWith("[")) return assignable(value.substring(1), target.substring(1), at);
            return target.equals("Ljava/lang/Cloneable;") || target.equals("Ljava/io/Serializable;");
        }
        if (target.startsWith("[")) return false;
        return subtype(value.substring(1, value.length() - 1).replace('/', '.'),
                target.substring(1, target.length() - 1).replace('/', '.'), at, new HashSet<>());
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
            var modules = jrt.getPath(jrt.getSeparator(), "modules");
            for (var module : java.lang.module.ModuleFinder.ofSystem().findAll()) {
                var filePath = modules.resolve(module.descriptor().name()).resolve(file);
                if (Files.isRegularFile(filePath)) return ClassFile.of().parse(filePath);
            }
        } catch (IOException | IllegalArgumentException failure) {
            throw new PageParseException(at, "Cannot read bean class " + type, failure);
        }
        throw new PageParseException(at, "Bean class is " + ABSENT + ": " + type);
    }
    private static String suffix(String property, SourceLocation at) {
        if (property.isEmpty()) throw new PageParseException(at, "Empty bean property");
        return Character.toUpperCase(property.charAt(0)) + property.substring(1);
    }
    private static String javaType(String descriptor) {
        if (descriptor.startsWith("[")) return javaType(descriptor.substring(1)) + "[]";
        return switch (descriptor) {
            case "V" -> "void"; case "Z" -> "boolean"; case "B" -> "byte"; case "C" -> "char"; case "D" -> "double";
            case "F" -> "float"; case "I" -> "int"; case "J" -> "long"; case "S" -> "short";
            default -> descriptor.substring(1, descriptor.length() - 1).replace('/', '.').replace('$', '.');
        };
    }
}
