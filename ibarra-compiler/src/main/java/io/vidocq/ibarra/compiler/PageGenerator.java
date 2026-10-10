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

import io.vidocq.ibarra.compiler.internal.*;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.*;
import javax.lang.model.SourceVersion;

/** Generates build-time servlet source. No generated class is loaded by this API. */
public final class PageGenerator {
    private final BeanClasses classes;
    public PageGenerator(List<Path> beanModulePath) { classes = new BeanClasses(beanModulePath); }

    public GeneratedPage generate(String path, String source, String className, GenerationOptions options) {
        if (!SourceVersion.isName(className) || !className.contains("."))
            throw new IllegalArgumentException("Generated class needs a qualified Java name: " + className);
        var unit = new TranslationUnit(options).read(path, source);
        return new Generator(unit.page(), className, unit.properties(), unit.sourceSyntaxes(), unit.responseEncoding(), options.allowStartupEl()).generate();
    }

    /** Detects and strictly decodes the requested file's byte encoding before translation. */
    public GeneratedPage generate(String path, byte[] source, String className, GenerationOptions options) {
        if (!SourceVersion.isName(className) || !className.contains("."))
            throw new IllegalArgumentException("Generated class needs a qualified Java name: " + className);
        var unit = new TranslationUnit(options).read(path, source);
        return new Generator(unit.page(), className, unit.properties(), unit.sourceSyntaxes(), unit.responseEncoding(), options.allowStartupEl()).generate();
    }

    private final class Generator {
        private static final Set<String> PAGE_ATTRIBUTES = Set.of("language", "import", "extends", "session",
                "buffer", "autoFlush", "contentType", "pageEncoding", "errorPage", "isErrorPage", "info",
                "isELIgnored", "deferredSyntaxAllowedAsLiteral", "trimDirectiveWhitespaces", "errorOnELNotFound");
        private final Page page;
        private final String className;
        private final Map<String, String> settings = new LinkedHashMap<>();
        private final Map<String, SourceLocation> settingLocations = new HashMap<>();
        private final Map<String, Page.Syntax> sourceSyntaxes;
        private final String responseEncoding;
        private final List<String> imports = new ArrayList<>();
        private final JavaSource declarations = new JavaSource();
        private final JavaSource body = new JavaSource();
        private final List<String> expressions = new ArrayList<>();
        private final Map<String, String> beans = new LinkedHashMap<>();
        private boolean needsFactory;
        private int serial;
        private final boolean startupElAllowed;

        Generator(Page page, String className, Map<String, String> properties, Map<String, Page.Syntax> sourceSyntaxes, String responseEncoding, boolean startupElAllowed) {
            this.page = page; this.className = className;
            this.sourceSyntaxes = sourceSyntaxes;
            this.responseEncoding = responseEncoding;
            this.startupElAllowed = startupElAllowed;
            copy(properties, "default-content-type", "contentType");
            copy(properties, "buffer", "buffer");
            copy(properties, "page-encoding", "pageEncoding");
            copy(properties, "trim-directive-whitespaces", "trimDirectiveWhitespaces");
            copy(properties, "scripting-invalid", "scriptingInvalid");
            copy(properties, "el-ignored", "isELIgnored");
            copy(properties, "deferred-syntax-allowed-as-literal", "deferredSyntaxAllowedAsLiteral");
            copy(properties, "error-on-el-not-found", "errorOnELNotFound");
            copy(properties, "error-on-undeclared-namespace", "errorOnUndeclaredNamespace");
        }
        private void copy(Map<String, String> values, String from, String to) {
            if (values.containsKey(from)) settings.put(to, values.get(from));
        }

