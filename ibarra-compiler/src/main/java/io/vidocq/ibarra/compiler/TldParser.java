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

package io.vidocq.ibarra.compiler;

import io.vidocq.ibarra.compiler.internal.DeploymentXml;
import java.util.*;
import javax.lang.model.SourceVersion;
import org.w3c.dom.*;

public final class TldParser {
    private static final Set<String> NAMESPACES = Set.of("", "https://jakarta.ee/xml/ns/jakartaee",
            "http://xmlns.jcp.org/xml/ns/javaee", "http://java.sun.com/xml/ns/javaee", "http://java.sun.com/xml/ns/j2ee");

    /** Reads build-time handler contracts and listener metadata without loading application classes. */
    public TagLibraryDescriptor parse(String path, byte[] bytes) {
        var at = new SourceLocation(path, 1, 1);
        var root = DeploymentXml.read(path, bytes).getDocumentElement();
        String namespace = namespace(root);
        if (!"taglib".equals(root.getLocalName()) || !NAMESPACES.contains(namespace))
            throw new PageParseException(at, "Expected a standard taglib root");
        String uri = null;
        var listeners = new ArrayList<String>();
        var tags = new LinkedHashMap<String, TagDescriptor>();
        var functions = new LinkedHashMap<String, TldFunction>();
        TagLibraryValidatorDescriptor validator = null;
        for (var child : DeploymentXml.children(root)) {
            String name = child.getLocalName();
            if (!Set.of("uri", "listener", "tag", "tag-file", "validator", "function").contains(name)) continue;
            requireNamespace(child, namespace, at);
            if (name.equals("function")) {
                var values = new LinkedHashMap<String, String>();
                for (var field : DeploymentXml.children(child)) {
                    requireNamespace(field, namespace, at);
                    String key = field.getLocalName();
                    if (Set.of("description", "display-name", "icon", "example", "function-extension").contains(key)) continue;
                    if (!Set.of("name", "function-class", "function-signature").contains(key) || values.putIfAbsent(key, field.getTextContent().trim()) != null)
                        throw new PageParseException(at, "Invalid function field: " + key);
                }
                String alias=values.get("name"), type=values.get("function-class"), signature=values.get("function-signature");
                if (alias==null || !SourceVersion.isIdentifier(alias) || type==null || !SourceVersion.isName(type) || signature==null || signature.isBlank())
                    throw new PageParseException(at, "Missing or invalid TLD function declaration");
                if (functions.putIfAbsent(alias, new TldFunction(alias, type, signature)) != null) throw new PageParseException(at, "Duplicate function: " + alias);
            } else if (name.equals("tag") || name.equals("tag-file")) {
                var tag = tag(child, namespace, at, name.equals("tag-file"));
                if (tags.putIfAbsent(tag.name(), tag) != null) throw new PageParseException(at, "Duplicate tag: " + tag.name());
            } else if (name.equals("validator")) {
                if (validator != null) throw new PageParseException(at, "Duplicate tag library validator");
                validator = validator(child, namespace, at);
            } else if (name.equals("uri")) {
                if (uri != null) throw new PageParseException(at, "Duplicate taglib URI");
                uri = child.getTextContent().trim();
                if (uri.isEmpty()) throw new PageParseException(at, "Empty taglib URI");
            } else {
                String type = null;
                for (var field : DeploymentXml.children(child)) {
                    requireNamespace(field, namespace, at);
                    if (field.getLocalName().equals("description")) continue;
                    if (!field.getLocalName().equals("listener-class") || type != null)
                        throw new PageParseException(at, "Expected one listener-class per TLD listener");
                    type = field.getTextContent().trim();
                }
                if (type == null || !SourceVersion.isName(type))
                    throw new PageParseException(at, "Invalid or missing TLD listener class");
                listeners.add(type);
            }
        }
        return new TagLibraryDescriptor(path, uri, listeners, tags, validator, functions);
    }

