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
import io.vidocq.ibarra.spi.PageDescriptor;
import java.util.*;
import javax.lang.model.SourceVersion;
import org.w3c.dom.*;

public final class TldParser {
    private static final Set<String> NAMESPACES = Set.of("", "https://jakarta.ee/xml/ns/jakartaee",
            "http://xmlns.jcp.org/xml/ns/javaee", "http://java.sun.com/xml/ns/javaee", "http://java.sun.com/xml/ns/j2ee");
    private static final String DESCRIPTION = "description";
    private static final String TAG_FILE = "tag-file";
    private static final String LISTENER_CLASS = "listener-class";
    private static final String ONE_LISTENER_CLASS = "Expected one listener-class per TLD listener";
    private static final String VALIDATOR_CLASS = "validator-class";
    private static final String PARAM_NAME = "param-name";
    private static final String PARAM_VALUE = "param-value";
    private static final String SMALL_ICON = "small-icon";
    private static final String LARGE_ICON = "large-icon";
    private static final String DEFERRED_VALUE = "deferred-value";
    private static final String RTEXPRVALUE = "rtexprvalue";
    private static final String JAVA_STRING = "java.lang.String";
    private static final Set<String> TAGLIB_CHILDREN = Set.of(DESCRIPTION, "tlib-version", "jsp-version", "short-name", "uri",
            "listener", "tag", TAG_FILE, "validator", "function");
    private static final Set<String> TAG_FIELDS = Set.of("name", "tag-class", "tei-class", "body-content", "dynamic-attributes", "path");
    private static final Set<String> ATTRIBUTE_FIELDS = Set.of("name", "type", "required", RTEXPRVALUE, "fragment");

    /** Reads build-time handler contracts and listener metadata without loading application classes. */
    public TagLibraryDescriptor parse(String path, byte[] bytes) {
        var at = new SourceLocation(path, 1, 1);
        var root = DeploymentXml.read(path, bytes).getDocumentElement();
        String namespace = namespace(root);
        if (!"taglib".equals(root.getLocalName()) || !NAMESPACES.contains(namespace))
            throw new PageParseException(at, "Expected a standard taglib root");
        var header = new LinkedHashMap<String, String>();
        var listeners = new ArrayList<String>();
        var tags = new LinkedHashMap<String, TagDescriptor>();
        var functions = new LinkedHashMap<String, TldFunction>();
        var validators = new ArrayList<TagLibraryValidatorDescriptor>();
        for (var child : DeploymentXml.children(root)) {
            String name = child.getLocalName();
            if (!TAGLIB_CHILDREN.contains(name)) continue;
            requireNamespace(child, namespace, at);
            switch (name) {
                case DESCRIPTION, "short-name", "tlib-version", "jsp-version" -> header.putIfAbsent(name, text(child));
                case "function" -> {
                    var function = function(child, namespace, at);
                    putOnce(functions, function.name(), function, at, "Duplicate function: " + function.name());
                }
                case "tag", TAG_FILE -> {
                    var tag = tag(child, namespace, at, name.equals(TAG_FILE));
                    putOnce(tags, tag.name(), tag, at, "Duplicate tag: " + tag.name());
                }
                case "validator" -> {
                    if (!validators.isEmpty()) throw new PageParseException(at, "Duplicate tag library validator");
                    validators.add(validator(child, namespace, at));
                }
                case "uri" -> putOnce(header, name, text(child), at, "Duplicate taglib URI");
                default -> listeners.add(listener(child, namespace, at));
            }
        }
        String uri = header.get("uri");
        if (uri != null && uri.isEmpty()) throw new PageParseException(at, "Empty taglib URI");
        return new TagLibraryDescriptor(path, uri, listeners, tags, validators.isEmpty() ? null : validators.getFirst(), functions,
                header.get(DESCRIPTION), header.get("short-name"), header.get("tlib-version"), requiredVersion(root, header));
    }

    /** The taglib version attribute, or the first jsp-version element when the attribute is absent or empty. */
    private static String requiredVersion(Element root, Map<String, String> header) {
        String version = root.hasAttribute("version") ? root.getAttribute("version").trim() : null;
        if (version == null || version.isEmpty()) return header.getOrDefault("jsp-version", version);
        return version;
    }