        GeneratedPage generate() {
            directives(page.nodes());
            if (settings.containsKey("language") && !settings.get("language").equals("java"))
                throw error(page.nodes().isEmpty() ? new SourceLocation(page.source(), 1, 1) : page.nodes().getFirst().location(), "Only Java scripting is supported");
            emit(page.nodes());
            int dot = className.lastIndexOf('.');
            String packageName = className.substring(0, dot);
            String simple = className.substring(dot + 1);
            var result = new JavaSource();
            result.add("package " + packageName + ";\n");
            result.add("import jakarta.servlet.*;\nimport jakarta.servlet.http.*;\nimport jakarta.servlet.jsp.*;\n");
            for (String name : imports) result.add("import " + name + ";\n");
            result.add("public final class " + simple + " extends " + simple + "$Lifecycle {\n");
            for (int i = 0; i < expressions.size(); i++) result.add("private jakarta.el.ValueExpression _jspxExpression" + i + ";\n");
            result.add(declarations);
            result.add("@Override protected final void _jspxInitialize() {\n");
            if (!expressions.isEmpty()) {
                result.add("var factory = jakarta.servlet.jsp.JspFactory.getDefaultFactory().getJspApplicationContext(getServletContext()).getExpressionFactory();\n");
                result.add("var context = new jakarta.el.StandardELContext(factory);\n");
                for (int i = 0; i < expressions.size(); i++)
                    result.add("_jspxExpression" + i + " = factory.createValueExpression(context, " + JavaSource.quote(expressions.get(i)) + ", Object.class);\n");
            }
            result.add("}\n");
            result.add("@Override public final void _jspService(jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) throws jakarta.servlet.ServletException, java.io.IOException {\n");
            result.add("response.setContentType(" + JavaSource.quote(contentType()) + ");\n");
            result.add("var _jspxFactory = jakarta.servlet.jsp.JspFactory.getDefaultFactory();\n");
            result.add("if (_jspxFactory == null) throw new jakarta.servlet.ServletException(\"No JSP factory installed\");\n");
            result.add("var pageContext = _jspxFactory.getPageContext(this, request, response, "
                    + (settings.containsKey("errorPage") ? JavaSource.quote(settings.get("errorPage")) : "null") + ", "
                    + bool("session", true) + ", " + buffer() + ", " + bool("autoFlush", true) + ");\n");
            result.add("if (pageContext == null) throw new jakarta.servlet.ServletException(\"Cannot initialize page context\");\n");
            result.add("var out = pageContext.getOut();\nvar application = getServletContext();\nvar config = getServletConfig();\nObject page = this;\n");
            if (bool("session", true)) result.add("var session = pageContext.getSession();\n");
            if (bool("isErrorPage", false)) result.add("Throwable exception = pageContext.getErrorData().getThrowable();\n");
            result.add("boolean _jspxFailed = false, _jspxHandled = false, _jspxForwarded = false;\ntry {\n");
            if (needsFactory) result.add("var _jspxExpressionFactory = _jspxFactory.getJspApplicationContext(application).getExpressionFactory();\n");
            result.add(body);
            result.add("} catch (Throwable failure) { _jspxFailed = true; pageContext.handlePageException(failure); _jspxHandled = true; } finally {\n");
            result.add("try { if (!_jspxForwarded && (!_jspxFailed || (_jspxHandled && out.getRemaining() < out.getBufferSize()))) out.flush(); } finally { _jspxFactory.releasePageContext(pageContext); }\n}\n}\n");
            result.add("private static String _jspxText(Object value) { return value == null ? \"\" : String.valueOf(value); }\n");
            result.add("private static Object _jspxBean(jakarta.servlet.jsp.PageContext context, String name) throws jakarta.servlet.ServletException { Object bean = context.findAttribute(name); if (bean == null) throw new jakarta.servlet.ServletException(\"No bean named \" + name); return bean; }\n");
            result.add("private static String _jspxAttribute(String value) { return value.replace(\"&\", \"&amp;\").replace(\"\\\"\", \"&quot;\").replace(\"<\", \"&lt;\"); }\n");
            result.add("private static String _jspxXmlName(String value) { if (!value.matches(\"[\\\\p{L}_][\\\\p{L}\\\\p{N}\\\\p{M}_.:-]*\")) throw new IllegalArgumentException(\"Invalid XML name: \" + value); return value; }\n");
            result.add("private static String _jspxParameter(String path, String name, Object value) { return path + (path.contains(\"?\") ? \"&\" : \"?\") + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8) + \"=\" + java.net.URLEncoder.encode(_jspxText(value), java.nio.charset.StandardCharsets.UTF_8); }\n");
            result.add("}\nabstract class " + simple + "$Lifecycle extends " + settings.getOrDefault("extends", "jakarta.servlet.http.HttpServlet") + " implements jakarta.servlet.jsp.HttpJspPage, io.vidocq.ibarra.spi.PageOptions {\n");
            result.add("@Override public final boolean errorOnELNotFound() { return " + bool("errorOnELNotFound", false) + "; }\n");
            result.add("protected abstract void _jspxInitialize();\npublic void jspInit() {}\npublic void jspDestroy() {}\n");
            result.add("@Override public final void init() throws jakarta.servlet.ServletException { super.init(); _jspxInitialize(); jspInit(); }\n");
            result.add("@Override public final void init(jakarta.servlet.ServletConfig config) throws jakarta.servlet.ServletException { super.init(config); }\n");
            result.add("@Override public final void destroy() { try { jspDestroy(); } finally { super.destroy(); } }\n");
            result.add("@Override protected final void service(jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) throws jakarta.servlet.ServletException, java.io.IOException { _jspService(request, response); }\n");
            result.add("@Override public final void service(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) throws jakarta.servlet.ServletException, java.io.IOException { super.service(request, response); }\n");
            if (settings.containsKey("info")) result.add("@Override public String getServletInfo() { return " + JavaSource.quote(settings.get("info")) + "; }\n");
            result.add("}\n");
            return new GeneratedPage(className, result.text(), result.locations());
        }

