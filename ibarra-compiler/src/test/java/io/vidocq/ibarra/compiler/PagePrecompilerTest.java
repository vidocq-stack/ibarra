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

import java.io.File;
import java.lang.module.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PagePrecompilerTest {
    @TempDir Path directory;
    private Path classes;
    private Path web;
    private final List<Path> extraModules = new ArrayList<>();

    @Test
    void deferredValuesRemainLazyAndTypedForSimpleAndClassicTags() throws Exception {
        prepareDeferredTags();
        Files.writeString(web.resolve("index.jsp"), deferredPrefix()
                + "<%request.setAttribute(\"later\",1);%><d:value value='#{requestScope.later}'/>|<d:classic value='42'/>"
                + "|<% pageContext.getELContext().getVariableMapper().setVariable(\"bound\", new demo.DeferredTag.Token(\"before\")); %><d:capture value='#{bound}'/>");
        precompile(); Path jar = directory.resolve("deferred-values.jar"); jar(classes, jar);
        assertEquals("V:java.lang.Integer:false:5|V:java.lang.Integer:true:42|V:java.lang.Object:false:before", render(jar, true));
    }

    @Test
    void deferredMethodsUseTypedCompiledLiteralAndVariableTargets() throws Exception {
        prepareDeferredTags();
        Files.writeString(web.resolve("index.jsp"), deferredPrefix()
                + "<d:method action='17'/>|<%pageContext.setAttribute(\"callback\",new demo.DeferredTag.Callback());%><d:method action='#{callback}'/>");
        precompile(); Path jar = directory.resolve("deferred-methods.jar"); jar(classes, jar);
        assertEquals("M:java.lang.Integer:true:17|M:java.lang.Integer:false:101", render(jar, true));
    }

    @Test
    void deferredMethodsSerializeAndRestoreLoadersOnFailuresAndConcurrentCalls() throws Exception {
        prepareDeferredTags();
        Files.writeString(web.resolve("index.jsp"), deferredPrefix()
                + "<%request.setAttribute(\"probe\",true);%><d:method action='17'/>|"
                + "<%pageContext.setAttribute(\"callback\",new demo.DeferredTag.Callback());%><d:method action='#{callback}'/>");
        precompile(); Path jar = directory.resolve("deferred-scope-serialization.jar"); jar(classes, jar);
        assertEquals("M:java.lang.Integer:true:17|M:java.lang.Integer:false:101", render(jar, true));
    }

    @Test
    void deferredLiteralClassesSupportLongStringsWithoutConstantPoolOverflow() throws Exception {
        prepareDeferredTags();
        String literal = "é😀".repeat(8_000);
        Files.writeString(web.resolve("index.jsp"), deferredPrefix() + "<%@ page pageEncoding='UTF-8' %><d:capture value='" + literal + "'/>");
        precompile(); Path jar = directory.resolve("deferred-long-literal.jar"); jar(classes, jar);
        assertEquals("V:java.lang.Object:true:" + literal, render(jar, true));
    }

    @Test
    void mixedDeferredAttributesDeferOnlyHashExpressions() throws Exception {
        prepareDeferredTags();
        Files.writeString(web.resolve("index.jsp"), deferredPrefix()
                + "<%request.setAttribute(\"later\",1);%><d:mixed value='${requestScope.later}'/>|<d:mixed value='#{requestScope.later}'/>"
                + "|<d:mixed value='literal'/>|<d:mixedMethod action='literal'/>");
        precompile(); Path jar = directory.resolve("mixed-deferred.jar"); jar(classes, jar);
        assertEquals("O:java.lang.Integer:1|V:java.lang.Integer:false:5|O:java.lang.String:literal|O:java.lang.String:literal", render(jar, true));
    }

    @Test
    void mixedLiteralsPreserveBackslashesAndNamedImmediateFunctionsKeepLocalBindings() throws Exception {
        prepareFunctions(); prepareDeferredTags();
        Files.writeString(web.resolve("part.jspx"), "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' xmlns:d='urn:deferred' version='4.0'><d:mixed><jsp:attribute name='value'><jsp:text xmlns:f='urn:functions'>${f:sum(2,3)}</jsp:text></jsp:attribute></d:mixed></jsp:root>");
        Files.writeString(web.resolve("index.jsp"), deferredPrefix() + "<d:mixed value='a\\b'/>|<%@ include file='part.jspx' %>");
        precompile(); Path jar = directory.resolve("mixed-literal-binding.jar"); jar(classes, jar);
        assertEquals("O:java.lang.String:a\\b|O:java.lang.Integer:5", render(jar, true));
    }

    @Test
    void deferredNamedTextAndEscapedLiteralsPreserveLiteralMetadata() throws Exception {
        prepareDeferredTags();
        Files.writeString(web.resolve("index.jsp"), deferredPrefix()
                + "<d:capture><jsp:attribute name='value'> Hello#{requestScope.answer} </jsp:attribute></d:capture>"
                + "|<d:capture value='\\#{literal}'/>");
        precompile(); Path jar = directory.resolve("deferred-named.jar"); jar(classes, jar);
        assertEquals("V:java.lang.Object:false:Hello42|V:java.lang.Object:true:#{literal}", render(jar, true));
        Files.writeString(web.resolve("index.jsp"), deferredPrefix() + "<%@ page deferredSyntaxAllowedAsLiteral='true' %><d:capture value='#{literal}'/>");
        precompile(); jar(classes, jar); assertEquals("V:java.lang.Object:true:#{literal}", render(jar, true));
    }

    @Test
    void deferredTldFunctionsCaptureNamedNamespacesAndCreationVariableBindings() throws Exception {
        prepareFunctions(); prepareDeferredTags();
        Files.createDirectories(web.resolve("outside"));
        Files.writeString(web.resolve("outside/functions.tld"), Files.readString(web.resolve("WEB-INF/functions.tld")).replace("int sum(int,int)", "int difference(int,int)"));
        Files.writeString(web.resolve("part.jspx"), "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' xmlns:d='urn:deferred' version='4.0'><d:capture><jsp:attribute name='value' xmlns:f='urn:jsptld:/outside/functions.tld'>#{f:sum(4,2)}:#{bound}</jsp:attribute></d:capture></jsp:root>");
        Files.writeString(web.resolve("index.jsp"), deferredPrefix() + "<%@ taglib prefix='f' uri='urn:functions' %>"
                + "<%request.setAttribute(\"later\",1);%><d:value value='#{f:sum(requestScope.later,2)}'/>|"
                + "<%pageContext.getELContext().getVariableMapper().setVariable(\"bound\",new demo.DeferredTag.Token(\"before\"));%><%@ include file='part.jspx' %>");
        precompile(); Path jar = directory.resolve("deferred-functions.jar"); jar(classes, jar);
        assertEquals("V:java.lang.Integer:false:7|V:java.lang.Object:false:2:before", render(jar, true));
    }

    @Test
    void literalExpressionProtocolsMatchInterpreterMetadataAndConversion() throws Exception {
        prepareDeferredTags();
        var factory = jakarta.el.ExpressionFactory.newInstance();
        var context = new jakarta.el.StandardELContext(factory);
        var value = factory.createValueExpression("42", Integer.class);
        var method = factory.createMethodExpression(context, "17", Integer.class, new Class<?>[]{String.class});
        String expected = "V:" + value.getExpectedType().getName() + ":" + value.isLiteralText() + ":" + value.getValue(context)
                + "|M:" + method.getMethodInfo(context).getReturnType().getName() + ":" + method.isLiteralText() + ":" + method.invoke(context, new Object[]{"x"});
        Files.writeString(web.resolve("index.jsp"), deferredPrefix() + "<d:value value='42'/>|<d:method action='17'/>");
        precompile(); Path jar = directory.resolve("deferred-literal-oracle.jar"); jar(classes, jar);
        assertEquals(expected, render(jar, true));
    }

    @Test
    void deferredTranslationRejectsWrongSyntaxVoidLiteralsAndInvalidCoercion() throws Exception {
        prepareDeferredTags();
        for (String tag : List.of("<d:value value='${1}'/>", "<d:method action='${callback}'/>",
                "<d:voidMethod action='literal'/>", "<d:method action='not-an-integer'/>",
                "<d:value value='#{1}${2}'/>", "<d:arrayMethod action=''/>",
                "<d:capture><jsp:attribute name='value'><d:capture value='x'/></jsp:attribute></d:capture>")) {
            Files.writeString(web.resolve("index.jsp"), deferredPrefix() + tag);
            assertThrows(PageParseException.class, this::precompile, tag);
        }
    }

    @Test
    void deferredValuesRetainWritableExpressionSemantics() throws Exception {
        prepareDeferredTags();
        Files.writeString(web.resolve("index.jsp"), deferredPrefix() + "<%request.setAttribute(\"write\",true);%><d:value value='#{requestScope.answer}'/>");
        precompile(); Path jar = directory.resolve("deferred-write.jar"); jar(classes, jar);
        assertEquals("V:java.lang.Integer:false:99", render(jar, true));
    }

    @Test
    void deferredSetterContractsSignatureVisibilityAndRequiredModuleReadsAreValidated() throws Exception {
        prepareDeferredTags();
        Path tld = web.resolve("WEB-INF/deferred.tld");
        String original = Files.readString(tld);
        Files.writeString(tld, original.replace("</taglib>", deferredTag("wrongSetter", "<attribute><name>action</name><deferred-value/></attribute>", false) + "</taglib>"));
        Files.writeString(web.resolve("index.jsp"), deferredPrefix() + "<d:wrongSetter action='#{requestScope.answer}'/>");
        assertThrows(PageParseException.class, this::precompile);
        Files.writeString(tld, original.replace("<type>java.lang.Integer</type>", "<type>demo.DeferredTag.Secret</type>"));
        Files.writeString(web.resolve("index.jsp"), deferredPrefix() + "<d:value value='#{requestScope.answer}'/>");
        assertThrows(PageParseException.class, this::precompile);
        Files.writeString(tld, original);
        Path info = directory.resolve("original/module-info.java");
        Files.writeString(info, "module demo.web { requires io.vidocq.ibarra.api; }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","25","--module-path",modulePath(),"-d",classes.toString(),info.toString()));
        var failure = assertThrows(IllegalArgumentException.class, this::precompile);
        assertTrue(failure.getMessage().contains("io.vidocq.peano.api"));
    }

    private String deferredPrefix() { return "<%@ page session='false' %><%@ taglib prefix='d' uri='urn:deferred' %>"; }
    private void prepareDeferredTags() throws Exception {
        prepare(false, true);
        Path source = directory.resolve("DeferredTag.java"), classic = directory.resolve("ClassicDeferredTag.java");
        Files.writeString(source, """
                package demo;
                public class DeferredTag extends jakarta.servlet.jsp.tagext.SimpleTagSupport {
                    private static class Secret {}
                    private Object value, action; private String valueKind, actionKind;
                    public void setValue(jakarta.el.ValueExpression expression) { value=expression; valueKind="V"; }
                    public void setValue(Object expression) { value=expression; valueKind="O"; }
                    public void setAction(jakarta.el.MethodExpression expression) { action=expression; actionKind="M"; }
                    public void setAction(Object expression) { action=expression; actionKind="O"; }
                    public void doTag() throws java.io.IOException { print((jakarta.servlet.jsp.PageContext)getJspContext(),value,action,valueKind,actionKind); }
                    public static void print(jakarta.servlet.jsp.PageContext page,Object value,Object action,String vk,String ak) throws java.io.IOException {
                        var context=page.getELContext(); page.getRequest().setAttribute("later",5);
                        context.getVariableMapper().setVariable("bound",new Token("after"));
                        if(value instanceof jakarta.el.ValueExpression expression) {
                            if(page.getRequest().getAttribute("write")!=null) {if(expression.isReadOnly(context))throw new AssertionError("Deferred value became read only");expression.setValue(context,99);}
                            page.getOut().write("V:"+expression.getExpectedType().getName()+":"+expression.isLiteralText()+":"+expression.getValue(context));
                        }
                        else if(value!=null) page.getOut().write(vk+":"+value.getClass().getName()+":"+value);
                        if(action instanceof jakarta.el.MethodExpression expression) {
                            if(page.getRequest().getAttribute("probe")!=null) probe(expression,context);
                            var loader=Thread.currentThread().getContextClassLoader();
                            page.getOut().write("M:"+expression.getMethodInfo(context).getReturnType().getName()+":"+expression.isLiteralText()+":"+expression.invoke(context,new Object[]{"x"}));
                            if(Thread.currentThread().getContextClassLoader()!=loader)throw new AssertionError("Method leaked loader");
                        }
                        else if(action!=null) page.getOut().write(ak+":"+action.getClass().getName()+":"+action);
                    }
                    public static class Token extends jakarta.el.ValueExpression {
                        private final Object value; public Token(Object value){this.value=value;}
                        public Object getValue(jakarta.el.ELContext c){return value;} public void setValue(jakarta.el.ELContext c,Object value){throw new jakarta.el.PropertyNotWritableException();}
                        public boolean isReadOnly(jakarta.el.ELContext c){return true;} public Class<?> getType(jakarta.el.ELContext c){return Object.class;}
                        public Class<?> getExpectedType(){return Object.class;} public String getExpressionString(){return "token";}
                        public boolean isLiteralText(){return true;} public boolean equals(Object other){return this==other;} public int hashCode(){return System.identityHashCode(this);}
                    }
                    public static class Callback extends jakarta.el.MethodExpression {
                        public jakarta.el.MethodInfo getMethodInfo(jakarta.el.ELContext c){return new jakarta.el.MethodInfo("callback",Integer.class,new Class<?>[]{String.class});}
                        public Object invoke(jakarta.el.ELContext c,Object[] arguments){if("fail".equals(arguments[0]))throw new jakarta.el.ELException("callback failure");return 100+((String)arguments[0]).length();}
                        public String getExpressionString(){return "callback";} public boolean isLiteralText(){return false;}
                        public boolean equals(Object other){return this==other;} public int hashCode(){return System.identityHashCode(this);}
                    }
                    private static void probe(jakarta.el.MethodExpression expression,jakarta.el.ELContext context) {
                        var loader=Thread.currentThread().getContextClassLoader();
                        try {
                            var bytes=new java.io.ByteArrayOutputStream();
                            try(var output=new java.io.ObjectOutputStream(bytes)){output.writeObject(expression);}
                            try(var input=new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))){
                                var restored=(jakarta.el.MethodExpression)input.readObject();
                                if(!expression.equals(restored)||expression.hashCode()!=restored.hashCode())throw new AssertionError("Method serialization identity");
                                restored.invoke(context,new Object[]{"x"});
                            }
                        } catch(Exception failure){throw new AssertionError(failure);}
                        try {expression.getMethodReference(context);} catch(UnsupportedOperationException expected){}
                        if(Thread.currentThread().getContextClassLoader()!=loader)throw new AssertionError("Metadata leaked loader");
                        if(!expression.isLiteralText()) {
                            try{expression.invoke(context,new Object[]{"fail"});throw new AssertionError("Method did not fail");}
                            catch(jakarta.el.ELException expected){if(!expected.getMessage().contains("callback failure"))throw expected;}
                            if(Thread.currentThread().getContextClassLoader()!=loader)throw new AssertionError("Failure leaked loader");
                        } else {
                            var factory=jakarta.el.ExpressionFactory.newInstance();
                            try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                                var calls=new java.util.ArrayList<java.util.concurrent.Future<?>>();
                                for(int i=0;i<8;i++)calls.add(executor.submit(()->{
                                    var thread=Thread.currentThread(); var original=thread.getContextClassLoader();
                                    var caller=new ClassLoader(original){}; thread.setContextClassLoader(caller);
                                    try {
                                        var own=new jakarta.el.StandardELContext(factory);expression.getMethodInfo(own);expression.invoke(own,new Object[]{"x"});
                                        if(thread.getContextClassLoader()!=caller)throw new AssertionError("Concurrent loader leaked");
                                    } finally {thread.setContextClassLoader(original);}
                                }));
                                for(var call:calls)call.get(5,java.util.concurrent.TimeUnit.SECONDS);
                            } catch(Exception failure){throw new AssertionError(failure);}
                        }
                    }
                }
                """);
        Files.writeString(classic, "package demo; public class ClassicDeferredTag extends jakarta.servlet.jsp.tagext.TagSupport { private jakarta.el.ValueExpression value; public void setValue(jakarta.el.ValueExpression v){value=v;} public int doStartTag() throws jakarta.servlet.jsp.JspException {try{DeferredTag.print(pageContext,value,null,\"V\",null);}catch(java.io.IOException e){throw new jakarta.servlet.jsp.JspException(e);}return SKIP_BODY;} }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","25","--module-path",modulePath()+File.pathSeparator+classes,
                "--add-modules","demo.web","--patch-module","demo.web="+source.getParent(),"-d",classes.toString(),source.toString(),classic.toString()));
        Files.createDirectories(web.resolve("WEB-INF"));
        String value = "<attribute><name>value</name><deferred-value><type>java.lang.Integer</type></deferred-value></attribute>";
        String action = "<attribute><name>action</name><deferred-method><method-signature>java.lang.Integer documented(java.lang.String)</method-signature></deferred-method></attribute>";
        Files.writeString(web.resolve("WEB-INF/deferred.tld"), "<taglib><uri>urn:deferred</uri>"
                + deferredTag("value", value, false) + deferredTag("classic", value, true)
                + deferredTag("capture", "<attribute><name>value</name><deferred-value/></attribute>", false)
                + deferredTag("method", action, false) + deferredTag("voidMethod", "<attribute><name>action</name><deferred-method/></attribute>", false)
                + deferredTag("mixed", value.replace("<deferred-value>", "<rtexprvalue>true</rtexprvalue><deferred-value>"), false)
                + deferredTag("mixedMethod", "<attribute><name>action</name><rtexprvalue>true</rtexprvalue><deferred-method/></attribute>", false)
                + deferredTag("arrayMethod", "<attribute><name>action</name><deferred-method><method-signature>java.lang.String[] array()</method-signature></deferred-method></attribute>", false) + "</taglib>");
    }
    private String deferredTag(String name,String attributes,boolean classic) {
        return "<tag><name>"+name+"</name><tag-class>demo."+(classic?"ClassicDeferredTag":"DeferredTag")+"</tag-class><body-content>empty</body-content>"+attributes+"</tag>";
    }
    @Test void tldFunctionsRenderThroughDirectCallsWithoutRuntimeExpressionCreation() throws Exception {
        prepareFunctions();
        Files.writeString(web.resolve("index.jsp"), functionPrefix()+"<%request.setAttribute(\"values\",new String[]{\"a\",\"b\"});%>${f:sum('7',2)}|${f:sum(f:sum(1,2),3)}|${f:length(values)}|${f:none()}|${f:over(3)}");
        precompile(); assertNull(System.getProperty("ibarra.function.initialized"));
        Path jar=directory.resolve("functions.jar"); jar(classes,jar);
        assertEquals("9|6|2||int",render(jar,true));
    }
    @Test void functionTranslationRejectsUnknownPrefixesSignaturesAndArities() throws Exception {
        prepareFunctions();
        for(String expression:List.of("${missing:sum(1,2)}","${f:unknown()}","${f:sum(1)}","${f:instance()}","${f:hidden()}","${f:wide('a')}")) {
            Files.writeString(web.resolve("index.jsp"),functionPrefix()+expression);
            assertThrows(PageParseException.class,this::precompile,expression);
        }
    }
    @Test void functionLibrariesCannotBypassValidatorRequirements() throws Exception {
        prepareFunctions();
        Path tld=web.resolve("WEB-INF/functions.tld");
        Files.writeString(tld,Files.readString(tld).replace("</taglib>","<validator><validator-class>demo.Validator</validator-class></validator></taglib>"));
        Files.writeString(web.resolve("index.jsp"),functionPrefix()+"${f:sum(1,2)}");
        assertThrows(PageParseException.class,this::precompile);
    }
    @Test void relativeFunctionBindingsCaptureEachIncludeDeclaration() throws Exception {
        prepareFunctions();
        Files.createDirectories(web.resolve("one")); Files.createDirectories(web.resolve("two"));
        String first=Files.readString(web.resolve("WEB-INF/functions.tld"));
        Files.writeString(web.resolve("one/lib.tld"),first); Files.writeString(web.resolve("two/lib.tld"),first.replace("int sum(int,int)","int difference(int,int)"));
        Files.writeString(web.resolve("one/bind.jspf"),"<%@ taglib prefix='f' uri='lib.tld' %>${f:sum(4,2)}");
        Files.writeString(web.resolve("two/bind.jspf"),"<%@ taglib prefix='f' uri='lib.tld' %>${f:sum(4,2)}");
        Files.writeString(web.resolve("index.jsp"),"<%@ page session='false' %><%@ include file='one/bind.jspf' %>|<%@ include file='two/bind.jspf' %>");
        precompile(); Path jar=directory.resolve("relative-functions.jar"); jar(classes,jar); assertEquals("6|2",render(jar,true));
    }
    @Test void xmlFunctionNamespacesRestoreTheirLexicalScope() throws Exception {
        prepareFunctions();
        String second=Files.readString(web.resolve("WEB-INF/functions.tld")).replace("urn:functions","urn:other").replace("int sum(int,int)","int difference(int,int)");
        Files.writeString(web.resolve("WEB-INF/other.tld"),second);
        Files.writeString(web.resolve("index.jsp"),"<%@ page session='false' %><%@ include file='functions.jspx' %>");
        Files.writeString(web.resolve("functions.jspx"),"<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' xmlns:f='urn:functions' version='4.0'><jsp:text>${f:sum(4,2)}|</jsp:text><jsp:text xmlns:f='urn:other'>${f:sum(4,2)}|</jsp:text><jsp:text>${f:sum(4,2)}</jsp:text></jsp:root>");
        precompile(); Path jar=directory.resolve("xml-functions.jar"); jar(classes,jar); assertEquals("6|2|6",render(jar,true));
    }
    @Test void directFunctionCallsEnforceSignatureTypeVisibility() throws Exception {
        prepareFunctions();
        Path tld=web.resolve("WEB-INF/functions.tld");
        Files.writeString(tld,Files.readString(tld).replace("</taglib>","<function><name>secret</name><function-class>demo.Functions</function-class><function-signature>int secret(demo.Functions.Secret)</function-signature></function></taglib>"));
        Files.writeString(web.resolve("index.jsp"),functionPrefix()+"${f:secret(null)}");
        assertThrows(PageCompilationException.class,this::precompile);
    }
    private String functionPrefix(){return "<%@ page session='false' %><%@ taglib prefix='f' uri='urn:functions' %>";}
    private void prepareFunctions() throws Exception {
        prepare(false,true);
        Path source=directory.resolve("Functions.java");
        Files.writeString(source,"""
                package demo; public class Functions {
                    private Functions(){} static {System.setProperty("ibarra.function.initialized","yes");}
                    public static int sum(int a,int b){return a+b;}
                    public static int difference(int a,int b){return a-b;}
                    public static int length(String[] values){return values.length;}
                    public static void none(){}
                    public static String over(int value){return "int";}
                    public static String over(String value){return "string";}
                    public int instance(){return 1;} private static int hidden(){return 1;}
                    public static int wide(String... values){return values.length;}
                    private static class Secret{} public static int secret(Secret value){return 1;}
                }
                """);
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","25","--module-path",
                modulePath()+File.pathSeparator+classes,"--add-modules","demo.web","--patch-module","demo.web="+source.getParent(),"-d",classes.toString(),source.toString()));
        Files.createDirectories(web.resolve("WEB-INF"));
        var tld=new StringBuilder("<taglib><uri>urn:functions</uri>");
        for(var entry:Map.of("sum","int sum(int,int)","length","int length(java.lang.String[])","none","void none()",
                "over","java.lang.String over(int)","instance","int instance()","hidden","int hidden()","wide","int wide(java.lang.String[])").entrySet())
            tld.append("<function><name>").append(entry.getKey()).append("</name><function-class>demo.Functions</function-class><function-signature>").append(entry.getValue()).append("</function-signature></function>");
        Files.writeString(web.resolve("WEB-INF/functions.tld"),tld.append("</taglib>").toString());
    }

    @Test void compiledElRendersFromAnApplicationJarWithoutRuntimeCompilers() throws Exception {
        prepare(false, true);
        Files.writeString(web.resolve("index.jsp"), "<%@ page session='false' %>${1+2}|${requestScope.answer}|${[1,2,3][1]}|${((x)->x+1)(4)}");
        precompile();
        Path jar = directory.resolve("compiled-el.jar"); jar(classes, jar);
        assertEquals("3|42|2|5", render(jar, true));
    }

    @Test void removedElClassesAreCleanedUpWithoutDeletingApplicationClasses() throws Exception {
        prepare(false, true);
        Files.writeString(web.resolve("index.jsp"), "${1+2}"); precompile();
        Set<String> first = classFiles();
        assertTrue(first.stream().anyMatch(name -> name.contains("$Expression")));
        Files.writeString(web.resolve("index.jsp"), "plain"); precompile();
        assertTrue(classFiles().stream().noneMatch(name -> name.contains("$Expression")));
        assertTrue(Files.exists(classes.resolve("demo/Bean.class")));
    }
    @Test void compiledElRequiresAnExplicitRuntimeApiReadBeforePublishing() throws Exception {
        prepare(false);
        byte[] original = Files.readAllBytes(classes.resolve("module-info.class"));
        Files.writeString(web.resolve("index.jsp"), "${1+2}");
        var failure = assertThrows(IllegalArgumentException.class, this::precompile);
        assertTrue(failure.getMessage().contains("io.vidocq.peano.api"));
        assertArrayEquals(original, Files.readAllBytes(classes.resolve("module-info.class")));
    }

    @Test
    void relativeTaglibLocationsResolveAgainstWebInfWebXml() throws Exception {
        prepare(false); listenerClass();
        Files.createDirectories(web.resolve("WEB-INF/tlds")); Files.createDirectories(web.resolve("tlds"));
        Files.writeString(web.resolve("WEB-INF/tlds/relative.tld"), "<taglib><listener><listener-class>demo.Listener$Nested</listener-class></listener></taglib>");
        Files.writeString(web.resolve("tlds/relative.tld"), "<taglib><listener><listener-class>demo.Bean</listener-class></listener></taglib>");
        Files.writeString(web.resolve("WEB-INF/web.xml"), "<web-app><jsp-config><taglib><taglib-uri>urn:relative</taglib-uri><taglib-location>tlds/relative.tld</taglib-location></taglib></jsp-config></web-app>");
        precompile(); assertEquals("demo.Listener$Nested", listenerNames());
    }

    @Test
    void explicitTaglibLocationsDiscoverTldsOutsideWebInf() throws Exception {
        prepare(false); listenerClass();
        Files.createDirectories(web.resolve("WEB-INF"));
        Files.writeString(web.resolve("startup.tld"), "<taglib><listener><listener-class>demo.Listener</listener-class></listener></taglib>");
        Files.writeString(web.resolve("WEB-INF/web.xml"), "<web-app><jsp-config><taglib><taglib-uri>urn:startup</taglib-uri><taglib-location>/startup.tld</taglib-location></taglib></jsp-config></web-app>");
        assertNotNull(precompile().indexClass()); assertEquals("demo.Listener", listenerNames());
    }

    @Test
    void directlyDeclaredLibrariesRegisterListenersAndIgnoreUnreferencedExternalTlds() throws Exception {
        prepare(false); listenerClass();
        Files.createDirectories(web.resolve("outside"));
        Files.writeString(web.resolve("outside/listeners.tld"), "<taglib><listener><listener-class>demo.Listener</listener-class></listener><listener><listener-class>demo.Listener$Nested</listener-class></listener></taglib>");
        Files.writeString(web.resolve("outside/ignored.tld"), "malformed unrelated external descriptor");
        Files.writeString(web.resolve("index.jsp"), "<%@ page session='false' %><%@ taglib prefix='l' uri='/outside/listeners.tld' %>page");
        precompile(); assertEquals("demo.Listenerdemo.Listener$Nested", listenerNames());
        Files.writeString(web.resolve("index.jsp"), "<%@ page session='false' %>page");
        precompile(); assertEquals("", listenerNames());
    }

    @Test
    void directJarReferencesFindListenersAndMissingResourcesPreservePublishedOutput() throws Exception {
        prepare(false); listenerClass();
        Path jar = web.resolve("external.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/taglib.tld"));
            out.write("<taglib><listener><listener-class>demo.Listener</listener-class></listener></taglib>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        Files.writeString(web.resolve("index.jsp"), "<%@ page session='false' %><%@ taglib prefix='l' uri='external.jar' %>page");
        precompile(); assertEquals("demo.Listener", listenerNames());
        byte[] descriptor = Files.readAllBytes(classes.resolve("module-info.class"));
        Files.delete(jar);
        var failure = assertThrows(PageParseException.class, this::precompile);
        assertEquals("/index.jsp", failure.location().source());
        assertArrayEquals(descriptor, Files.readAllBytes(classes.resolve("module-info.class")));
    }

    @Test
    void forcedXmlLibraryDeclarationsResolveRelativeToTheirFileAndRegisterListeners() throws Exception {
        prepareFunctions(); listenerClass();
        Files.createDirectories(web.resolve("outside"));
        String tld = Files.readString(web.resolve("WEB-INF/functions.tld")).replace("</taglib>", "<listener><listener-class>demo.Listener</listener-class></listener></taglib>");
        Files.writeString(web.resolve("outside/functions.tld"), tld);
        Files.writeString(web.resolve("outside/part.jspx"), "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' xmlns:f='urn:jsptld:functions.tld' version='4.0'><jsp:directive.page session='false'/><jsp:text>${f:sum(4,2)}</jsp:text></jsp:root>");
        Files.writeString(web.resolve("index.jsp"), "<%@ page session='false' %><%@ include file='outside/part.jspx' %>");
        precompile(); assertEquals("demo.Listener", listenerNames());
        Path jar = directory.resolve("direct-xml.jar"); jar(classes, jar); assertEquals("6", render(jar, true));
    }

    @Test
    void forcedXmlPathsBypassAliasesAndDoNotBindPlainNamespacesInOtherPages() throws Exception {
        prepareFunctions();
        Files.writeString(web.resolve("actual.tld"), Files.readString(web.resolve("WEB-INF/functions.tld")));
        Files.writeString(web.resolve("WEB-INF/other.tld"), Files.readString(web.resolve("WEB-INF/functions.tld")).replace("int sum(int,int)", "int difference(int,int)"));
        Files.writeString(web.resolve("WEB-INF/web.xml"), "<web-app><jsp-config><taglib><taglib-uri>/actual.tld</taglib-uri><taglib-location>/WEB-INF/other.tld</taglib-location></taglib></jsp-config></web-app>");
        Files.writeString(web.resolve("forced.jspx"), "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' xmlns:f='urn:jsptld:/actual.tld' version='4.0'><jsp:directive.page session='false'/><jsp:text>${f:sum(4,2)}</jsp:text></jsp:root>");
        Files.writeString(web.resolve("index.jsp"), "<%@ page session='false' %><%@ include file='forced.jspx' %>|<%@ taglib prefix='f' uri='/actual.tld' %>${f:sum(4,2)}");
        precompile(); Path jar = directory.resolve("forced-alias.jar"); jar(classes, jar); assertEquals("6|2", render(jar, true));
        Files.delete(web.resolve("WEB-INF/web.xml"));
        Files.writeString(web.resolve("index.jsp"), "<%@ page session='false' %><%@ include file='forced.jspx' %>|<%@ include file='plain.jspx' %>");
        Files.writeString(web.resolve("plain.jspx"), "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' version='4.0'><jsp:directive.page session='false'/><f:literal xmlns:f='/actual.tld'/></jsp:root>");
        precompile(); jar(classes, jar); assertEquals("6|<f:literal xmlns:f=\"/actual.tld\"/>", render(jar, true));
    }

    @Test
    void forcedDeclarationsOnNormalizedDirectivesRetainExtensionlessResourcesAndListeners() throws Exception {
        prepare(false); listenerClass();
        Files.writeString(web.resolve("metadata"), "<taglib><listener><listener-class>demo.Listener</listener-class></listener></taglib>");
        Files.writeString(web.resolve("index.jspx"), "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' version='4.0'><jsp:directive.page xmlns:f='urn:jsptld:/metadata' session='false'/><jsp:text>page</jsp:text></jsp:root>");
        precompile(); assertEquals("demo.Listener", listenerNames());
    }

    @Test
    void preludeAndCodaDirectReferencesUseImmutableSuppliedResourceSnapshots() throws Exception {
        prepareFunctions(); listenerClass();
        Files.writeString(web.resolve("index.jsp"), "<%@ page session='false' %>${f:sum(4,2)}|");
        String tld = Files.readString(web.resolve("WEB-INF/functions.tld")).replace("</taglib>", "<listener><listener-class>demo.Listener</listener-class></listener></taglib>");
        var group = new JspPropertyGroup(List.of("/index.jsp"), Map.of(), List.of("/first/prelude.jspf"), List.of("/second/coda.jspf"));
        var options = new GenerationOptions(List.of(group), Map.of(
                "/first/prelude.jspf", "<%@ taglib prefix='f' uri='library' %>",
                "/second/coda.jspf", "<%@ taglib prefix='f' uri='library' %>${f:sum(4,2)}"))
                .withByteSources(Map.of("/first/library", tld.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "/second/library", tld.replace("int sum(int,int)", "int difference(int,int)").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        new PagePrecompiler().precompile(web, classes, modulePaths(), "demo.generated", options);
        assertEquals("demo.Listener", listenerNames());
        Path jar = directory.resolve("snapshot-direct.jar"); jar(classes, jar); assertEquals("6|2", render(jar, true));
    }

    @Test
    void authoritativeElParsingDoesNotDiscoverTaglibTextInsideQuotedExpressions() throws Exception {
        prepare(false, true);
        Files.writeString(web.resolve("index.jsp"), "<%@ page session='false' %>${'<%@ taglib prefix=\"ghost\" uri=\"missing.tld\" %>'}");
        var group = new JspPropertyGroup(List.of("/index.jsp"), Map.of("el-ignored", "true"), List.of("/prelude.jspf"), List.of());
        var options = new GenerationOptions(List.of(group), Map.of("/prelude.jspf", "<%@ page isELIgnored='false' %>"));
        new PagePrecompiler().precompile(web, classes, modulePaths(), "demo.generated", options);
        Path jar = directory.resolve("quoted-taglib.jar"); jar(classes, jar);
        assertEquals("<%@ taglib prefix=\"ghost\" uri=\"missing.tld\" %>", render(jar, true));
    }

    @Test
    void standaloneGenerationResolvesDirectTldByteSnapshotsWithoutCatalogAliases() throws Exception {
        prepareFunctions();
        byte[] tld = Files.readAllBytes(web.resolve("WEB-INF/functions.tld"));
        var options = GenerationOptions.DEFAULT.withByteSources(Map.of("/outside/library", tld));
        var generated = new PageGenerator(List.of(classes)).generate("/outside/page.jsp",
                "<%@ page session='false' %><%@ taglib prefix='f' uri='library' %>${f:sum(2,3)}",
                "demo.generated.Standalone", options);
        assertTrue(generated.source().contains("demo.Functions.sum("));
        assertEquals(1, generated.auxiliaryClasses().size());
        assertNull(System.getProperty("ibarra.function.initialized"));
    }

    @Test
    void namedScalarAndFragmentAttributesPreserveLocalNamespaceBindingsAndTrimming() throws Exception {
        prepareFunctions();
        Path source = directory.resolve("Echo.java");
        Files.writeString(source, """
                package demo;
                public class Echo extends jakarta.servlet.jsp.tagext.SimpleTagSupport {
                    private String label; private jakarta.servlet.jsp.tagext.JspFragment piece;
                    public void setLabel(String value) { label = value; }
                    public void setPiece(jakarta.servlet.jsp.tagext.JspFragment value) { piece = value; }
                    public void doTag() throws jakarta.servlet.jsp.JspException, java.io.IOException {
                        getJspContext().getOut().write(label + "|");
                        var body = piece == null ? getJspBody() : piece; body.invoke(null); body.invoke(null);
                    }
                }
                """);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "25", "--module-path",
                modulePath() + File.pathSeparator + classes, "--add-modules", "demo.web", "--patch-module", "demo.web=" + source.getParent(), "-d", classes.toString(), source.toString()));
        Files.writeString(web.resolve("WEB-INF/echo.tld"), "<taglib><uri>urn:echo</uri><tag><name>echo</name><tag-class>demo.Echo</tag-class><body-content>scriptless</body-content><attribute><name>label</name><type>java.lang.String</type><rtexprvalue>true</rtexprvalue></attribute><attribute><name>piece</name><fragment>true</fragment></attribute></tag></taglib>");
        Files.createDirectories(web.resolve("outside"));
        Files.writeString(web.resolve("outside/sum.tld"), Files.readString(web.resolve("WEB-INF/functions.tld")));
        Files.writeString(web.resolve("outside/difference.tld"), Files.readString(web.resolve("WEB-INF/functions.tld")).replace("int sum(int,int)", "int difference(int,int)"));
        String body = "<t:echo><jsp:attribute name='label' xmlns:f='urn:jsptld:/outside/difference.tld'> ${f:sum(4,2)} </jsp:attribute>"
                + "<jsp:attribute name='piece' xmlns:f='urn:jsptld:/outside/difference.tld'><jsp:text xmlns:f='urn:jsptld:/outside/sum.tld'> ${f:sum(4,2)} </jsp:text></jsp:attribute></t:echo>";
        Files.writeString(web.resolve("index.jspx"), "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' xmlns:t='urn:echo' version='4.0'><jsp:directive.page session='false'/>" + body + "</jsp:root>");
        Files.writeString(web.resolve("index.jsp"), "<%@ page session='false' %><%@ include file='index.jspx' %>");
        precompile(); Path jar = directory.resolve("named-namespace.jar"); jar(classes, jar); assertEquals("2|66", render(jar, true));
        Files.writeString(web.resolve("index.jspx"), "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' xmlns:t='urn:echo' xmlns:f='urn:functions' version='4.0'><jsp:directive.page session='false'/>" + body + "<jsp:text>|${f:sum(4,2)}</jsp:text></jsp:root>");
        precompile(); jar(classes, jar); assertEquals("2|66|6", render(jar, true));
        String explicit = "<t:echo label='body'><jsp:body xmlns:f='urn:jsptld:/outside/difference.tld'>${f:sum(4,2)}</jsp:body></t:echo>";
        Files.writeString(web.resolve("index.jspx"), "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' xmlns:t='urn:echo' version='4.0'><jsp:directive.page session='false'/>" + explicit + "</jsp:root>");
        precompile(); jar(classes, jar); assertEquals("body|22", render(jar, true));
        Files.writeString(web.resolve("index.jspx"), "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' xmlns:t='urn:echo' xmlns:f='urn:functions' version='4.0'><jsp:directive.page session='false'/>" + explicit + "<jsp:text>|${f:sum(4,2)}</jsp:text></jsp:root>");
        precompile(); jar(classes, jar); assertEquals("body|22|6", render(jar, true));
    }

    @Test
    void explicitJarLocationsUseMetaInfTaglibAndMissingLocationsFailTranslation() throws Exception {
        prepare(false); listenerClass();
        Files.createDirectories(web.resolve("WEB-INF"));
        Path jar = web.resolve("WEB-INF/metadata.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/taglib.tld"));
            out.write("<taglib><listener><listener-class>demo.Listener</listener-class></listener></taglib>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        Files.writeString(web.resolve("WEB-INF/web.xml"), "<web-app><jsp-config><taglib><taglib-uri>urn:startup</taglib-uri><taglib-location>metadata.jar</taglib-location></taglib></jsp-config></web-app>");
        assertNotNull(precompile().indexClass()); assertEquals("demo.Listener", listenerNames());
        Files.delete(jar);
        assertThrows(PageParseException.class, this::precompile);
    }

    @Test
    void nestedListenerClassesRetainBinaryIdentityAndUseLegalConstructorSourceNames() throws Exception {
        prepare(false); listenerClass();
        Files.createDirectories(web.resolve("WEB-INF"));
        Files.writeString(web.resolve("WEB-INF/nested.tld"), "<taglib><listener><listener-class>demo.Listener$Nested</listener-class></listener></taglib>");
        precompile(); assertEquals("demo.Listener$Nested", listenerNames());
    }

    @Test
    void namedLibraryTldsAreDiscoveredOnlyInsideTheApplicationDependencyGraph() throws Exception {
        prepare(false);
        Path source = directory.resolve("library-source"); Path library = directory.resolve("library");
        Files.createDirectories(source.resolve("library")); Files.createDirectories(library.resolve("META-INF/tags"));
        Files.writeString(source.resolve("module-info.java"), "module tag.library { requires jakarta.servlet; exports library; }");
        Files.writeString(source.resolve("library/Startup.java"), "package library; public class Startup implements jakarta.servlet.ServletContextListener { public Startup() throws java.lang.Throwable { } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "25", "--module-path", modulePath(),
                "-d", library.toString(), source.resolve("module-info.java").toString(), source.resolve("library/Startup.java").toString()));
        Files.writeString(library.resolve("META-INF/tags/library.tld"), "<taglib><uri>urn:library</uri><listener><listener-class>library.Startup</listener-class></listener></taglib>");
        Path jar = directory.resolve("tag-library.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar)); var files = Files.walk(library)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new JarEntry(library.relativize(file).toString().replace('\\', '/'))); Files.copy(file, out); out.closeEntry();
            }
        }
        extraModules.add(jar);
        Path info = directory.resolve("original/module-info.java");
        Files.writeString(info, "module demo.web { requires io.vidocq.ibarra.api; requires tag.library; }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "25", "--module-path", modulePath(), "-d", classes.toString(), info.toString()));
        precompile(); assertEquals("library.Startup", listenerNames());
        Files.writeString(info, "module demo.web { requires io.vidocq.ibarra.api; }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "25", "--module-path", modulePath(), "-d", classes.toString(), info.toString()));
        assertNull(precompile().indexClass());
    }

    @Test
    void applicationMetaInfTldsProvideListenersWithoutWebSourcePages() throws Exception {
        prepare(false); listenerClass();
        Files.createDirectories(classes.resolve("META-INF/tags"));
        Files.writeString(classes.resolve("META-INF/tags/local.tld"), "<taglib><listener><listener-class>demo.Listener</listener-class></listener></taglib>");
        assertNotNull(precompile().indexClass()); assertEquals("demo.Listener", listenerNames());
    }

    @Test
    void listenerOnlyApplicationsGenerateAnIndexWithoutRunningListenerInitialization() throws Exception {
        prepare(false);
        listenerClass();
        Files.createDirectories(web.resolve("WEB-INF"));
        Files.writeString(web.resolve("WEB-INF/one.tld"), "<taglib><listener><listener-class>demo.Listener</listener-class></listener></taglib>");
        Files.writeString(web.resolve("WEB-INF/two.tld"), "<taglib><listener><listener-class>demo.Listener</listener-class></listener></taglib>");
        Files.createDirectories(web.resolve("WEB-INF/classes")); Files.createDirectories(web.resolve("WEB-INF/lib"));
        Files.writeString(web.resolve("WEB-INF/classes/ignored.tld"), "invalid XML excluded from TLD scanning");
        Files.writeString(web.resolve("WEB-INF/lib/ignored.tld"), "invalid XML excluded from loose TLD scanning");
        var original = classFiles();
        var result = precompile();
        assertNotNull(result.indexClass());
        assertTrue(result.pagePaths().isEmpty());
        assertEquals("demo.Listener", listenerNames());
        Files.delete(web.resolve("WEB-INF/one.tld")); Files.delete(web.resolve("WEB-INF/two.tld"));
        assertNull(precompile().indexClass());
        assertEquals(original, classFiles());
    }

    @Test
    void unsupportedListenerTypesFailBeforeReplacingAnExistingGeneratedCatalogue() throws Exception {
        prepare(false); Files.writeString(web.resolve("index.jsp"), "original");
        precompile();
        byte[] module = Files.readAllBytes(classes.resolve("module-info.class"));
        Files.createDirectories(web.resolve("WEB-INF"));
        Files.writeString(web.resolve("WEB-INF/wrong.tld"), "<taglib><listener><listener-class>demo.Bean</listener-class></listener></taglib>");
        var failure = assertThrows(PageParseException.class, this::precompile);
        assertTrue(failure.getMessage().contains("wrong.tld"));
        assertArrayEquals(module, Files.readAllBytes(classes.resolve("module-info.class")));
    }

    private void listenerClass() throws Exception {
        Path source = directory.resolve("listener-source/demo/Listener.java"); Files.createDirectories(source.getParent());
        Files.writeString(source, "package demo; public class Listener implements jakarta.servlet.ServletContextListener { static { if (Boolean.parseBoolean(\"true\")) throw new AssertionError(\"Listener initialized during metadata discovery\"); } public static class Nested implements jakarta.servlet.ServletContextListener { } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "25", "--module-path",
                modulePath() + File.pathSeparator + classes, "--patch-module", "demo.web=" + source.getParent().getParent(),
                "-d", classes.toString(), source.toString()));
    }

    private String listenerNames() throws Exception {
        Path harness = directory.resolve("listener-harness");
        String main = "package io.vidocq.ibarra.core.internal; public class ListenerMain { public static void main(String[] args) { var indexes = java.util.ServiceLoader.load(ModuleLayer.boot(), io.vidocq.ibarra.spi.PageIndex.class); for (var index : indexes) for (var listener : index.listeners()) System.out.print(listener.type().getName()); } }";
        new PageCompiler().compile(List.of(new GeneratedPage("io.vidocq.ibarra.core.internal.ListenerMain", main, Map.of())), harness,
                List.of("--module-path", modulePath(), "--add-modules", "io.vidocq.ibarra.core", "--patch-module", "io.vidocq.ibarra.core=" + harness.resolve(".ibarra-sources")));
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-Dpeano.reflection=deny",
                "--limit-modules", "io.vidocq.ibarra.core,demo.web", "--module-path", modulePath() + File.pathSeparator + classes,
                "--add-modules", "demo.web", "--patch-module", "io.vidocq.ibarra.core=" + harness,
                "-m", "io.vidocq.ibarra.core/io.vidocq.ibarra.core.internal.ListenerMain")
                .redirectErrorStream(true).redirectOutput(directory.resolve("listener.log").toFile()).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        var output = Files.readString(directory.resolve("listener.log")); assertEquals(0, process.exitValue(), output);
        return output;
    }

    @Test
    void packagedApplicationIsDiscoveredAndRendersWithoutCompilerAtRuntime() throws Exception {
        prepare(false);
        Files.writeString(web.resolve("index.jsp"), "A<jsp:useBean id='b' class='demo.Bean'/><jsp:getProperty name='b' property='value'/><%@ include file='part.jspf' %>");
        Files.writeString(web.resolve("part.jspf"), "<%@ page pageEncoding='UTF-8' %>é");
        var result = precompile();
        assertEquals(Set.of("/index.jsp"), result.pagePaths());
        var descriptor = ModuleFinder.of(classes).find("demo.web").orElseThrow().descriptor();
        assertTrue(descriptor.exports().isEmpty());
        assertEquals(1, descriptor.provides().size());
        Path jar = directory.resolve("application.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar)); var files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String name = classes.relativize(file).toString().replace('\\', '/');
                assertFalse(name.endsWith(".java"));
                assertFalse(name.startsWith("META-INF/services/"));
                output.putNextEntry(new JarEntry(name)); Files.copy(file, output); output.closeEntry();
            }
        }
        assertEquals("ABé", render(jar));
    }

    @Test
    void incrementalRunsRemoveOnlyOwnedClassesAndPreserveOtherServiceProviders() throws Exception {
        prepare(true);
        Files.writeString(web.resolve("old.jsp"), "old");
        precompile();
        var original = Files.readAllBytes(classes.resolve("demo/Bean.class"));
        Files.delete(web.resolve("old.jsp"));
        Files.writeString(web.resolve("new.jsp"), "new");
        var result = new PagePrecompiler().precompile(web, classes, modulePaths(), "demo.changed", GenerationOptions.DEFAULT);
        assertEquals(Set.of("/new.jsp"), result.pagePaths());
        assertArrayEquals(original, Files.readAllBytes(classes.resolve("demo/Bean.class")));
        assertFalse(Files.exists(classes.resolve("demo/generated")));
        var provided = ModuleFinder.of(classes).find("demo.web").orElseThrow().descriptor().provides().iterator().next();
        assertEquals(Set.of("demo.ManualIndex", "demo.changed.GeneratedPageIndex"), Set.copyOf(provided.providers()));
    }

    @Test
    void generationFailureKeepsThePreviousModuleAndPages() throws Exception {
        prepare(false); Files.writeString(web.resolve("index.jsp"), "good"); precompile();
        byte[] module = Files.readAllBytes(classes.resolve("module-info.class"));
        var before = classFiles();
        Files.writeString(web.resolve("index.jsp"), "${1 + }");
        assertThrows(PageParseException.class, this::precompile);
        assertArrayEquals(module, Files.readAllBytes(classes.resolve("module-info.class")));
        assertEquals(before, classFiles());
        assertThrows(IllegalArgumentException.class, () -> new PagePrecompiler().precompile(web, classes,
                modulePaths(), "demo.generated", GenerationOptions.DEFAULT.withStartupEl(true)));
    }

    @Test
    void removingAllPagesRemovesOnlyTheGeneratedProvider() throws Exception {
        prepare(true); Files.writeString(web.resolve("index.jsp"), "page"); precompile();
        Files.delete(web.resolve("index.jsp")); assertTrue(precompile().pagePaths().isEmpty());
        var descriptor = ModuleFinder.of(classes).find("demo.web").orElseThrow().descriptor();
        assertEquals(List.of("demo.ManualIndex"), descriptor.provides().iterator().next().providers());
        assertFalse(Files.exists(classes.resolve("demo/generated")));
    }

    @Test
    void missingModuleMetadataAndEscapingSymbolicResourcesAreRejected() throws Exception {
        prepare(false);
        Files.delete(classes.resolve("module-info.class"));
        assertThrows(IllegalArgumentException.class, this::precompile);
        prepare(false);
        Path outside = directory.resolve("outside.jsp"); Files.writeString(outside, "outside");
        Files.createSymbolicLink(web.resolve("escape.jsp"), outside);
        assertThrows(IllegalArgumentException.class, this::precompile);
    }

    @Test
    void invalidPagePathsFailBeforePublishingTheIndex() throws Exception {
        prepare(false); Files.writeString(web.resolve("bad#name.jsp"), "page");
        byte[] module = Files.readAllBytes(classes.resolve("module-info.class"));
        assertThrows(IllegalArgumentException.class, this::precompile);
        assertArrayEquals(module, Files.readAllBytes(classes.resolve("module-info.class")));
    }

    @Test
    void replacedOwnedClassesAreNeitherOverwrittenNorDeleted() throws Exception {
        prepare(false); Files.writeString(web.resolve("index.jsp"), "page"); precompile();
        Path generated;
        try (var files = Files.walk(classes.resolve("demo/generated"))) {
            generated = files.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
        byte[] replacement = "application-owned replacement".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(generated, replacement);
        assertThrows(IllegalArgumentException.class, this::precompile);
        Files.delete(web.resolve("index.jsp"));
        assertThrows(IllegalArgumentException.class, this::precompile);
        assertArrayEquals(replacement, Files.readAllBytes(generated));
    }

    @Test
    void existingModulePackageMetadataDropsRemovedGeneratedPackages() throws Exception {
        prepare(true); Files.writeString(web.resolve("index.jsp"), "page"); precompile();
        Path info = classes.resolve("module-info.class");
        var cf = java.lang.classfile.ClassFile.of();
        Files.write(info, cf.transformClass(cf.parse(info), java.lang.classfile.ClassTransform.endHandler(builder ->
                builder.with(java.lang.classfile.attribute.ModulePackagesAttribute.ofNames(
                        java.lang.constant.PackageDesc.of("demo"), java.lang.constant.PackageDesc.of("demo.generated"))))));
        Files.delete(web.resolve("index.jsp")); precompile();
        assertEquals(Set.of("demo"), ModuleFinder.of(classes).find("demo.web").orElseThrow().descriptor().packages());
    }

    @Test
    void missingOwnedDirectoriesDoNotPreventRemovingPages() throws Exception {
        prepare(false); Files.writeString(web.resolve("index.jsp"), "page"); precompile();
        try (var files = Files.walk(classes.resolve("demo/generated"))) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
        Files.delete(web.resolve("index.jsp"));
        assertTrue(precompile().pagePaths().isEmpty());
        assertTrue(ModuleFinder.of(classes).find("demo.web").orElseThrow().descriptor().provides().isEmpty());
    }

    @Test
    void missingOwnedDirectoriesCanBeRebuiltWithoutChangingApplicationMetadata() throws Exception {
        prepare(true); Files.writeString(web.resolve("index.jsp"), "page"); precompile();
        try (var files = Files.walk(classes.resolve("demo/generated"))) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
        assertEquals(Set.of("/index.jsp"), precompile().pagePaths());
        assertTrue(Files.isRegularFile(classes.resolve("demo/generated/GeneratedPageIndex.class")));
    }

    @Test
    void defaultGeneratedPackageBelongsToTheApplicationModule() throws Exception {
        prepare(false); Files.writeString(web.resolve("index.jsp"), "page");
        var result = new PagePrecompiler().precompile(web, classes, modulePaths(), null, GenerationOptions.DEFAULT);
        assertEquals("demo.web.ibarra_generated.GeneratedPageIndex", result.indexClass());
    }

    private PagePrecompiler.Result precompile() throws Exception {
        return new PagePrecompiler().precompile(web, classes, modulePaths(), "demo.generated", GenerationOptions.DEFAULT);
    }

    private void prepare(boolean manual) throws Exception {
        prepare(manual, false);
    }
    private void prepare(boolean manual, boolean el) throws Exception {
        classes = directory.resolve("classes"); web = directory.resolve("web");
        Files.createDirectories(classes); Files.createDirectories(web);
        Path sources = directory.resolve("original"); Files.createDirectories(sources.resolve("demo"));
        Files.writeString(sources.resolve("module-info.java"), "module demo.web { requires io.vidocq.ibarra.api;"
                + (el ? " requires io.vidocq.peano.api;" : "")
                + (manual ? " provides io.vidocq.ibarra.spi.PageIndex with demo.ManualIndex;" : "") + " }");
        Files.writeString(sources.resolve("demo/Bean.java"), "package demo; public class Bean { public String getValue() { return \"B\"; } }");
        var inputs = new ArrayList<String>(List.of("--release", "25", "-proc:none", "--module-path", modulePath(), "-d", classes.toString(),
                sources.resolve("module-info.java").toString(), sources.resolve("demo/Bean.java").toString()));
        if (manual) {
            Path index = sources.resolve("demo/ManualIndex.java");
            Files.writeString(index, "package demo; public class ManualIndex implements io.vidocq.ibarra.spi.PageIndex { public java.util.List<io.vidocq.ibarra.spi.PageDescriptor> pages() { return java.util.List.of(); } }");
            inputs.add(index.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, inputs.toArray(String[]::new)));
    }

    private Set<String> classFiles() throws Exception {
        try (var files = Files.walk(classes)) {
            return files.filter(Files::isRegularFile).map(classes::relativize).map(Path::toString).collect(java.util.stream.Collectors.toSet());
        }
    }
    private void jar(Path output, Path target) throws Exception {
        try (var stream = new JarOutputStream(Files.newOutputStream(target)); var files = Files.walk(output)) {
            for (var file : files.filter(Files::isRegularFile).sorted().toList()) {
                stream.putNextEntry(new JarEntry(output.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, stream); stream.closeEntry();
            }
        }
    }

    private List<Path> modulePaths() { return Arrays.stream(modulePath().split(java.util.regex.Pattern.quote(File.pathSeparator))).map(Path::of).toList(); }
    private String modulePath() {
        var names = Set.of("io.vidocq.ibarra.api", "io.vidocq.ibarra.core", "jakarta.servlet", "jakarta.servlet.jsp", "jakarta.el", "io.vidocq.peano.api", "io.vidocq.peano.core");
        String available = System.getProperty("jdk.module.path", "") + File.pathSeparator + System.getProperty("surefire.test.class.path", "");
        var paths = Arrays.stream(available.split(java.util.regex.Pattern.quote(File.pathSeparator)))
                .filter(path -> !path.isEmpty()).map(Path::of)
                .filter(path -> path.toString().endsWith(".jar") || Files.isRegularFile(path.resolve("module-info.class")))
                .distinct().toArray(Path[]::new);
        String base = ModuleFinder.of(paths).findAll().stream().filter(module -> names.contains(module.descriptor().name()))
                .map(module -> Path.of(module.location().orElseThrow()).toString()).collect(java.util.stream.Collectors.joining(File.pathSeparator));
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(base), extraModules.stream().map(Path::toString))
                .collect(java.util.stream.Collectors.joining(File.pathSeparator));
    }

    private String render(Path jar) throws Exception {
        return render(jar, false);
    }
    private String render(Path jar, boolean el) throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (!Files.isDirectory(root.resolve("ibarra-core"))) root = root.getParent();
        String doubles = Files.readString(root.resolve("ibarra-core/src/test/java/io/vidocq/ibarra/core/internal/ServletDoubles.java"));
        String main = """
                package io.vidocq.ibarra.core.internal;
                import jakarta.servlet.*;
                import jakarta.servlet.jsp.*;
                public class PackagedMain {
                    public static void main(String[] args) throws Exception {
                        if (ModuleLayer.boot().findModule("io.vidocq.ibarra.compiler").isPresent()
                                || ModuleLayer.boot().findModule("io.vidocq.peano.compiler").isPresent()
                                || ModuleLayer.boot().findModule("java.compiler").isPresent()) throw new AssertionError("Compiler resolved at runtime");
                        %s
                        var catalogue = PrecompiledPages.discover(ModuleLayer.boot());
                        var descriptor = catalogue.find("/index.jsp");
                        if (descriptor == null) throw new AssertionError("No precompiled page");
                        var page = descriptor.createPage();
                        var context = new ServletDoubles.Context();
                        ServletConfig config = new ServletConfig() {
                            public String getServletName() { return "index"; }
                            public ServletContext getServletContext() { return context; }
                            public String getInitParameter(String name) { return null; }
                            public java.util.Enumeration<String> getInitParameterNames() { return java.util.Collections.emptyEnumeration(); }
                        };
                        page.init(config);
                        var response = new ServletDoubles.Response();
                        var request = new ServletDoubles.Request(); request.setAttribute("answer", 42);
                        page.service(request, response);
                        System.out.print("RESULT=" + java.util.Base64.getEncoder().encodeToString(response.output.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    }
                }
                """.formatted(el ? noParsingFactory() : "JspFactory.setDefaultFactory(new IbarraJspFactory());");
        Path harness = directory.resolve("harness");
        new PageCompiler().compile(List.of(new GeneratedPage("io.vidocq.ibarra.core.internal.PackagedMain", main, Map.of()),
                new GeneratedPage("io.vidocq.ibarra.core.internal.ServletDoubles", doubles, Map.of())), harness,
                List.of("--module-path", modulePath(), "--add-modules", "io.vidocq.ibarra.core",
                        "--patch-module", "io.vidocq.ibarra.core=" + harness.resolve(".ibarra-sources")));
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "--limit-modules", "io.vidocq.ibarra.core,demo.web" + (el ? ",io.vidocq.peano.core" : ""),
                "-Dpeano.reflection=deny",
                "--module-path", modulePath() + File.pathSeparator + jar, "--add-modules", "demo.web" + (el ? ",io.vidocq.peano.core" : ""),
                "--patch-module", "io.vidocq.ibarra.core=" + harness,
                "-m", "io.vidocq.ibarra.core/io.vidocq.ibarra.core.internal.PackagedMain")
                .redirectErrorStream(true).redirectOutput(directory.resolve("render.log").toFile()).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        String output = Files.readString(directory.resolve("render.log"));
        assertEquals(0, process.exitValue(), output);
        return new String(Base64.getDecoder().decode(output.substring(output.indexOf("RESULT=") + 7).trim()), java.nio.charset.StandardCharsets.UTF_8);
    }
    private String noParsingFactory() {
        return """
                var delegate = jakarta.el.ExpressionFactory.newInstance();
                var application = new IbarraApplicationContext(new jakarta.el.ExpressionFactory() {
                    public jakarta.el.ValueExpression createValueExpression(jakarta.el.ELContext context, String text, Class<?> type) { throw new AssertionError("Runtime parsing: " + text); }
                    public jakarta.el.ValueExpression createValueExpression(Object value, Class<?> type) { throw new AssertionError("Runtime expression creation"); }
                    public jakarta.el.MethodExpression createMethodExpression(jakarta.el.ELContext context, String text, Class<?> type, Class<?>[] parameters) { throw new AssertionError("Runtime method parsing"); }
                    public jakarta.el.ELResolver getStreamELResolver() { return delegate.getStreamELResolver(); }
                    public <T> T coerceToType(Object value, Class<T> type) { return delegate.coerceToType(value, type); }
                });
                JspFactory.setDefaultFactory(new JspFactory() {
                    public PageContext getPageContext(Servlet servlet, ServletRequest request, ServletResponse response, String error, boolean session, int buffer, boolean autoFlush) {
                        var context = new IbarraPageContext(application);
                        try { context.initialize(servlet, request, response, error, session, buffer, autoFlush); }
                        catch(java.io.IOException failure) { throw new IllegalStateException(failure); }
                        return context;
                    }
                    public void releasePageContext(PageContext context) { context.release(); }
                    public jakarta.servlet.jsp.JspApplicationContext getJspApplicationContext(ServletContext context) { return application; }
                    public jakarta.servlet.jsp.JspEngineInfo getEngineInfo() { return new IbarraJspFactory().getEngineInfo(); }
                });
                """;
    }
}