    private TagLibraryValidatorDescriptor validator(Element element, String namespace, SourceLocation at) {
        String className = null;
        var initParameters = new LinkedHashMap<String, String>();
        for (var field : DeploymentXml.children(element)) {
            requireNamespace(field, namespace, at);
            switch (field.getLocalName()) {
                case "description" -> { }
                case "validator-class" -> {
                    if (className != null) throw new PageParseException(at, "Duplicate tag library validator class");
                    className = field.getTextContent().trim();
                }
                case "init-param" -> {
                    String parameterName = null;
                    String parameterValue = null;
                    for (var parameterField : DeploymentXml.children(field)) {
                        requireNamespace(parameterField, namespace, at);
                        switch (parameterField.getLocalName()) {
                            case "description" -> { }
                            case "param-name" -> {
                                if (parameterName != null) throw new PageParseException(at, "Duplicate tag library validator parameter name");
                                parameterName = parameterField.getTextContent().trim();
                            }
                            case "param-value" -> {
                                if (parameterValue != null) throw new PageParseException(at, "Duplicate tag library validator parameter value");
                                parameterValue = parameterField.getTextContent().trim();
                            }
                            default -> throw new PageParseException(at, "Invalid tag library validator parameter field: "
                                    + parameterField.getLocalName());
                        }
                    }
                    if (parameterName == null || parameterName.isBlank() || parameterValue == null)
                        throw new PageParseException(at, "Missing tag library validator parameter name or value");
                    if (initParameters.putIfAbsent(parameterName, parameterValue) != null)
                        throw new PageParseException(at, "Duplicate tag library validator parameter: " + parameterName);
                }
                default -> throw new PageParseException(at, "Invalid tag library validator field: " + field.getLocalName());
            }
        }
        if (className == null || !SourceVersion.isName(className))
            throw new PageParseException(at, "Invalid or missing tag library validator class");
        return new TagLibraryValidatorDescriptor(className, initParameters);
    }

    private TagDescriptor tag(Element element, String namespace, SourceLocation at, boolean tagFile) {
        var fields = new LinkedHashMap<String, String>();
        var attributes = new LinkedHashMap<String, TagAttribute>();
        var variables = new ArrayList<TagVariableDescriptor>();
        boolean unsupported = tagFile;
        String unsupportedReason = null;
        for (var child : DeploymentXml.children(element)) {
            requireNamespace(child, namespace, at);
            String key = child.getLocalName();
            if (Set.of("description", "display-name", "icon", "example", "tag-extension").contains(key)) continue;
            if (key.equals("variable")) {
                var variable = variable(child, namespace, at);
                boolean duplicate = variables.stream().anyMatch(previous ->
                        variable.name() != null && variable.name().equals(previous.name())
                        || variable.nameFromAttribute() != null && variable.nameFromAttribute().equals(previous.nameFromAttribute()));
                if (duplicate) throw new PageParseException(at, "Duplicate tag variable name declaration");
                variables.add(variable);
                continue;
            }
            if (key.equals("attribute")) {
                var values = new LinkedHashMap<String, String>();
                String deferredValue = null; DeferredMethodSignature deferredMethod = null;
                for (var value : DeploymentXml.children(child)) {
                    requireNamespace(value, namespace, at);
                    String name = value.getLocalName();
                    if (name.equals("description")) continue;
                    if (name.equals("deferred-value") || name.equals("deferred-method")) {
                        if (deferredValue != null || deferredMethod != null) throw new PageParseException(at, "Conflicting or duplicate deferred declarations");
                        var deferredFields = new LinkedHashMap<String, String>();
                        String expected = name.equals("deferred-value") ? "type" : "method-signature";
                        for (var field : DeploymentXml.children(value)) {
                            requireNamespace(field, namespace, at);
                            if (!field.getLocalName().equals(expected) || deferredFields.putIfAbsent(expected, field.getTextContent().trim()) != null)
                                throw new PageParseException(at, "Invalid deferred attribute metadata");
                        }
                        if (name.equals("deferred-value")) {
                            deferredValue = deferredFields.getOrDefault(expected, "java.lang.Object").replaceAll("\\s+", "");
                            if (!DeferredMethodSignature.validType(deferredValue, false)) throw new PageParseException(at, "Invalid deferred value type: " + deferredValue);
                        } else deferredMethod = DeferredMethodSignature.parse(deferredFields.getOrDefault(expected, "void method()"), at);
                        continue;
                    }
                    if (!Set.of("name", "type", "required", "rtexprvalue", "fragment").contains(name)
                            || values.putIfAbsent(name, value.getTextContent().trim()) != null)
                        throw new PageParseException(at, "Invalid or duplicate tag attribute field: " + name);
                }
                String name = required(values, "name", at);
                if (!SourceVersion.isIdentifier(name))
                    throw new PageParseException(at, "Invalid tag attribute name: " + name);
                boolean fragment = bool(values, "fragment", false, at);
                boolean runtime = fragment || bool(values, "rtexprvalue", false, at);
                String type = values.getOrDefault("type", fragment ? "jakarta.servlet.jsp.tagext.JspFragment" : runtime ? "java.lang.Object" : "java.lang.String");
                if (fragment && (values.containsKey("type") || values.containsKey("rtexprvalue")))
                    throw new PageParseException(at, "Fragment attributes cannot declare type or rtexprvalue");
                if (fragment && (deferredValue != null || deferredMethod != null)) throw new PageParseException(at, "Fragment attributes cannot be deferred");
                if (!DeferredMethodSignature.validType(type, false))
                    throw new PageParseException(at, "Invalid tag attribute type: " + type);
                var attribute = new TagAttribute(name, type, bool(values, "required", false, at),
                        runtime, fragment, deferredValue, deferredMethod);
                if (attributes.putIfAbsent(name, attribute) != null) throw new PageParseException(at, "Duplicate tag attribute: " + name);
                continue;
            }
            if (!Set.of("name", "tag-class", "tei-class", "body-content", "dynamic-attributes", "path").contains(key)
                    || fields.putIfAbsent(key, child.getTextContent().trim()) != null)
                throw new PageParseException(at, "Invalid or duplicate tag field: " + key);
        }
        String name = required(fields, "name", at);
        if (!name.matches("[\\p{L}_][\\p{L}\\p{N}\\p{M}_.-]*")) throw new PageParseException(at, "Invalid tag name: " + name);
        String handler = tagFile ? null : required(fields, "tag-class", at);
        if (handler != null && !SourceVersion.isName(handler)) throw new PageParseException(at, "Invalid tag handler class");
        String tei = fields.get("tei-class");
        if (tei != null && !SourceVersion.isName(tei)) throw new PageParseException(at, "Invalid tag extra info class");
        String content = fields.getOrDefault("body-content", tagFile ? "scriptless" : "JSP");
        if (!Set.of("JSP", "empty", "scriptless", "tagdependent").contains(content)) throw new PageParseException(at, "Invalid tag body-content: " + content);
        for (var variable : variables) if (variable.nameFromAttribute() != null) {
            var attribute = attributes.get(variable.nameFromAttribute());
            if (attribute == null || !attribute.type().equals("java.lang.String") || !attribute.required()
                    || attribute.runtimeExpression() || attribute.fragment())
                throw new PageParseException(at, "Tag variable name-from-attribute must reference a required, non-runtime java.lang.String attribute: "
                        + variable.nameFromAttribute());
        }
        return new TagDescriptor(name, handler, content, attributes, bool(fields, "dynamic-attributes", false, at),
                unsupported, false, unsupportedReason, false, tei, variables);
    }