        private void directives(List<PageNode> nodes) {
            var explicit = new HashMap<String, String>();
            collectDirectives(nodes, explicit);
            settings.putAll(explicit);
            if (settings.containsKey("extends") && !SourceVersion.isName(settings.get("extends")))
                throw error(new SourceLocation(page.source(), 1, 1), "Invalid page superclass");
        }
        private void collectDirectives(List<PageNode> nodes, Map<String, String> explicit) {
            for (var node : nodes) {
                if (node instanceof PageNode.Directive directive) {
                    if (directive.name().equals("taglib")) continue;
                    if (!directive.name().equals("page")) throw error(directive.location(), "Unsupported directive: " + directive.name());
                    for (var entry : directive.attributes().entrySet()) {
                        if (entry.getKey().startsWith("xmlns")) continue;
                        if (!PAGE_ATTRIBUTES.contains(entry.getKey())) throw error(directive.location(), "Unsupported page attribute: " + entry.getKey());
                        if (entry.getKey().equals("pageEncoding") && !directive.location().source().equals(page.source())) continue;
                        if (Set.of("session", "autoFlush", "isErrorPage", "isELIgnored",
                                "deferredSyntaxAllowedAsLiteral", "trimDirectiveWhitespaces", "errorOnELNotFound").contains(entry.getKey())
                                && !entry.getValue().equals("true") && !entry.getValue().equals("false"))
                            throw error(directive.location(), entry.getKey() + " must be true or false");
                        if (entry.getKey().equals("import")) {
                            for (String name : entry.getValue().split(",")) {
                                name = name.trim();
                                String base = name.startsWith("static ") ? name.substring(7) : name;
                                if (!SourceVersion.isName(base.endsWith(".*") ? base.substring(0, base.length() - 2) : base))
                                    throw error(directive.location(), "Invalid page import: " + name);
                                imports.add(name);
                            }
                        } else {
                            String previous = explicit.putIfAbsent(entry.getKey(), entry.getValue());
                            settingLocations.putIfAbsent(entry.getKey(), directive.location());
                            if (previous != null && !previous.equals(entry.getValue()))
                                throw error(directive.location(), "Conflicting page attribute: " + entry.getKey());
                        }
                    }
                } else if (node instanceof PageNode.Element element) collectDirectives(element.children(), explicit);
            }
        }

