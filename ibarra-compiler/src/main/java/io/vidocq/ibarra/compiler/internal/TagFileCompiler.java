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
import io.vidocq.ibarra.spi.PageDescriptor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Build-time catalog and compiler for static XML and restricted standard tag files. */
public final class TagFileCompiler {
    private static final String TAGDIR_PREFIX = "urn:jsptagdir:";
    private static final String JSP_NAMESPACE = PageParser.JSP_NAMESPACE;

    public record Result(Map<String, TagLibraryDescriptor> libraries, List<GeneratedPage> sources) {
        public Result { libraries = Map.copyOf(libraries); sources = List.copyOf(sources); }
    }
    private record Directory(String path, SourceLocation location) { }
    private record TagLibraryUse(String prefix, String uri, TagLibraryValidatorDescriptor validator) { }
    private record StandardTagFile(String bodyContent, List<TagVariableDescriptor> variables,
            Map<String, TagAttribute> attributes, List<String> imports, String declarations, String body,
            String libraryValidationDeclarations, String dynamicAttributesName) { }

    private TagFileCompiler() { }

    public static boolean referencesDirectory(TranslationUnit.Result unit, String directory) {
        for (var reference : unit.tagLibraries()) if (reference.uri().startsWith(TAGDIR_PREFIX))
            try {
                if (directory(reference.uri().substring(TAGDIR_PREFIX.length()), reference.location()).equals(directory)) return true;
            } catch (PageParseException ignored) { }
        return false;
    }

    public static Result compile(Collection<TranslationUnit.Result> units, GenerationOptions options, String packageName,
            List<Path> classPath) {
        var directories = new LinkedHashMap<String, Directory>();
        var aliases = new LinkedHashMap<String, String>();
        var referencedFiles = new HashSet<String>();
        for (var unit : units) for (var reference : unit.tagLibraries()) {
            if (!reference.uri().startsWith(TAGDIR_PREFIX)) continue;
            String path = directory(reference.uri().substring(TAGDIR_PREFIX.length()), reference.location());
            directories.putIfAbsent(path, new Directory(path, reference.location()));
            aliases.put(reference.uri(), path);
        }
        for (var unit : units) referencedFiles(unit.page().nodes(), referencedFiles);

        var generated = new ArrayList<GeneratedPage>();
        var byDirectory = new HashMap<String, Map<String, TagDescriptor>>();
        var classes = new BeanClasses(classPath);
        for (var directory : directories.values()) {
            var tags = new TreeMap<String, TagDescriptor>();
            var kinds = new HashMap<String, String>();
            String prefix = directory.path() + "/";
            for (var resource : new TreeMap<>(options.byteSources()).entrySet()) {
                String path = resource.getKey();
                if (!path.startsWith(prefix) || path.substring(prefix.length()).contains("/")) continue;
                String suffix = path.endsWith(".tagx") ? ".tagx" : path.endsWith(".tag") ? ".tag" : null;
                if (suffix == null) continue;
                String name = path.substring(prefix.length(), path.length() - suffix.length());
                if (name.isBlank() || name.contains("."))
                    throw new PageParseException(directory.location(), "Invalid tag-file name: " + path);
                String previousKind = kinds.putIfAbsent(name, suffix);
                if (previousKind != null)
                    throw new PageParseException(directory.location(), "Conflicting .tag and .tagx files for tag " + name);
                PageDescriptor.validatePath(path);
                String handler = packageName + ".TagFile_" + hash(path);
                if (suffix.equals(".tag")) {
                    if (referencedFiles.contains(path)) {
                        try {
                            var unit = new TranslationUnit(options).read(path, resource.getValue());
                            var tag = standardTagFile(unit, options, classes);
                            generated.add(new GeneratedPage(handler, source(handler, tag), Map.of()));
                            tags.put(name, new TagDescriptor(name, handler, tag.bodyContent(), tag.attributes(), tag.dynamicAttributesName() != null,
                                    false, true, null, false, null, tag.variables()));
                        } catch (PageParseException failure) {
                            tags.put(name, new TagDescriptor(name, null, "empty", Map.of(), false, true,
                                    false, failure.getMessage()));
                        }
                    } else tags.put(name, new TagDescriptor(name, handler, "scriptless", Map.of(), false, false, true));
                    continue;
                }
                if (referencedFiles.contains(path)) {
                    try {
                        var unit = new TranslationUnit(options).read(path, resource.getValue());
                        boolean declaration = XmlDeclaration.collectTagFile(unit.page());
                        String markup = staticMarkup(unit.page().nodes());
                        generated.add(new GeneratedPage(handler, source(handler, declaration, markup), Map.of()));
                        tags.put(name, new TagDescriptor(name, handler, "empty", Map.of(), false, false, true, null, declaration));
                    } catch (PageParseException failure) {
                        tags.put(name, new TagDescriptor(name, null, "empty", Map.of(), false, true, false, failure.getMessage()));
                        continue;
                    }
                }
                tags.putIfAbsent(name, new TagDescriptor(name, handler, "empty", Map.of(), false, false, true));
            }
            if (tags.isEmpty()) throw new PageParseException(directory.location(), "Tag directory contains no .tag or .tagx files: " + directory.path());
            byDirectory.put(directory.path(), Map.copyOf(tags));
        }

        var libraries = new LinkedHashMap<String, TagLibraryDescriptor>();
        for (var alias : aliases.entrySet()) {
            String path = alias.getValue();
            libraries.put(alias.getKey(), new TagLibraryDescriptor(path, alias.getKey(), List.of(), byDirectory.get(path), null, Map.of()));
        }
        return new Result(libraries, generated);
    }