    private static TldFunction function(Element element, String namespace, SourceLocation at) {
        var values = new LinkedHashMap<String, String>();
        for (var field : DeploymentXml.children(element)) {
            requireNamespace(field, namespace, at);
            String key = field.getLocalName();
            if (Set.of(DESCRIPTION, "display-name", "icon", "example", "function-extension").contains(key)) continue;
            if (!Set.of("name", "function-class", "function-signature").contains(key) || values.putIfAbsent(key, text(field)) != null)
                throw new PageParseException(at, "Invalid function field: " + key);
        }
        String alias = values.get("name");
        String type = values.get("function-class");
        String signature = values.get("function-signature");
        if (alias == null || !SourceVersion.isIdentifier(alias) || type == null || !SourceVersion.isName(type)
                || signature == null || signature.isBlank())
            throw new PageParseException(at, "Missing or invalid TLD function declaration");
        return new TldFunction(alias, type, signature);
    }

    private static String listener(Element element, String namespace, SourceLocation at) {
        var fields = new LinkedHashMap<String, String>();
        for (var field : DeploymentXml.children(element)) {
            requireNamespace(field, namespace, at);
            String key = field.getLocalName();
            if (key.equals(DESCRIPTION)) continue;
            if (!key.equals(LISTENER_CLASS)) throw new PageParseException(at, ONE_LISTENER_CLASS);
            putOnce(fields, key, text(field), at, ONE_LISTENER_CLASS);
        }
        String type = fields.get(LISTENER_CLASS);
        if (type == null || !SourceVersion.isName(type))
            throw new PageParseException(at, "Invalid or missing TLD listener class");
        return type;
    }

    private static TagLibraryValidatorDescriptor validator(Element element, String namespace, SourceLocation at) {
        var fields = new LinkedHashMap<String, String>();
        var initParameters = new LinkedHashMap<String, String>();
        for (var field : DeploymentXml.children(element)) {
            requireNamespace(field, namespace, at);
            switch (field.getLocalName()) {
                case DESCRIPTION -> {
                    // Documentation only.
                }
                case VALIDATOR_CLASS -> putOnce(fields, VALIDATOR_CLASS, text(field), at, "Duplicate tag library validator class");
                case "init-param" -> initParameter(field, namespace, at, initParameters);
                default -> throw new PageParseException(at, "Invalid tag library validator field: " + field.getLocalName());
            }
        }
        String className = fields.get(VALIDATOR_CLASS);
        if (className == null || !SourceVersion.isName(className))
            throw new PageParseException(at, "Invalid or missing tag library validator class");
        return new TagLibraryValidatorDescriptor(className, initParameters);
    }

    private static void initParameter(Element element, String namespace, SourceLocation at, Map<String, String> initParameters) {
        var fields = new LinkedHashMap<String, String>();
        for (var field : DeploymentXml.children(element)) {
            requireNamespace(field, namespace, at);
            switch (field.getLocalName()) {
                case DESCRIPTION -> {
                    // Documentation only.
                }
                case PARAM_NAME -> putOnce(fields, PARAM_NAME, text(field), at, "Duplicate tag library validator parameter name");
                case PARAM_VALUE -> putOnce(fields, PARAM_VALUE, text(field), at, "Duplicate tag library validator parameter value");
                default -> throw new PageParseException(at, "Invalid tag library validator parameter field: " + field.getLocalName());
            }
        }
        String parameterName = fields.get(PARAM_NAME);
        String parameterValue = fields.get(PARAM_VALUE);
        if (parameterName == null || parameterName.isBlank() || parameterValue == null)
            throw new PageParseException(at, "Missing tag library validator parameter name or value");
        if (initParameters.putIfAbsent(parameterName, parameterValue) != null)
            throw new PageParseException(at, "Duplicate tag library validator parameter: " + parameterName);
    }

