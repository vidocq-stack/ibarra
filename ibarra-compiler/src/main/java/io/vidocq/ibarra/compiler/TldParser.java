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
        boolean validationRequired = false;
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
                if (validationRequired) throw new PageParseException(at, "Duplicate tag library validator");
                validationRequired = true;
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
        return new TagLibraryDescriptor(path, uri, listeners, tags, validationRequired, functions);
    }

    private TagDescriptor tag(Element element, String namespace, SourceLocation at, boolean tagFile) {
        var fields = new LinkedHashMap<String, String>();
        var attributes = new LinkedHashMap<String, TagAttribute>();
        boolean unsupported = tagFile;
        for (var child : DeploymentXml.children(element)) {
            requireNamespace(child, namespace, at);
            String key = child.getLocalName();
            if (Set.of("description", "display-name", "icon", "example", "tag-extension").contains(key)) continue;
            if (key.equals("variable")) { unsupported = true; continue; }
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
            if (key.equals("tei-class")) unsupported = true;
        }
        String name = required(fields, "name", at);
        if (!name.matches("[\\p{L}_][\\p{L}\\p{N}\\p{M}_.-]*")) throw new PageParseException(at, "Invalid tag name: " + name);
        String handler = tagFile ? null : required(fields, "tag-class", at);
        if (handler != null && !SourceVersion.isName(handler)) throw new PageParseException(at, "Invalid tag handler class");
        String content = fields.getOrDefault("body-content", tagFile ? "scriptless" : "JSP");
        if (!Set.of("JSP", "empty", "scriptless", "tagdependent").contains(content)) throw new PageParseException(at, "Invalid tag body-content: " + content);
        return new TagDescriptor(name, handler, content, attributes, bool(fields, "dynamic-attributes", false, at), unsupported);
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

    private static String namespace(Element element) { return Objects.toString(element.getNamespaceURI(), ""); }
    private static void requireNamespace(Element element, String namespace, SourceLocation at) {
        if (!namespace(element).equals(namespace)) throw new PageParseException(at, "Foreign namespace in TLD listener metadata");
    }
}