    private static StandardTagFile standardTagFile(TranslationUnit.Result unit, GenerationOptions options, BeanClasses classes) {
        var nodes = unit.page().nodes();
        var referencedLibraries = TldReferences.discover(List.of(unit), options);
        var imports = new TreeSet<String>();
        var variables = new ArrayList<TagVariableDescriptor>();
        var variableNames = new HashSet<String>();
        var attributes = new LinkedHashMap<String, TagAttribute>();
        var declarations = new StringBuilder();
        var body = new StringBuilder();
        var bodyContent = "scriptless";
        String dynamicAttributesName = null;
        SourceLocation dynamicAttributesLocation = null;
        int nestedActionIndex = 0;
        for (var node : nodes) {
            if (node instanceof PageNode.Directive directive) {
                switch (directive.name()) {
                    case "tag" -> {
                        for (var attribute : directive.attributes().entrySet()) {
                            switch (attribute.getKey()) {
                                case "import" -> imports.addAll(imports(attribute.getValue(), directive.location()));
                                case "body-content" -> {
                                    String value = attribute.getValue();
                                    if (!Set.of("empty", "scriptless", "tagdependent").contains(value))
                                        throw new PageParseException(directive.location(), "Invalid tag-file body-content: " + value);
                                    bodyContent = value;
                                }
                                case "isELIgnored", "deferredSyntaxAllowedAsLiteral", "errorOnELNotFound" -> { }
                                case "dynamic-attributes" -> {
                                    String name = attribute.getValue();
                                    if (!javax.lang.model.SourceVersion.isIdentifier(name)
                                            || javax.lang.model.SourceVersion.isKeyword(name))
                                        throw new PageParseException(directive.location(),
                                                "Tag-file dynamic-attributes must name a Java identifier");
                                    if (dynamicAttributesName != null && !dynamicAttributesName.equals(name))
                                        throw new PageParseException(directive.location(),
                                                "Conflicting tag-file dynamic-attributes declarations");
                                    dynamicAttributesName = name;
                                    dynamicAttributesLocation = directive.location();
                                }
                                case "description", "display-name", "small-icon", "large-icon" -> { }
                                default -> throw new PageParseException(directive.location(),
                                        "Tag directive attribute is not supported: " + attribute.getKey());
                            }
                        }
                    }
                    case "variable" -> {
                        var variable = variable(directive);
                        if (!variableNames.add(variable.name()))
                            throw new PageParseException(directive.location(), "Duplicate tag-file variable: " + variable.name());
                        variables.add(variable);
                    }
                    case "attribute" -> {
                        var attribute = attribute(directive);
                        if (attributes.putIfAbsent(attribute.name(), attribute) != null)
                            throw new PageParseException(directive.location(), "Duplicate tag-file attribute: " + attribute.name());
                    }
                    case "taglib" -> { }
                    default -> throw new PageParseException(directive.location(),
                            "Directive in standard tag files is not supported: " + directive.name());
                }
            } else if (node instanceof PageNode.Text text) {
                if (!text.value().isEmpty()) body.append("out.write(").append(JavaSource.quote(text.value())).append(");\n");
            } else if (node instanceof PageNode.RawText text) {
                if (!text.value().isEmpty()) body.append("out.write(").append(JavaSource.quote(text.value())).append(");\n");
            } else if (node instanceof PageNode.Script script) {
                if (script.kind() == PageNode.ScriptKind.DECLARATION) declarations.append(script.code()).append('\n');
                else if (script.kind() == PageNode.ScriptKind.SCRIPTLET) body.append(script.code()).append('\n');
                else body.append("out.write(String.valueOf(").append(script.code()).append("));\n");
            } else if (node instanceof PageNode.Expression expression) {
                throw new PageParseException(expression.location(), "EL in standard tag files is not supported yet");
            } else if (node instanceof PageNode.Element element) {
                if (element.namespace().equals(JSP_NAMESPACE)) body.append(standardAction(element, nestedActionIndex++));
                else body.append(nestedAction(element, referencedLibraries, classes, nestedActionIndex++));
            }
        }
        if (dynamicAttributesName != null
                && (attributes.containsKey(dynamicAttributesName) || variableNames.contains(dynamicAttributesName)))
            throw new PageParseException(dynamicAttributesLocation,
                    "Tag-file dynamic-attributes name conflicts with a declared attribute or variable: " + dynamicAttributesName);
        var libraryUses = tagLibraryUses(nodes, referencedLibraries);
        String xmlView = PageDataXmlView.serialize(nodes, taglibNamespaces(nodes));
        return new StandardTagFile(bodyContent, List.copyOf(variables), Collections.unmodifiableMap(new LinkedHashMap<>(attributes)), List.copyOf(imports),
                declarations.toString(), body.toString(), libraryValidationDeclarations(libraryUses, xmlView), dynamicAttributesName);
    }

