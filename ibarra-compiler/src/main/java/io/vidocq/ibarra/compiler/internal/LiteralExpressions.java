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
import java.lang.classfile.attribute.ConstantValueAttribute;
import java.lang.constant.*;
import java.security.*;
import java.util.HexFormat;
import static java.lang.constant.ConstantDescs.*;

/** Emits literal expression protocol classes without reparsing escaped literal text. */
public final class LiteralExpressions {
    private LiteralExpressions() { }
    public static byte[] compile(String name, String literal, boolean method) {
        var owner = ClassDesc.of(name);
        var parent = ClassDesc.of("io.vidocq.peano.spi." + (method ? "CompiledMethodExpression" : "CompiledValueExpression"));
        var context = ClassDesc.of("jakarta.el.ELContext");
        var type = ClassDesc.of("java.lang.Class");
        var types = type.arrayType();
        String identity = identity(literal, method);
        return ClassFile.of().build(owner, builder -> {
            builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER)
                    .withVersion(ClassFile.JAVA_25_VERSION, 0).withSuperclass(parent);
            builder.withField("serialVersionUID", CD_long, field -> field.withFlags(ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL)
                    .with(ConstantValueAttribute.of(Long.parseUnsignedLong(identity.substring(identity.length() - 16), 16))));
            builder.withMethodBody("<init>", method ? MethodTypeDesc.of(CD_void, type, types) : MethodTypeDesc.of(CD_void, type),
                    ClassFile.ACC_PUBLIC, code -> {
                        code.aload(0); string(code, literal); code.ldc(identity);
                        if (method) {
                            code.aconst_null().aload(1).aload(2).iconst_0();
                            code.iconst_0().anewarray(CD_String).iconst_0().anewarray(CD_String);
                            code.invokespecial(parent, "<init>", MethodTypeDesc.of(CD_void, CD_String, CD_String, context, type, types, CD_int,
                                    CD_String.arrayType(), CD_String.arrayType()));
                        } else {
                            code.aload(1).iconst_1().ldc(CD_String);
                            code.invokespecial(parent, "<init>", MethodTypeDesc.of(CD_void, CD_String, CD_String, type, CD_boolean, type));
                        }
                        code.return_();
                    });
            builder.withMethodBody(method ? "evaluatePart" : "evaluate",
                    method ? MethodTypeDesc.of(CD_Object, context, CD_int) : MethodTypeDesc.of(CD_Object, context),
                    ClassFile.ACC_PROTECTED, code -> { string(code, literal); code.areturn(); });
        });
    }
    private static void string(CodeBuilder code, String value) {
        if (value.length() < 8192) { code.ldc(value); return; }
        var builder = ClassDesc.of("java.lang.StringBuilder");
        code.new_(builder).dup().invokespecial(builder, "<init>", MethodTypeDesc.of(CD_void));
        for (int start = 0; start < value.length(); start += 8192)
            code.ldc(value.substring(start, Math.min(value.length(), start + 8192)))
                    .invokevirtual(builder, "append", MethodTypeDesc.of(builder, CD_String));
        code.invokevirtual(builder, "toString", MethodTypeDesc.of(CD_String));
    }
    private static String identity(String literal, boolean method) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(("ibarra-literal-v1:" + (method ? "method:" : "value:")).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            for (char unit : literal.toCharArray()) { digest.update((byte) (unit >>> 8)); digest.update((byte) unit); }
            return "ibarra-literal-v1:" + (method ? "method:" : "value:") + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
