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
import java.util.*;

/** Evaluates literal TagExtraInfo variable declarations without loading application classes. */
final class TagExtraInfoVariables {
    private static final String VARIABLE_INFO = "jakarta/servlet/jsp/tagext/VariableInfo";
    private static final String DEBUG = "ee/jakarta/tck/pages/common/util/JspTestUtil";
    private static final Object NULL = new Object();

    record Variable(String name, String className, boolean declare, int scope) { }

    private static final class Value {
        String name;
        String className;
        boolean declare;
        int scope;
        Value(String name, String className, boolean declare, int scope) {
            this.name = name; this.className = className; this.declare = declare; this.scope = scope;
        }
    }

    private final Object instance = new Object();

    static Optional<List<Variable>> read(ClassModel model) {
        var method = model.methods().stream().filter(value -> value.methodName().equalsString("getVariableInfo")
                && value.methodType().equalsString("(Ljakarta/servlet/jsp/tagext/TagData;)[Ljakarta/servlet/jsp/tagext/VariableInfo;"))
                .findFirst();
        if (method.isEmpty()) return Optional.empty();
        int flags = method.orElseThrow().flags().flagsMask();
        if ((flags & ClassFile.ACC_PUBLIC) == 0 || (flags & (ClassFile.ACC_STATIC | ClassFile.ACC_ABSTRACT)) != 0)
            throw new IllegalArgumentException("getVariableInfo must be a public instance method");
        Object returned = new TagExtraInfoVariables().evaluate(model, method.orElseThrow());
        if (returned == NULL) return Optional.of(List.of());
        if (!(returned instanceof Object[] values)) throw new IllegalArgumentException("Invalid variable metadata result");
        var result = new ArrayList<Variable>(values.length);
        var names = new HashSet<String>();
        for (Object value : values) {
            if (!(value instanceof Value variable) || variable.name == null || variable.className == null
                    || !names.add(variable.name) || variable.scope < 0 || variable.scope > 2)
                throw new IllegalArgumentException("Invalid variable metadata");
            result.add(new Variable(variable.name, variable.className, variable.declare, variable.scope));
        }
        return Optional.of(List.copyOf(result));
    }

    private Object evaluate(ClassModel model, MethodModel method) {
        var elements = method.code().orElseThrow(() -> new IllegalArgumentException("getVariableInfo has no bytecode")).elementList();
        var stack = new ArrayList<Object>();
        var locals = new HashMap<Integer, Object>();
        locals.put(0, instance);
        locals.put(1, new Object());
        for (var element : elements) {
            if (!(element instanceof Instruction instruction)) continue;
            var opcode = instruction.opcode();
            if (opcode == Opcode.ACONST_NULL) stack.add(NULL);
            else if (instruction instanceof ConstantInstruction constant) {
                Object value = constant.constantValue();
                if (!(value instanceof String || value instanceof Integer)) throw new IllegalArgumentException("Nonliteral variable metadata");
                stack.add(value);
            } else if (instruction instanceof LoadInstruction load) stack.add(Objects.requireNonNull(locals.get(load.slot())));
            else if (instruction instanceof StoreInstruction store) locals.put(store.slot(), pop(stack));
            else if (opcode == Opcode.DUP) stack.add(stack.getLast());
            else if (instruction instanceof NewObjectInstruction allocation) {
                if (!allocation.className().asInternalName().equals(VARIABLE_INFO)) throw new IllegalArgumentException("Dynamic variable allocation");
                stack.add(new Value(null, null, false, -1));
            } else if (instruction instanceof NewReferenceArrayInstruction allocation) {
                if (!allocation.componentType().asInternalName().equals(VARIABLE_INFO)) throw new IllegalArgumentException("Unsupported variable array");
                int size = (Integer) pop(stack);
                if (size < 0 || size > 4096) throw new IllegalArgumentException("Invalid variable count");
                stack.add(new Object[size]);
            } else if (opcode == Opcode.AASTORE) {
                Object value = pop(stack); int index = (Integer) pop(stack); Object[] array = (Object[]) pop(stack);
                array[index] = value;
            } else if (instruction instanceof InvokeInstruction invoke) invoke(invoke, stack);
            else if (opcode == Opcode.ARETURN) return pop(stack);
            else throw new IllegalArgumentException("Unsupported variable metadata instruction: " + opcode);
        }
        throw new IllegalArgumentException("getVariableInfo did not return");
    }

    private void invoke(InvokeInstruction call, List<Object> stack) {
        String owner = call.owner().asInternalName(), name = call.name().stringValue(), type = call.type().stringValue();
        if (owner.equals(VARIABLE_INFO) && name.equals("<init>")
                && type.equals("(Ljava/lang/String;Ljava/lang/String;ZI)V")) {
            int scope = (Integer) pop(stack);
            boolean declare = (Integer) pop(stack) != 0;
            String className = (String) pop(stack);
            String variableName = (String) pop(stack);
            var variable = (Value) pop(stack);
            variable.name = variableName; variable.className = className;
            variable.declare = declare; variable.scope = scope;
        } else if (owner.equals(DEBUG) && name.equals("debug") && type.equals("(Ljava/lang/String;)V")) {
            // This fixture logger does not contribute to the returned compile-time metadata.
            pop(stack);
        } else throw new IllegalArgumentException("Dynamic variable metadata call: " + owner + "." + name);
    }

    private static Object pop(List<Object> stack) { return stack.removeLast(); }
}