    private static String standardAction(PageNode.Element action, int index) {
        String local = action.name().substring(action.name().lastIndexOf(':') + 1);
        if (!local.equals("forward"))
            throw new PageParseException(action.location(), "Standard action in tag files is not supported yet: " + action.name());
        String page = action.attributes().get("page");
        if (page == null || page.isBlank())
            throw new PageParseException(action.location(), "jsp:forward in a tag file requires a page attribute");
        for (String name : action.attributes().keySet())
            if (!name.equals("page") && !name.equals("xmlns") && !name.startsWith("xmlns:"))
                throw new PageParseException(action.location(), "Unsupported jsp:forward attribute in a tag file: " + name);
        for (var child : action.children())
            if (!(child instanceof PageNode.Text text) || !text.value().isBlank())
                throw new PageParseException(action.location(), "jsp:param in tag-file jsp:forward is not supported yet");
        if (page.contains("${") || page.contains("#{") || page.contains("<%"))
            throw new PageParseException(action.location(), "Dynamic jsp:forward paths in tag files are not supported yet");
        String failure = "_jspxTagFileForwardFailure" + index;
        return "try { pageContext.forward(" + JavaSource.quote(page) + "); } catch (jakarta.servlet.ServletException "
                + failure + ") { throw new JspException(\"Could not forward from tag file\", " + failure + "); }\n"
                + "if (true) throw new jakarta.servlet.jsp.SkipPageException();\n";
    }

