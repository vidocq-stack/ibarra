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

import java.lang.classfile.*;
import java.lang.classfile.instruction.*;
import java.lang.constant.ClassDesc;
import java.util.*;
import static java.lang.constant.ConstantDescs.INIT_NAME;

/** Reads constant descriptor construction without executing application BeanInfo classes. */
final class BeanInfoEditors {
    private static final String DESCRIPTOR = "java/beans/PropertyDescriptor";
    private static final Object NULL = new Object();
    private static final Object CONTINUE = new Object();
    private static final String FOREIGN_INSTANCE = "Foreign metadata instance";
    private final String bean;
    private final String info;
    private final Object instance = new Object();
    private final Map<String, Object> fields = new HashMap<>();

    private static final class Descriptor {
        String property;
        String editor;
    }

    private BeanInfoEditors(String bean, ClassModel model) {
        this.bean = bean;
        info = model.thisClass().asInternalName();
    }

    record Metadata(Set<String> properties, Map<String, String> editors) {
        Metadata { properties = Set.copyOf(properties); editors = Map.copyOf(editors); }
    }

    static Metadata read(String bean, ClassModel model) {
        var reader = new BeanInfoEditors(bean, model);
        var constructor = model.methods().stream().filter(m -> m.methodName().equalsString(INIT_NAME)
                && m.methodType().equalsString("()V")).findFirst().orElseThrow();
        reader.evaluate(constructor);
        var method = model.methods().stream().filter(m -> m.methodName().equalsString("getPropertyDescriptors")
                && m.methodType().equalsString("()[Ljava/beans/PropertyDescriptor;")).findFirst().orElseThrow();
        Object returned = reader.evaluate(method);
        if (returned == NULL) return new Metadata(Set.of(), Map.of());
        var result = new LinkedHashMap<String, String>();
        var properties = new HashSet<String>();
        for (Object value : (Object[]) returned) {
            if (!(value instanceof Descriptor descriptor) || descriptor.property == null
                    || !properties.add(descriptor.property)) throw new IllegalArgumentException("Invalid descriptor array");
            if (descriptor.editor != null) result.put(descriptor.property, descriptor.editor);
        }
        return new Metadata(properties, result);
    }

    private Object evaluate(MethodModel method) {
        var elements = method.code().orElseThrow().elementList();
        var labels = new HashMap<Label, Integer>();
        for (int i = 0; i < elements.size(); i++) {
            if (elements.get(i) instanceof LabelTarget target) labels.put(target.label(), i);
        }
        var stack = new ArrayList<Object>();
        var locals = new HashMap<Integer, Object>();
        locals.put(0, instance);
        var visited = new HashSet<Integer>();
        int pc = 0;
        while (pc < elements.size()) {
            if (!visited.add(pc)) throw new IllegalArgumentException("Cyclic metadata construction");
            int next = pc + 1;
            if (elements.get(pc) instanceof BranchInstruction branch) {
                if (taken(branch, stack)) next = labels.get(branch.target());
            } else if (elements.get(pc) instanceof Instruction instruction) {
                Object returned = execute(instruction, stack, locals);
                if (returned != CONTINUE) return returned;
            }
            pc = next;
        }
        throw new IllegalArgumentException("Metadata method did not return");
    }

    /** Executes one non-branch instruction; returns the method result on return, otherwise {@link #CONTINUE}. */
    private Object execute(Instruction instruction, List<Object> stack, Map<Integer, Object> locals) {
        switch (instruction.opcode()) {
            case ARETURN -> { return pop(stack); }
            case RETURN -> { return NULL; }
            case ACONST_NULL -> stack.add(NULL);
            case DUP -> stack.add(stack.getLast());
            case AASTORE -> arrayStore(stack);
            case AALOAD -> arrayLoad(stack);
            case ARRAYLENGTH -> stack.add(((Object[]) pop(stack)).length);
            case CHECKCAST -> {
                // Descriptor-array copies preserve the already known symbolic values.
                if (!(stack.getLast() instanceof Object[])) throw new IllegalArgumentException("Unsupported metadata cast");
            }
            default -> executeTyped(instruction, stack, locals);
        }
        return CONTINUE;
    }