        private void emit(List<PageNode> nodes) {
            for (var node : nodes) {
                if (node instanceof PageNode.Text text) {
                    if (!bool("trimDirectiveWhitespaces", false) || !text.value().chars().allMatch(c -> c == ' ' || c == '\t' || c == '\r' || c == '\n'))
                        write(text.value(), text.location());
                } else if (node instanceof PageNode.Expression expression) {
                    if (expression.deferred()) throw error(expression.location(), "Deferred EL in template text is not supported");
                    body.add("out.write(_jspxText(" + expression("${" + expression.code() + "}", expression.location()) + "));\n", expression.location());
                } else if (node instanceof PageNode.Script script) {
                    if (bool("scriptingInvalid", false)) throw error(script.location(), "Scripting is disabled by configuration");
                    if (script.kind() == PageNode.ScriptKind.DECLARATION) declarations.add(script.code() + "\n", script.location());
                    else if (script.kind() == PageNode.ScriptKind.EXPRESSION) body.add("out.print(" + script.code() + ");\n", script.location());
                    else body.add(script.code() + "\n", script.location());
                } else if (node instanceof PageNode.Element element) element(element);
            }
        }

        private void element(PageNode.Element node) {
            String local = node.name().substring(node.name().lastIndexOf(':') + 1);
            if (!node.namespace().equals(PageParser.JSP_NAMESPACE)) {
                if (sourceSyntaxes.get(node.location().source()) == Page.Syntax.STANDARD || node.namespace().startsWith("urn:jsptag")
                        || node.namespace().startsWith("urn:jsptld") || node.namespace().startsWith("jakarta.tags."))
                    throw error(node.location(), "Custom tags require the later tag-handler milestone: " + node.name());
                write("<" + node.name(), node.location());
                for (var entry : node.attributes().entrySet()) {
                    if (entry.getKey().startsWith("xmlns") && entry.getValue().equals(PageParser.JSP_NAMESPACE)) continue;
                    write(" " + entry.getKey() + "=\"", node.location());
                    body.add("out.write(_jspxText(" + value(entry.getValue(), node.location(), true) + "));\n", node.location());
                    write("\"", node.location());
                }
                write(node.selfClosing() ? "/>" : ">", node.location());
                if (!node.selfClosing()) { emit(node.children()); write("</" + node.name() + ">", node.location()); }
                return;
            }
            switch (local) {
                case "root", "text" -> emit(node.children());
                case "element" -> dynamicElement(node);
                case "include", "forward" -> dispatch(node, local.equals("forward"));
                case "useBean" -> bean(node);
                case "getProperty" -> getProperty(node);
                case "setProperty" -> setProperty(node);
                case "output" -> {
                    allowed(node, Set.of("omit-xml-declaration"));
                    if (!boolValue(node.attributes().getOrDefault("omit-xml-declaration", "false"), node.location()))
                        write("<?xml version=\"1.0\" encoding=\"" + settings.getOrDefault("pageEncoding", "UTF-8") + "\"?>", node.location());
                }
                default -> throw error(node.location(), "Unsupported standard action: " + node.name());
            }
        }