    private static TagAttribute attribute(PageNode.Directive directive) {
        var values = directive.attributes();
        String name = values.get("name");
        if (name == null || !javax.lang.model.SourceVersion.isIdentifier(name)
                || javax.lang.model.SourceVersion.isKeyword(name))
            throw new PageParseException(directive.location(), "Tag-file attribute name must be a Java identifier");
        for (String key : values.keySet()) if (!Set.of("name", "required", "rtexprvalue", "type", "fragment", "description").contains(key))
            throw new PageParseException(directive.location(), "Invalid tag-file attribute directive field: " + key);
        boolean fragment = booleanValue(values.getOrDefault("fragment", "false"), "fragment", directive.location());
        if (fragment) throw new PageParseException(directive.location(), "Fragment attributes in standard tag files are not supported yet");
        String type = values.getOrDefault("type", "java.lang.String");
        if (!validAttributeType(type))
            throw new PageParseException(directive.location(), "Invalid tag-file attribute type: " + type);
        return new TagAttribute(name, type, booleanValue(values.getOrDefault("required", "false"), "required", directive.location()),
                booleanValue(values.getOrDefault("rtexprvalue", "true"), "rtexprvalue", directive.location()), false);
    }

    private static boolean booleanValue(String value, String field, SourceLocation at) {
        if (value.equals("true")) return true;
        if (value.equals("false")) return false;
        throw new PageParseException(at, "Tag-file " + field + " must be true or false");
    }

    private static boolean validAttributeType(String type) {
        while (type.endsWith("[]")) type = type.substring(0, type.length() - 2);
        return Set.of("boolean", "byte", "short", "int", "long", "float", "double", "char").contains(type)
                || javax.lang.model.SourceVersion.isName(type);
    }

    private static Map<String, String> taglibNamespaces(List<PageNode> nodes) {
        var namespaces = new LinkedHashMap<String, String>();
        for (var node : nodes) if (node instanceof PageNode.Directive directive && directive.name().equals("taglib")) {
            String prefix = directive.attributes().get("prefix");
            String uri = directive.attributes().get("uri");
            if (prefix != null && uri != null) namespaces.putIfAbsent(prefix, uri);
        }
        return namespaces;
    }

    private static List<TagLibraryUse> tagLibraryUses(List<PageNode> nodes, TldReferences.Resolved libraries) {
        var uses = new LinkedHashMap<String, TagLibraryUse>();
        for (var node : nodes) if (node instanceof PageNode.Directive directive && directive.name().equals("taglib")) {
            String prefix = directive.attributes().get("prefix");
            String uri = directive.attributes().get("uri");
            if (prefix == null || uri == null) continue;
            var library = libraries.resolve(uri, directive.location(), true);
            if (library != null && library.validator() != null)
                uses.putIfAbsent(uri, new TagLibraryUse(prefix, uri, library.validator()));
        }
        return List.copyOf(uses.values());
    }

    private static String libraryValidationDeclarations(List<TagLibraryUse> uses, String xmlView) {
        if (uses.isEmpty()) return "";
        var source = new StringBuilder("private static boolean _jspxTagLibrariesValidated;\n")
                .append("private static synchronized void _jspxValidateTagLibraries() throws jakarta.servlet.jsp.JspException {\n")
                .append("if (_jspxTagLibrariesValidated) return;\n");
        int serial = 0;
        for (var use : uses) {
            String suffix = String.valueOf(serial++);
            String validator = "_jspxTagLibraryValidator" + suffix;
            String xml = "_jspxPageDataXml" + suffix;
            String messages = "_jspxTagLibraryMessages" + suffix;
            source.append("var ").append(validator).append(" = new ")
                    .append(use.validator().className().replace('$', '.')).append("();\n");
            if (!use.validator().initParameters().isEmpty()) {
                String parameters = use.validator().initParameters().entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .map(parameter -> "java.util.Map.<String,Object>entry(" + JavaSource.quote(parameter.getKey())
                                + ", " + JavaSource.quote(parameter.getValue()) + ")")
                        .collect(java.util.stream.Collectors.joining(", "));
                source.append(validator).append(".setInitParameters(java.util.Map.<String,Object>ofEntries(")
                        .append(parameters).append("));\n");
            }
            source.append("var ").append(xml).append(" = ").append(JavaSource.quote(xmlView)).append(";\n")
                    .append("try {\nvar ").append(messages).append(" = ").append(validator).append(".validate(")
                    .append(JavaSource.quote(use.prefix())).append(", ").append(JavaSource.quote(use.uri()))
                    .append(", new jakarta.servlet.jsp.tagext.PageData() {\n")
                    .append("@Override public java.io.InputStream getInputStream() { return new java.io.ByteArrayInputStream(")
                    .append(xml).append(".getBytes(java.nio.charset.StandardCharsets.UTF_8)); }\n});\n")
                    .append("if (").append(messages).append(" != null && ").append(messages)
                    .append(".length != 0) throw new jakarta.servlet.jsp.JspException(")
                    .append(JavaSource.quote("TagLibraryValidator validation failed for " + use.uri() + ": "))
                    .append(" + ").append(messages).append("[0].getMessage());\n")
                    .append("} finally { ").append(validator).append(".release(); }\n");
        }
        return source.append("_jspxTagLibrariesValidated = true;\n}\n").toString();
    }