    private void executeTyped(Instruction instruction, List<Object> stack, Map<Integer, Object> locals) {
        if (instruction instanceof ConstantInstruction constant) stack.add(literal(constant));
        else if (instruction instanceof LoadInstruction load) stack.add(Objects.requireNonNull(locals.get(load.slot())));
        else if (instruction instanceof StoreInstruction store) locals.put(store.slot(), pop(stack));
        else if (instruction instanceof NewObjectInstruction allocation) {
            if (!allocation.className().asInternalName().equals(DESCRIPTOR)) throw new IllegalArgumentException("Dynamic allocation");
            stack.add(new Descriptor());
        } else if (instruction instanceof NewReferenceArrayInstruction allocation) {
            if (!allocation.componentType().asInternalName().equals(DESCRIPTOR)) throw new IllegalArgumentException("Unsupported metadata array");
            int size = (Integer) pop(stack);
            if (size < 0 || size > 4096) throw new IllegalArgumentException("Invalid descriptor count");
            stack.add(new Object[size]);
        } else if (instruction instanceof FieldInstruction field) field(field, stack);
        else if (instruction instanceof InvokeInstruction invoke) invoke(invoke, stack);
        else throw new IllegalArgumentException("Unsupported metadata instruction: " + instruction.opcode());
    }

    private static Object literal(ConstantInstruction constant) {
        Object value = constant.constantValue();
        if (!(value instanceof String || value instanceof Integer || value instanceof ClassDesc))
            throw new IllegalArgumentException("Nonliteral metadata");
        return value;
    }

    private static void arrayStore(List<Object> stack) {
        Object value = pop(stack);
        int index = (Integer) pop(stack);
        Object[] array = (Object[]) pop(stack);
        array[index] = value;
    }

    private static void arrayLoad(List<Object> stack) {
        int index = (Integer) pop(stack);
        stack.add(Objects.requireNonNull(((Object[]) pop(stack))[index]));
    }

    private void field(FieldInstruction field, List<Object> stack) {
        if (!field.owner().asInternalName().equals(info) || !field.type().equalsString("[Ljava/beans/PropertyDescriptor;"))
            throw new IllegalArgumentException("Dynamic metadata field");
        if (field.opcode() == Opcode.GETFIELD) {
            if (pop(stack) != instance) throw new IllegalArgumentException(FOREIGN_INSTANCE);
            stack.add(fields.getOrDefault(field.name().stringValue(), NULL));
        } else if (field.opcode() == Opcode.PUTFIELD) {
            Object value = pop(stack);
            if (pop(stack) != instance) throw new IllegalArgumentException(FOREIGN_INSTANCE);
            fields.put(field.name().stringValue(), value);
        } else throw new IllegalArgumentException("Static metadata field");
    }

    private static boolean taken(BranchInstruction branch, List<Object> stack) {
        var opcode = branch.opcode();
        if (opcode == Opcode.GOTO || opcode == Opcode.GOTO_W) return true;
        if (opcode != Opcode.IFNULL && opcode != Opcode.IFNONNULL) throw new IllegalArgumentException("Dynamic metadata branch");
        boolean isNull = pop(stack) == NULL;
        return opcode == Opcode.IFNULL ? isNull : !isNull;
    }

    private void invoke(InvokeInstruction call, List<Object> stack) {
        String owner = call.owner().asInternalName();
        String name = call.name().stringValue();
        String type = call.type().stringValue();
        if (owner.equals("java/beans/SimpleBeanInfo") && name.equals(INIT_NAME) && type.equals("()V")) {
            if (pop(stack) != instance) throw new IllegalArgumentException("Foreign BeanInfo constructor");
        } else if (owner.equals(DESCRIPTOR) && name.equals(INIT_NAME)
                && type.equals("(Ljava/lang/String;Ljava/lang/Class;)V")) {
            constructDescriptor(stack);
        } else if (owner.equals(DESCRIPTOR) && name.equals("setPropertyEditorClass") && type.equals("(Ljava/lang/Class;)V")) {
            Object editor = pop(stack);
            var descriptor = (Descriptor) pop(stack);
            descriptor.editor = editor == NULL ? null : ((ClassDesc) editor).descriptorString().substring(1,
                    ((ClassDesc) editor).descriptorString().length() - 1).replace('/', '.');
        } else if (owner.equals("java/util/Arrays") && name.equals("copyOf") && type.equals("([Ljava/lang/Object;I)[Ljava/lang/Object;")) {
            int size = (Integer) pop(stack);
            var array = (Object[]) pop(stack);
            if (size != array.length) throw new IllegalArgumentException("Truncated descriptor array");
            stack.add(array.clone());
        } else throw new IllegalArgumentException("Dynamic metadata call: " + owner + "." + name);
    }

    private void constructDescriptor(List<Object> stack) {
        var target = (ClassDesc) pop(stack);
        String property = (String) pop(stack);
        if (!target.descriptorString().equals("L" + bean.replace('.', '/') + ";"))
            throw new IllegalArgumentException("Foreign descriptor bean");
        ((Descriptor) pop(stack)).property = property;
    }

    private static Object pop(List<Object> stack) { return stack.removeLast(); }
}