    private TagDescriptor tag(Element element, String namespace, SourceLocation at, boolean tagFile) {
        var fields = new LinkedHashMap<String, String>();
        var info = new LinkedHashMap<String, String>();
        var attributes = new LinkedHashMap<String, TagAttribute>();
        var variables = new ArrayList<TagVariableDescriptor>();
        for (var child : DeploymentXml.children(element)) {
            requireNamespace(child, namespace, at);
            String key = child.getLocalName();
            switch (key) {
                case DESCRIPTION, "display-name" -> info.putIfAbsent(key, text(child));
                case "icon" -> {
                    var icons = icons(child, namespace, at);
                    info.putIfAbsent(SMALL_ICON, icons.smallIcon());
                    info.putIfAbsent(LARGE_ICON, icons.largeIcon());
                }
                case "example", "tag-extension" -> {
                    // Documentation and vendor extensions carry no translation contract.
                }
                case "variable" -> addVariable(variables, variable(child, namespace, at), at);
                case "attribute" -> {
                    var attribute = attribute(child, namespace, at);
                    putOnce(attributes, attribute.name(), attribute, at, "Duplicate tag attribute: " + attribute.name());
                }
                default -> {
                    if (!TAG_FIELDS.contains(key) || fields.putIfAbsent(key, text(child)) != null)
                        throw new PageParseException(at, "Invalid or duplicate tag field: " + key);
                }
            }
        }
        String name = required(fields, "name", at);
        if (!name.matches("[\\p{L}_][\\p{L}\\p{N}\\p{M}_.-]*")) throw new PageParseException(at, "Invalid tag name: " + name);
        String tagFilePath = tagFilePath(fields, tagFile, at);
        String handler = tagFile ? null : required(fields, "tag-class", at);
        requireClassName(handler, at, "Invalid tag handler class");
        String tei = fields.get("tei-class");
        requireClassName(tei, at, "Invalid tag extra info class");
        String content = fields.getOrDefault("body-content", tagFile ? "scriptless" : "JSP");
        if (!Set.of("JSP", "empty", "scriptless", "tagdependent").contains(content)) throw new PageParseException(at, "Invalid tag body-content: " + content);
        requireNameFromAttributes(variables, attributes, at);
        return new TagDescriptor(name, handler, content, attributes, bool(fields, "dynamic-attributes", false, at),
                tagFile, tagFile, null, false, tei, variables, tagFilePath,
                info.get(DESCRIPTION), info.get("display-name"), info.get(SMALL_ICON), info.get(LARGE_ICON));
    }

    private static void requireClassName(String className, SourceLocation at, String message) {
        if (className != null && !SourceVersion.isName(className)) throw new PageParseException(at, message);
    }

    private static void addVariable(List<TagVariableDescriptor> variables, TagVariableDescriptor variable, SourceLocation at) {
        boolean duplicate = variables.stream().anyMatch(previous ->
                variable.name() != null && variable.name().equals(previous.name())
                || variable.nameFromAttribute() != null && variable.nameFromAttribute().equals(previous.nameFromAttribute()));
        if (duplicate) throw new PageParseException(at, "Duplicate tag variable name declaration");
        variables.add(variable);
    }

    private static String tagFilePath(Map<String, String> fields, boolean tagFile, SourceLocation at) {
        if (!tagFile) {
            if (fields.containsKey("path")) throw new PageParseException(at, "A tag path is only valid for a tag-file declaration");
            return null;
        }
        String tagFilePath = required(fields, "path", at);
        try {
            PageDescriptor.validatePath(tagFilePath);
        } catch (IllegalArgumentException invalid) {
            throw new PageParseException(at, "Invalid tag-file path: " + tagFilePath, invalid);
        }
        return tagFilePath;
    }

    private static void requireNameFromAttributes(List<TagVariableDescriptor> variables, Map<String, TagAttribute> attributes,
            SourceLocation at) {
        for (var variable : variables) {
            if (variable.nameFromAttribute() == null) continue;
            var attribute = attributes.get(variable.nameFromAttribute());
            if (attribute == null || !attribute.type().equals(JAVA_STRING) || !attribute.required()
                    || attribute.runtimeExpression() || attribute.fragment())
                throw new PageParseException(at, "Tag variable name-from-attribute must reference a required, non-runtime java.lang.String attribute: "
                        + variable.nameFromAttribute());
        }
    }

    /** The deferred-value type or deferred-method signature of a tag attribute. */
    private record Deferred(String valueType, DeferredMethodSignature method) { }

    private static TagAttribute attribute(Element element, String namespace, SourceLocation at) {
        var values = new LinkedHashMap<String, String>();
        Deferred deferred = attributeFields(element, namespace, at, values);
        String name = required(values, "name", at);
        if (!SourceVersion.isIdentifier(name))
            throw new PageParseException(at, "Invalid tag attribute name: " + name);
        boolean fragment = bool(values, "fragment", false, at);
        boolean runtime = fragment || bool(values, RTEXPRVALUE, false, at);
        String type = values.getOrDefault("type", defaultAttributeType(fragment, runtime));
        if (fragment && (values.containsKey("type") || values.containsKey(RTEXPRVALUE)))
            throw new PageParseException(at, "Fragment attributes cannot declare type or rtexprvalue");
        if (fragment && deferred != null) throw new PageParseException(at, "Fragment attributes cannot be deferred");
        if (!DeferredMethodSignature.validType(type, false))
            throw new PageParseException(at, "Invalid tag attribute type: " + type);
        return new TagAttribute(name, type, bool(values, "required", false, at), runtime, fragment,
                deferred == null ? null : deferred.valueType(), deferred == null ? null : deferred.method());
    }