    private static String nestedAction(PageNode.Element action, TldReferences.Resolved libraries, BeanClasses classes,
            int index) {
        var library = libraries.resolve(action.namespace(), action.location(), true);
        if (library == null) throw new PageParseException(action.location(), "No build-time tag library for " + action.namespace());
        String local = action.name().substring(action.name().lastIndexOf(':') + 1);
        var tag = library.tags().get(local);
        if (tag == null) throw new PageParseException(action.location(), "Unknown tag " + local + " in " + library.resourcePath());
        if (tag.unsupportedMetadata())
            throw new PageParseException(action.location(), tag.unsupportedReason() == null
                    ? "Tag metadata in standard tag files are not supported yet" : tag.unsupportedReason());
        if (!action.children().isEmpty())
            throw new PageParseException(action.location(), "Bodies on nested tags in standard tag files are not supported yet");
        if (tag.tagFile() || tag.teiClass() != null || !tag.variables().isEmpty())
            throw new PageParseException(action.location(), "Nested tag files and tag variables in standard tag files are not supported yet");
        var kind = classes.handler(tag.handlerClass(), action.location());
        var supplied = new LinkedHashMap<String, String>();
        for (var attribute : action.attributes().entrySet()) {
            if (attribute.getKey().equals("xmlns") || attribute.getKey().startsWith("xmlns:")) continue;
            if (!tag.attributes().containsKey(attribute.getKey())
                    && (!tag.dynamicAttributes() || !kind.dynamic() || attribute.getKey().contains(":")))
                throw new PageParseException(action.location(), "Unknown nested tag attribute: " + attribute.getKey());
            supplied.put(attribute.getKey(), attribute.getValue());
        }
        for (var attribute : tag.attributes().values()) if (attribute.required() && !supplied.containsKey(attribute.name()))
            throw new PageParseException(action.location(), "Required nested tag attribute: " + attribute.name());

        String handler = "_jspxNestedTag" + index;
        var source = new StringBuilder("{\nvar ").append(handler).append(" = new ").append(kind.sourceName()).append("();\n");
        if (kind.simple()) source.append(handler).append(".setJspContext(jspContext);\n");
        else source.append(handler).append(".setPageContext((jakarta.servlet.jsp.PageContext) jspContext);\n");
        if (kind.simple()) source.append(handler).append(".setParent(this);\n");
        else source.append(handler).append(".setParent(new jakarta.servlet.jsp.tagext.TagAdapter(this));\n");
        if (kind.jspIdConsumer()) source.append(handler).append(".setJspId(")
                .append(JavaSource.quote("ibarra_nested_tag_" + index)).append(");\n");
        for (var attribute : supplied.entrySet()) {
            var contract = tag.attributes().get(attribute.getKey());
            if (contract == null) {
                if (!tag.dynamicAttributes() || !kind.dynamic() || attribute.getKey().contains(":"))
                    throw new PageParseException(action.location(), "Unknown nested tag attribute: " + attribute.getKey());
                source.append(handler).append(".setDynamicAttribute(null, ")
                        .append(JavaSource.quote(attribute.getKey())).append(", ")
                        .append(JavaSource.quote(attribute.getValue())).append(");\n");
                continue;
            }
            var setter = classes.tagPropertySetter(tag.handlerClass(), contract.name(), action.location());
            String argument = nestedAttributeValue(attribute.getValue(), contract, setter.type(), action.location());
            source.append(handler).append('.').append(setter.name()).append("((").append(setter.type()).append(") (")
                    .append(argument).append("));\n");
        }
        if (kind.simple()) return source.append(handler).append(".doTag();\n}\n").toString();
        if (kind.buffered() || kind.catchFinally())
            throw new PageParseException(action.location(), "Body and exception callbacks on classic nested tags are not supported yet");
        return source.append("try {\nint ").append(handler).append("Start = ").append(handler).append(".doStartTag();\n")
                .append("if (").append(handler).append("Start != jakarta.servlet.jsp.tagext.Tag.SKIP_BODY")
                .append(" && ").append(handler).append("Start != jakarta.servlet.jsp.tagext.Tag.EVAL_BODY_INCLUDE)")
                .append(" throw new jakarta.servlet.jsp.JspException(\"Invalid doStartTag result\");\n")
                .append("int ").append(handler).append("End = ").append(handler).append(".doEndTag();\n")
                .append("if (").append(handler).append("End == jakarta.servlet.jsp.tagext.Tag.SKIP_PAGE) throw new jakarta.servlet.jsp.SkipPageException();\n")
                .append("if (").append(handler).append("End != jakarta.servlet.jsp.tagext.Tag.EVAL_PAGE)")
                .append(" throw new jakarta.servlet.jsp.JspException(\"Invalid doEndTag result\");\n")
                .append("} finally { ").append(handler).append(".release(); }\n}\n").toString();
    }