        private void dynamicElement(PageNode.Element node) {
            allowed(node, Set.of("name"));
            String variable = "_jspxElement" + serial++;
            body.add("String " + variable + " = _jspxXmlName(_jspxText(" + value(required(node, "name"), node.location(), false) + "));\n", node.location());
            body.add("out.write(\"<\" + " + variable + ");\n", node.location());
            var content = new ArrayList<PageNode>();
            var names = new HashSet<String>();
            boolean hasBody = false;
            for (var child : node.children()) {
                if (child instanceof PageNode.Element attribute && attribute.namespace().equals(PageParser.JSP_NAMESPACE)
                        && attribute.name().substring(attribute.name().lastIndexOf(':') + 1).equals("attribute")) {
                    if (hasBody) throw error(attribute.location(), "jsp:attribute must precede the element body");
                    allowed(attribute, Set.of("name", "trim"));
                    String name = required(attribute, "name");
                    if (!name.matches("[\\p{L}_][\\p{L}\\p{N}\\p{M}_.:-]*") || !names.add(name))
                        throw error(attribute.location(), "Invalid or duplicate element attribute: " + name);
                    String captured = "_jspxAttribute" + serial++;
                    String writer = "_jspxBody" + serial++;
                    body.add("String " + captured + ";\nvar " + writer + " = pageContext.pushBody();\ntry { out = " + writer + ";\n", attribute.location());
                    var children = new ArrayList<>(attribute.children());
                    if (boolValue(attribute.attributes().getOrDefault("trim", "true"), attribute.location()) && !children.isEmpty()) {
                        if (children.getFirst() instanceof PageNode.Text text) {
                            int start = 0;
                            while (start < text.value().length() && xmlSpace(text.value().charAt(start))) start++;
                            children.set(0, new PageNode.Text(text.value().substring(start), text.location().advance(text.value().substring(0, start))));
                        }
                        if (children.getLast() instanceof PageNode.Text text) {
                            int end = text.value().length();
                            while (end > 0 && xmlSpace(text.value().charAt(end - 1))) end--;
                            children.set(children.size() - 1, new PageNode.Text(text.value().substring(0, end), text.location()));
                        }
                    }
                    emit(children);
                    body.add(captured + " = " + writer + ".getString();\n} finally { out = pageContext.popBody(); }\n", attribute.location());
                    body.add("out.write(" + JavaSource.quote(" " + name + "=\"") + " + _jspxAttribute(" + captured + ") + \"\\\"\");\n", attribute.location());
                } else if (child instanceof PageNode.Text text && !hasBody && text.value().isBlank()) {
                    // Formatting between named attributes is not element body content.
                } else {
                    hasBody = true;
                    if (child instanceof PageNode.Element wrapper && wrapper.namespace().equals(PageParser.JSP_NAMESPACE)
                            && wrapper.name().substring(wrapper.name().lastIndexOf(':') + 1).equals("body")) {
                        allowed(wrapper, Set.of()); content.addAll(wrapper.children());
                    } else content.add(child);
                }
            }
            if (content.isEmpty()) body.add("out.write(\"/>\");\n", node.location());
            else {
                write(">", node.location()); emit(content);
                body.add("out.write(\"</\" + " + variable + " + \">\");\n", node.location());
            }
        }

        private void dispatch(PageNode.Element node, boolean forward) {
            allowed(node, forward ? Set.of("page") : Set.of("page", "flush"));
            String variable = "_jspxPath" + serial++;
            body.add("String " + variable + " = _jspxText(" + value(required(node, "page"), node.location(), false) + ");\n", node.location());
            for (var child : node.children()) {
                if (child instanceof PageNode.Text text && text.value().isBlank()) continue;
                if (!(child instanceof PageNode.Element param) || !param.namespace().equals(PageParser.JSP_NAMESPACE)
                        || !param.name().endsWith(":param")) throw error(child.location(), "Dispatch body accepts only jsp:param");
                allowed(param, Set.of("name", "value"));
                String name = required(param, "name");
                if (name.contains("${") || name.contains("<%=")) throw error(param.location(), "Parameter name must be literal");
                body.add(variable + " = _jspxParameter(" + variable + ", " + JavaSource.quote(name) + ", "
                        + value(required(param, "value"), param.location(), false) + ");\n", param.location());
            }
            if (forward) body.add("pageContext.forward(" + variable + "); _jspxForwarded = true; if (true) return;\n", node.location());
            else body.add("pageContext.include(" + variable + ", " + boolValue(node.attributes().getOrDefault("flush", "false"), node.location()) + ");\n", node.location());
        }