    /** Collects the plain fields of a tag attribute into {@code values} and returns its deferred metadata, if any. */
    private static Deferred attributeFields(Element element, String namespace, SourceLocation at, Map<String, String> values) {
        Deferred deferred = null;
        for (var value : DeploymentXml.children(element)) {
            requireNamespace(value, namespace, at);
            String name = value.getLocalName();
            if (name.equals(DESCRIPTION)) continue;
            if (name.equals(DEFERRED_VALUE) || name.equals("deferred-method")) {
                if (deferred != null) throw new PageParseException(at, "Conflicting or duplicate deferred declarations");
                deferred = deferred(value, name, namespace, at);
            } else if (!ATTRIBUTE_FIELDS.contains(name) || values.putIfAbsent(name, text(value)) != null) {
                throw new PageParseException(at, "Invalid or duplicate tag attribute field: " + name);
            }
        }
        return deferred;
    }

    private static String defaultAttributeType(boolean fragment, boolean runtime) {
        if (fragment) return "jakarta.servlet.jsp.tagext.JspFragment";
        return runtime ? "java.lang.Object" : JAVA_STRING;
    }

    private static Deferred deferred(Element element, String name, String namespace, SourceLocation at) {
        var deferredFields = new LinkedHashMap<String, String>();
        String expected = name.equals(DEFERRED_VALUE) ? "type" : "method-signature";
        for (var field : DeploymentXml.children(element)) {
            requireNamespace(field, namespace, at);
            if (!field.getLocalName().equals(expected) || deferredFields.putIfAbsent(expected, text(field)) != null)
                throw new PageParseException(at, "Invalid deferred attribute metadata");
        }
        if (!name.equals(DEFERRED_VALUE))
            return new Deferred(null, DeferredMethodSignature.parse(deferredFields.getOrDefault(expected, "void method()"), at));
        String valueType = deferredFields.getOrDefault(expected, "java.lang.Object").replaceAll("\\s+", "");
        if (!DeferredMethodSignature.validType(valueType, false)) throw new PageParseException(at, "Invalid deferred value type: " + valueType);
        return new Deferred(valueType, null);
    }

    private record TagIcons(String smallIcon, String largeIcon) { }

    private static TagIcons icons(Element element, String namespace, SourceLocation at) {
        var icons = new LinkedHashMap<String, String>();
        for (var child : DeploymentXml.children(element)) {
            requireNamespace(child, namespace, at);
            switch (child.getLocalName()) {
                case SMALL_ICON -> putOnce(icons, SMALL_ICON, text(child), at, "Duplicate TLD small icon");
                case LARGE_ICON -> putOnce(icons, LARGE_ICON, text(child), at, "Duplicate TLD large icon");
                case DESCRIPTION -> {
                    // Documentation only.
                }
                default -> throw new PageParseException(at, "Invalid TLD icon field: " + child.getLocalName());
            }
        }
        return new TagIcons(icons.get(SMALL_ICON), icons.get(LARGE_ICON));
    }

    private static String text(Element element) { return element.getTextContent().trim(); }

    /** Records a single-valued field, failing with {@code duplicate} when it was already declared. */
    private static <T> void putOnce(Map<String, T> fields, String key, T value, SourceLocation at, String duplicate) {
        if (fields.putIfAbsent(key, value) != null) throw new PageParseException(at, duplicate);
    }

    private TagVariableDescriptor variable(Element element, String namespace, SourceLocation at) {
        var fields = new LinkedHashMap<String, String>();
        for (var child : DeploymentXml.children(element)) {
            requireNamespace(child, namespace, at);
            String name = child.getLocalName();
            if (name.equals(DESCRIPTION)) continue;
            if (!Set.of("name-given", "name-from-attribute", "variable-class", "declare", "scope").contains(name)
                    || fields.putIfAbsent(name, child.getTextContent().trim()) != null)
                throw new PageParseException(at, "Invalid or duplicate tag variable field: " + name);
        }
        String given = fields.get("name-given");
        String fromAttribute = fields.get("name-from-attribute");
        if ((given == null) == (fromAttribute == null)) throw new PageParseException(at, "A tag variable needs exactly one name-given or name-from-attribute");
        if (given != null && !SourceVersion.isIdentifier(given)) throw new PageParseException(at, "Invalid tag variable name: " + given);
        if (fromAttribute != null && !SourceVersion.isIdentifier(fromAttribute))
            throw new PageParseException(at, "Invalid tag variable attribute name: " + fromAttribute);
        String className = fields.getOrDefault("variable-class", JAVA_STRING);
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