    private static String nestedAttributeValue(String value, TagAttribute attribute, String setterType, SourceLocation at) {
        var expression = java.util.regex.Pattern.compile("^\\s*<%=\\s*(.*?)\\s*%>\\s*$", java.util.regex.Pattern.DOTALL).matcher(value);
        if (expression.matches()) {
            if (!attribute.runtimeExpression())
                throw new PageParseException(at, "Request-time expression is not allowed for nested tag attribute: " + attribute.name());
            String code = expression.group(1).trim();
            return setterType.equals("java.lang.String") ? "java.lang.String.valueOf(" + code + ")" : code;
        }
        if (value.contains("<%") || value.contains("${") || value.contains("#{"))
            throw new PageParseException(at, "Expressions in nested tag attributes are not supported yet");
        if (!setterType.equals("java.lang.String") && !setterType.equals("java.lang.Object"))
            throw new PageParseException(at, "Literal nested tag attributes require String setters in standard tag files");
        return JavaSource.quote(value);
    }

    private static List<String> imports(String value, SourceLocation at) {
        var result = new ArrayList<String>();
        for (String item : value.split(",", -1)) {
            String name = item.trim();
            String type = name.endsWith(".*") ? name.substring(0, name.length() - 2) : name;
            if (name.isEmpty() || !javax.lang.model.SourceVersion.isName(type))
                throw new PageParseException(at, "Invalid tag-file import: " + name);
            result.add(name);
        }
        return result;
    }

    private static TagVariableDescriptor variable(PageNode.Directive directive) {
        var attributes = directive.attributes();
        if (attributes.containsKey("name-from-attribute"))
            throw new PageParseException(directive.location(), "name-from-attribute tag-file variables are not supported yet");
        String name = attributes.get("name-given");
        if (name == null || !javax.lang.model.SourceVersion.isIdentifier(name)
                || javax.lang.model.SourceVersion.isKeyword(name))
            throw new PageParseException(directive.location(), "Tag-file variable name-given must be a Java identifier");
        String className = attributes.getOrDefault("variable-class", "java.lang.String");
        if (!javax.lang.model.SourceVersion.isName(className))
            throw new PageParseException(directive.location(), "Invalid tag-file variable class: " + className);
        String declareValue = attributes.getOrDefault("declare", "true");
        if (!declareValue.equals("true") && !declareValue.equals("false"))
            throw new PageParseException(directive.location(), "Tag-file variable declare must be true or false");
        int scope = switch (attributes.getOrDefault("scope", "NESTED")) {
            case "NESTED" -> TagVariableDescriptor.NESTED;
            case "AT_BEGIN" -> TagVariableDescriptor.AT_BEGIN;
            case "AT_END" -> TagVariableDescriptor.AT_END;
            default -> throw new PageParseException(directive.location(), "Invalid tag-file variable scope");
        };
        return new TagVariableDescriptor(name, className, declareValue.equals("true"), scope);
    }