        private void bean(PageNode.Element node) {
            allowed(node, Set.of("id", "class", "type", "scope"));
            String id = required(node, "id");
            if (!SourceVersion.isIdentifier(id) || SourceVersion.isKeyword(id)) throw error(node.location(), "Invalid bean id");
            String implementation = node.attributes().get("class");
            String type = node.attributes().getOrDefault("type", implementation);
            if (type == null || !SourceVersion.isName(type) || (implementation != null && !SourceVersion.isName(implementation)))
                throw error(node.location(), "Bean requires a Java type");
            if (beans.putIfAbsent(id, type) != null) throw error(node.location(), "Duplicate bean id: " + id);
            int scope = scope(node.attributes().getOrDefault("scope", "page"), node.location());
            if (implementation != null && (scope == 3 || scope == 4))
                throw error(node.location(), "Shared-scope bean creation needs atomic lifecycle support; supply an existing typed bean instead");
            body.add(type + " " + id + " = (" + type + ") pageContext.getAttribute(" + JavaSource.quote(id) + ", " + scope + ");\nif (" + id + " == null) {\n", node.location());
            if (implementation == null) body.add("throw new jakarta.servlet.ServletException(\"Required bean is absent: " + id + "\");\n", node.location());
            else {
                classes.constructor(implementation, node.location());
                body.add(id + " = new " + implementation + "();\npageContext.setAttribute(" + JavaSource.quote(id) + ", " + id + ", " + scope + ");\n", node.location());
                emit(node.children());
            }
            body.add("}\n");
        }
        private String beanType(PageNode.Element node) {
            String name = required(node, "name");
            var type = beans.get(name);
            if (type == null) throw error(node.location(), "Bean must be declared before property access: " + name);
            return type;
        }
        private void getProperty(PageNode.Element node) {
            allowed(node, Set.of("name", "property"));
            String type = beanType(node);
            var accessor = classes.getter(type, required(node, "property"), node.location());
            body.add("out.print(((" + type + ") _jspxBean(pageContext, " + JavaSource.quote(node.attributes().get("name")) + "))." + accessor.name() + "());\n", node.location());
        }
        private void setProperty(PageNode.Element node) {
            allowed(node, Set.of("name", "property", "param", "value"));
            String property = required(node, "property");
            if (property.equals("*")) {
                if (node.attributes().containsKey("param") || node.attributes().containsKey("value"))
                    throw error(node.location(), "Wildcard setProperty accepts neither param nor value");
                for (String name : classes.writableProperties(beanType(node), node.location())) {
                    var attributes = new LinkedHashMap<>(node.attributes());
                    attributes.put("property", name);
                    setProperty(new PageNode.Element(node.name(), node.namespace(), attributes, List.of(), true, node.location()));
                }
                return;
            }
            var setter = classes.setter(beanType(node), property, node.location());
            String parameter = node.attributes().getOrDefault("param", property);
            String supplied = node.attributes().containsKey("value") ? value(node.attributes().get("value"), node.location(), false)
                    : "request." + (setter.type().endsWith("[]") ? "getParameterValues" : "getParameter")
                            + "(" + JavaSource.quote(parameter) + ")";
            if (node.attributes().containsKey("param") && node.attributes().containsKey("value"))
                throw error(node.location(), "setProperty cannot combine value and param");
            String temporary = "_jspxValue" + serial++;
            body.add("Object " + temporary + " = " + supplied + ";\n", node.location());
            if (!node.attributes().containsKey("value")) body.add("if (" + temporary + " != null && !\"\".equals(" + temporary + ")) {\n");
            needsFactory = true;
            body.add("((" + beanType(node) + ") _jspxBean(pageContext, " + JavaSource.quote(node.attributes().get("name")) + "))." + setter.name() + "(_jspxExpressionFactory.coerceToType("
                    + temporary + ", " + setter.type() + ".class));\n", node.location());
            if (!node.attributes().containsKey("value")) body.add("}\n");
        }

        private String expression(String code, SourceLocation at) {
            if (!startupElAllowed) throw error(at, "Build-time EL compilation requires peano-compiler; startup EL must be enabled explicitly for preview");
            var unquoted = new StringBuilder();
            char quote = 0;
            for (int i = 0; i < code.length(); i++) {
                char c = code.charAt(i);
                if (quote != 0) {
                    unquoted.append(' ');
                    if (c == '\\' && i + 1 < code.length()) { unquoted.append(' '); i++; }
                    else if (c == quote) quote = 0;
                } else if (c == '\'' || c == '"') { quote = c; unquoted.append(' '); }
                else unquoted.append(c);
            }
            if (unquoted.toString().matches("(?s).*\\b[\\p{L}_][\\p{L}\\p{N}_]*:[\\p{L}_][\\p{L}\\p{N}_]*\\s*\\(.*"))
                throw error(at, "Tag-library function bindings are not supported yet");
            int index = expressions.size(); expressions.add(code);
            return "_jspxExpression" + index + ".getValue(pageContext.getELContext())";
        }
        private String value(String raw, SourceLocation at, boolean xmlLiteral) {
            if (raw.startsWith("<%=") && raw.endsWith("%>")) {
                if (bool("scriptingInvalid", false)) throw error(at, "Scripting attributes are disabled");
                return "(" + raw.substring(3, raw.length() - 2) + ")";
            }
            var temporary = new PageNode.Text(raw, at);
            var parser = new PageNormalizer(sourceSyntaxes.getOrDefault(at.source(), Page.Syntax.STANDARD), new ParserOptions(bool("isELIgnored", false),
                    bool("deferredSyntaxAllowedAsLiteral", false)), Map.of());
            var pieces = parser.normalizeStructured(List.of(temporary));
            if (pieces.size() == 1 && pieces.getFirst() instanceof PageNode.Expression el) {
                if (el.deferred()) throw error(at, "Deferred action attributes require tag-handler support");
                return expression("${" + el.code() + "}", at);
            }
            var parts = new ArrayList<String>();
            for (var piece : pieces) {
                if (piece instanceof PageNode.Text text) parts.add(JavaSource.quote(xmlLiteral ? escapeAttribute(text.value()) : text.value()));
                else if (piece instanceof PageNode.Expression el) {
                    if (el.deferred()) throw error(at, "Deferred action attributes require tag-handler support");
                    parts.add("_jspxText(" + expression("${" + el.code() + "}", at) + ")");
                }
            }
            return parts.isEmpty() ? "\"\"" : String.join(" + ", parts);
        }