    private TagVariableDescriptor variable(Element element, String namespace, SourceLocation at) {
        var fields = new LinkedHashMap<String, String>();
        for (var child : DeploymentXml.children(element)) {
            requireNamespace(child, namespace, at);
            String name = child.getLocalName();
            if (name.equals("description")) continue;
            if (!Set.of("name-given", "name-from-attribute", "variable-class", "declare", "scope").contains(name)
                    || fields.putIfAbsent(name, child.getTextContent().trim()) != null)
                throw new PageParseException(at, "Invalid or duplicate tag variable field: " + name);
        }
        String given = fields.get("name-given"), fromAttribute = fields.get("name-from-attribute");
        if ((given == null) == (fromAttribute == null)) throw new PageParseException(at, "A tag variable needs exactly one name-given or name-from-attribute");
        if (given != null && !SourceVersion.isIdentifier(given)) throw new PageParseException(at, "Invalid tag variable name: " + given);
        if (fromAttribute != null && !SourceVersion.isIdentifier(fromAttribute))
            throw new PageParseException(at, "Invalid tag variable attribute name: " + fromAttribute);
        String className = fields.getOrDefault("variable-class", "java.lang.String");
        if (!SourceVersion.isName(className)) throw new PageParseException(at, "Invalid tag variable class: " + className);
        boolean declare = tagVariableDeclare(fields, at);
        int scope = switch (fields.getOrDefault("scope", "NESTED")) {
            case "NESTED" -> TagVariableDescriptor.NESTED;
            case "AT_BEGIN" -> TagVariableDescriptor.AT_BEGIN;
            case "AT_END" -> TagVariableDescriptor.AT_END;
            default -> throw new PageParseException(at, "Invalid tag variable scope");
        };
        return new TagVariableDescriptor(given, fromAttribute, className, declare, scope);
    }
    private static String required(Map<String, String> fields, String key, SourceLocation at) {
        String value = fields.get(key);
        if (value == null || value.isEmpty()) throw new PageParseException(at, "Missing tag metadata: " + key);
        return value;
    }
    private static boolean bool(Map<String, String> fields, String key, boolean fallback, SourceLocation at) {
        String value = fields.get(key);
        if (value == null) return fallback;
        if (!value.equals("true") && !value.equals("false")) throw new PageParseException(at, "Expected true or false for " + key);
        return Boolean.parseBoolean(value);
    }

    private static boolean tagVariableDeclare(Map<String, String> fields, SourceLocation at) {
        return switch (fields.getOrDefault("declare", "true")) {
            case "true", "yes" -> true;
            case "false", "no" -> false;
            default -> throw new PageParseException(at, "Expected true, false, yes, or no for declare");
        };
    }

    private static String namespace(Element element) { return Objects.toString(element.getNamespaceURI(), ""); }
    private static void requireNamespace(Element element, String namespace, SourceLocation at) {
        if (!namespace(element).equals(namespace)) throw new PageParseException(at, "Foreign namespace in TLD listener metadata");
    }
}