    private static void referencedFiles(List<PageNode> nodes, Set<String> result) {
        for (var node : nodes) if (node instanceof PageNode.Element element) {
            String uri = element.namespace();
            if (uri.startsWith(TAGDIR_PREFIX)) {
                String name = element.name().substring(element.name().lastIndexOf(':') + 1);
                String directory = directory(uri.substring(TAGDIR_PREFIX.length()), element.location());
                result.add(directory + "/" + name + ".tag");
                result.add(directory + "/" + name + ".tagx");
            }
            referencedFiles(element.children(), result);
        }
    }

    private static String directory(String token, SourceLocation at) {
        if (token.isBlank()) throw new PageParseException(at, "tagdir must name a directory");
        String path = token.startsWith("/") ? java.nio.file.Path.of(token).normalize().toString().replace('\\', '/')
                : TranslationUnit.resolve(at.source(), token);
        if (path.equals("/")) throw new PageParseException(at, "tagdir must name a directory below the web root");
        try { PageDescriptor.validatePath(path + "/_tagdir_validation.tagx"); }
        catch (IllegalArgumentException invalid) { throw new PageParseException(at, "Invalid tagdir: " + token, invalid); }
        return path;
    }

    private static String source(String className, boolean declaration, String markup) {
        int split = className.lastIndexOf('.');
        String source = "package " + className.substring(0, split) + ";\n"
                + "public final class " + className.substring(split + 1)
                + " extends jakarta.servlet.jsp.tagext.SimpleTagSupport {\n"
                + "public void doTag() throws java.io.IOException {\n";
        if (declaration) source += "getJspContext().getOut().write(\"<?xml version=\\\"1.0\\\" encoding=\\\"UTF-8\\\"?>\");\n";
        source += "getJspContext().getOut().write(" + JavaSource.quote(markup) + ");\n} }\n";
        return source;
    }