        private void write(String value, SourceLocation at) {
            for (int start = 0; start < value.length();) {
                int end = Math.min(value.length(), start + 8192);
                if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) end--;
                body.add("out.write(" + JavaSource.quote(value.substring(start, end)) + ");\n", at);
                start = end;
            }
        }
        private SourceLocation settingLocation(String name) { return settingLocations.getOrDefault(name, new SourceLocation(page.source(), 1, 1)); }
        private boolean bool(String name, boolean fallback) { return boolValue(settings.getOrDefault(name, String.valueOf(fallback)), settingLocation(name)); }
        private boolean boolValue(String value, SourceLocation at) {
            if (!value.equals("true") && !value.equals("false")) throw error(at, "Expected true or false: " + value);
            return Boolean.parseBoolean(value);
        }
        private String contentType() {
            String contentType = settings.getOrDefault("contentType", page.syntax() == Page.Syntax.XML ? "text/xml" : "text/html");
            if (SourceDecoder.contentCharset(contentType) == null) {
                String encoding = page.syntax() == Page.Syntax.XML ? "UTF-8"
                        : responseEncoding != null ? responseEncoding : settings.get("pageEncoding");
                if (encoding != null) contentType += ";charset=" + Charset.forName(encoding).name();
            }
            return contentType;
        }
        private int buffer() {
            String value = settings.getOrDefault("buffer", "8kb");
            if (value.equals("none")) {
                if (!bool("autoFlush", true)) throw error(settingLocation("buffer"), "Unbuffered pages require autoFlush");
                return 0;
            }
            try {
                if (!value.endsWith("kb")) throw new NumberFormatException();
                int capacity = Math.multiplyExact(Integer.parseInt(value.substring(0, value.length() - 2)), 1024);
                if (capacity <= 0) throw new NumberFormatException();
                return capacity;
            } catch (NumberFormatException | ArithmeticException failure) {
                throw error(settingLocation("buffer"), "Invalid page buffer: " + value);
            }
        }
        private int scope(String scope, SourceLocation at) {
            if (scope.equals("session") && !bool("session", true)) throw error(at, "Session-scoped beans require session participation");
            return switch (scope) { case "page" -> 1; case "request" -> 2; case "session" -> 3; case "application" -> 4;
                default -> throw error(at, "Invalid bean scope: " + scope); };
        }
        private String required(PageNode.Element node, String name) {
            var value = node.attributes().get(name);
            if (value == null) throw error(node.location(), node.name() + " requires " + name);
            return value;
        }
        private void allowed(PageNode.Element node, Set<String> attributes) {
            for (String name : node.attributes().keySet())
                if (!name.startsWith("xmlns") && !attributes.contains(name)) throw error(node.location(), "Unsupported action attribute: " + name);
        }
        private String escapeAttribute(String text) { return text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;"); }
        private boolean xmlSpace(char value) { return value == ' ' || value == '\t' || value == '\r' || value == '\n'; }
        private PageParseException error(SourceLocation location, String message) { return new PageParseException(location, message); }
    }
}