    private static String source(String className, StandardTagFile tag) {
        int split = className.lastIndexOf('.');
        var source = new StringBuilder("package ").append(className, 0, split).append(";\n")
                .append("import jakarta.servlet.jsp.*;\n")
                .append("import jakarta.servlet.jsp.tagext.SimpleTagSupport;\n")
                .append("import io.vidocq.ibarra.spi.TagFileJspContext;\n");
        for (String name : tag.imports()) source.append("import ").append(name).append(";\n");
        source.append("public final class ").append(className.substring(split + 1))
                .append(" extends SimpleTagSupport")
                .append(tag.dynamicAttributesName() == null ? " {\n"
                        : " implements jakarta.servlet.jsp.tagext.DynamicAttributes {\n")
                .append(tag.libraryValidationDeclarations());
        if (tag.dynamicAttributesName() != null)
            source.append("private final java.util.Map<String, Object> ").append(tag.dynamicAttributesName())
                    .append(" = new java.util.LinkedHashMap<>();\n");
        for (var attribute : tag.attributes().values()) {
            String setter = "set" + Character.toUpperCase(attribute.name().charAt(0)) + attribute.name().substring(1);
            source.append("private ").append(attribute.type()).append(' ').append(attribute.name()).append(";\n")
                    .append("public void ").append(setter).append('(').append(attribute.type()).append(" value) {")
                    .append(" this.").append(attribute.name()).append(" = value; getJspContext().setAttribute(")
                    .append(JavaSource.quote(attribute.name())).append(", value); }\n");
        }
        source.append("@Override public void setJspContext(JspContext context) {\n")
                .append("if (context instanceof PageContext page) super.setJspContext(new TagFileJspContext(page));\n")
                .append("else super.setJspContext(context);\n");
        if (tag.dynamicAttributesName() != null)
            source.append("getJspContext().setAttribute(").append(JavaSource.quote(tag.dynamicAttributesName())).append(", ")
                    .append(tag.dynamicAttributesName()).append(");\n");
        source.append("}\n")
                .append("public JspContext _jspxTagFileContext() { return getJspContext(); }\n");
        if (tag.dynamicAttributesName() != null)
            source.append("@Override public void setDynamicAttribute(String uri, String localName, Object value) throws JspException {\n")
                    .append("if (uri != null && !uri.isEmpty()) throw new JspException(\"Namespaced dynamic attributes are not supported on standard tag files\");\n")
                    .append(tag.dynamicAttributesName()).append(".put(localName, value);\n}\n");
        source
                .append(tag.declarations())
                .append("@Override public void doTag() throws java.io.IOException, jakarta.servlet.jsp.JspException {\n")
                .append(tag.libraryValidationDeclarations().isEmpty() ? "" : "_jspxValidateTagLibraries();\n")
                .append("var jspContext = getJspContext();\n")
                .append("var pageContext = (PageContext) jspContext;\n")
                .append("var out = jspContext.getOut();\n")
                .append("var application = pageContext.getServletContext();\n")
                .append("var config = pageContext.getServletConfig();\n")
                .append("var request = (jakarta.servlet.http.HttpServletRequest) pageContext.getRequest();\n")
                .append("var response = (jakarta.servlet.http.HttpServletResponse) pageContext.getResponse();\n")
                .append("var session = pageContext.getSession();\n")
                .append(tag.body())
                .append("}\n}\n");
        return source.toString();
    }

    private static String staticMarkup(List<PageNode> nodes) {
        var result = new StringBuilder();
        append(nodes, result);
        return result.toString();
    }

    private static void append(List<PageNode> nodes, StringBuilder result) {
        for (var node : nodes) {
            if (node instanceof PageNode.Text text) result.append(escapeText(text.value()));
            else if (node instanceof PageNode.RawText text) result.append(text.value());
            else if (node instanceof PageNode.Directive directive)
                throw new PageParseException(directive.location(), "Directives in XML tag files are not supported yet");
            else if (node instanceof PageNode.Expression expression)
                throw new PageParseException(expression.location(), "Expressions in XML tag files are not supported yet");
            else if (node instanceof PageNode.Script script)
                throw new PageParseException(script.location(), "Scripting in XML tag files is not supported yet");
            else if (node instanceof PageNode.Element element) element(element, result);
        }
    }

    private static void element(PageNode.Element element, StringBuilder result) {
        String local = element.name().substring(element.name().lastIndexOf(':') + 1);
        if (element.namespace().equals(JSP_NAMESPACE)) {
            if (local.equals("output")) return;
            if (local.equals("root") || local.equals("text")) { append(element.children(), result); return; }
            throw new PageParseException(element.location(), "Action in XML tag files is not supported yet: " + element.name());
        }
        result.append('<').append(element.name());
        for (var attribute : element.attributes().entrySet()) {
            if (attribute.getKey().startsWith("xmlns") && attribute.getValue().equals(JSP_NAMESPACE)) continue;
            if (attribute.getValue().contains("${") || attribute.getValue().contains("#{"))
                throw new PageParseException(element.location(), "Expressions in XML tag-file attributes are not supported yet");
            result.append(' ').append(attribute.getKey()).append("=\"").append(escapeAttribute(attribute.getValue())).append('"');
        }
        if (element.selfClosing()) result.append("/>");
        else {
            result.append('>'); append(element.children(), result); result.append("</").append(element.name()).append('>');
        }
    }

    private static String escapeText(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
    private static String escapeAttribute(String text) {
        return escapeText(text).replace("\"", "&quot;");
    }
    private static String hash(String path) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(path.getBytes(StandardCharsets.UTF_8))).substring(0, 24); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
