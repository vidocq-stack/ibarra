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
import java.lang.module.ModuleFinder;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TagGenerationTest {
    @TempDir Path directory;
    @Test void simpleAttributesAndRepeatedFragmentEvaluationRenderWithoutEl() throws Exception {
        assertEquals("[hello:bodybody]", render("<x:echo label='hello' count='2'>body</x:echo>"));
    }
    @Test void namedStaticAttributesTrimOnlyTemplateWhitespaceAndAllowQualifiedNames() throws Exception {
        assertEquals("[hello:body]", render("<x:echo>\n<jsp:attribute name='x:label'> hello </jsp:attribute>\n<jsp:body>body</jsp:body>\n</x:echo>"));
        assertEquals("[ hello : ]", render("<x:echo><jsp:attribute name='label' trim='false' omit='true'> hello </jsp:attribute><jsp:body> </jsp:body></x:echo>"));
        assertEquals("[:]|[\u2003: ]", render("<x:echo><jsp:attribute name='label'/></x:echo>|<x:echo><jsp:attribute name='label'>\u2003</jsp:attribute><jsp:body> </jsp:body></x:echo>"));
        assertEquals("[hello:]", render("<x:echo><jsp:attribute name='label'> <jsp:text> </jsp:text><jsp:text> hello </jsp:text><jsp:text> </jsp:text> </jsp:attribute></x:echo>"));
    }
    @Test void runtimeNamedAttributesEvaluateOnceAndPreserveProducedWhitespace() throws Exception {
        assertEquals("[ space :bodybody]|8|a= space ;", render("<x:runtimeText><jsp:attribute name='label'> <x:spaced/> </jsp:attribute><jsp:body>body</jsp:body></x:runtimeText>|<x:runtimeCount><jsp:attribute name='count'><%=8%></jsp:attribute></x:runtimeCount>|<x:dynamic><jsp:attribute name='a'><x:spaced/></jsp:attribute></x:dynamic>"));
    }
    @Test void fragmentAttributesAreLazyRepeatableAndAvailableToClassicAndSimpleHandlers() throws Exception {
        assertEquals("<P>P!|<classic>classic", render("<x:fragment><jsp:attribute name='piece'> <x:fragmentParent/> </jsp:attribute><jsp:body>!</jsp:body></x:fragment>|<x:classicFragment><jsp:attribute name='piece'>classic</jsp:attribute></x:classicFragment>"));
    }
    @Test void explicitBodiesRetainClassicIterationAndBuffering() throws Exception {
        assertEquals("aaR[ABC]", render("<x:repeat count='2'><jsp:body>a</jsp:body></x:repeat><x:buffer><jsp:body>abc</jsp:body></x:buffer>"));
    }
    @Test void namedAttributeFailuresRestoreWritersAndSkipPages() throws Exception {
        assertEquals("before", render("before<x:fragment><jsp:attribute name='piece'><x:simpleSkip/></jsp:attribute></x:fragment>after"));
        assertEquals("CFRtail", render("<x:guard><x:runtimeText><jsp:attribute name='label'><x:fail/></jsp:attribute></x:runtimeText></x:guard>tail"));
        assertEquals("R", render("<x:namedGuard><jsp:attribute name='count'><x:fail/></jsp:attribute></x:namedGuard>", true));
    }
    @Test void namedAttributeEnvelopeRejectsDuplicatesOrderingAndInvalidContent() throws Exception {
        prepare();
        for (String source : List.of(
                "<x:echo label='a'><jsp:attribute name='label'>b</jsp:attribute></x:echo>",
                "<x:echo><jsp:attribute name='label'>a</jsp:attribute><jsp:attribute name='label'>b</jsp:attribute></x:echo>",
                "<x:echo><jsp:attribute name='label'>a</jsp:attribute>body</x:echo>",
                "<x:echo label='a'><jsp:body/><jsp:body/></x:echo>",
                "<x:echo><jsp:body/><jsp:attribute name='label'>a</jsp:attribute></x:echo>",
                "<x:echo><jsp:attribute name='label'><x:spaced/></jsp:attribute></x:echo>",
                "<x:echo><jsp:attribute name='y:label'>a</jsp:attribute></x:echo>",
                "<x:fragment piece='text'/>",
                "<x:fragment><jsp:attribute name='piece'><%=1%></jsp:attribute></x:fragment>",
                "<x:fragment><jsp:attribute name='piece' trim='maybe'/></x:fragment>",
                "<x:fail><jsp:body>body</jsp:body></x:fail>")) {
            assertThrows(PageParseException.class, () -> generator().generate("/tags.jsp", prefix()+source,
                    "fixture.GeneratedPage", options()), source);
        }
    }
    @Test void jspStringConversionsAcceptEmptyNumbersAndCharacterPrefixes() throws Exception {
        assertEquals("0|0|A|A|0", render("<x:countOnly count=''/>|<x:runtimeCount><jsp:attribute name='count'/></x:runtimeCount>|<x:character value='ABC'/>|<x:character><jsp:attribute name='value'>ABC</jsp:attribute></x:character>|<x:character value=''/>").replace("\u0000", "0"));
    }
    @Test void staticXmlNamedAttributesRejectActionsAndActiveExpressions() throws Exception {
        prepare();
        for (String content : List.of("<x:spaced/>", "<b title='${1}'/>", "${1}")) {
            String xml = "<jsp:root xmlns:jsp='http://java.sun.com/JSP/Page' xmlns:x='urn:test' version='4.0'><x:echo><jsp:attribute name='label'>" + content + "</jsp:attribute></x:echo></jsp:root>";
            assertThrows(PageParseException.class, () -> generator().generate("/tags.jspx", xml,
                    "fixture.GeneratedPage", options()), content);
        }
    }
    @Test void classicIterationAndBufferedBodiesRestoreTheEnclosingWriter() throws Exception {
        assertEquals("aaaRZ[ABC]Z", render("<x:repeat count='3'>a</x:repeat>Z<x:buffer>abc</x:buffer>Z"));
        assertEquals("Z", render("<x:buffer/>Z"));
    }
    @Test void catchFinallyAndReleaseRunWithTheRestoredWriter() throws Exception {
        assertEquals("CFRtail", render("<x:guard><x:fail/></x:guard>tail"));
    }
    @Test void mixedNestingUsesTagAdaptersAndFragmentWriterTargets() throws Exception {
        assertEquals("<P>", render("<x:capture><x:parent/></x:capture>"));
    }
    @Test void skipPageStopsRenderingWithoutInvokingErrorHandling() throws Exception {
        assertEquals("beforeR", render("before<x:skip/>after"));
        assertEquals("before", render("before<x:simpleSkip/>after"));
        assertEquals("beforeCFR", render("before<x:guard><x:skip/></x:guard>after"));
        assertEquals("beforeCFR", render("before<x:guard><x:simpleSkip/></x:guard>after"));
    }
    @Test void bodyContentSetterFailuresBypassCatchFinallyButStillRelease() throws Exception {
        assertEquals("R", render("<x:setterFailure>body</x:setterFailure>", true));
    }
    @Test void staticValuesUseSetterTypesAndRuntimeValuesUseObjectDefaults() throws Exception {
        assertEquals("8|42", render("<x:countOnly count='08'/>|<x:objectValue value='<%=42%>'/>"));
        assertEquals("Oliteral",render("<x:overload value='literal'/>"));
    }
    @Test void ordinaryAttributesBeginningWithXmlnsAreNotNamespaceDeclarations() throws Exception {
        assertEquals("[name:body]", render("<x:xmlnsLabel xmlnsLabel='name'>body</x:xmlnsLabel>"));
    }
    @Test void aNormalSkipPageReturnDoesNotInvokeTheReturningTagsOwnCatch() throws Exception {
        assertEquals("beforeFR", render("before<x:ownSkip/>after"));
    }
    @Test void relativeTaglibBindingsRetainTheirDeclarationOriginAcrossIncludes() throws Exception {
        var defaults=options(); var first=defaults.tagLibraries().get("urn:test");
        var tags=new HashMap<>(first.tags()); var echo=tags.get("echo");
        tags.put("echo",new TagDescriptor("echo","fixture.Tags$Second",echo.bodyContent(),echo.attributes(),false,false));
        var second=new TagLibraryDescriptor("/two/lib.tld",null,List.of(),tags,false);
        var libraries=new HashMap<>(defaults.tagLibraries()); libraries.put("/one/lib.tld",first); libraries.put("/two/lib.tld",second);
        var options=new GenerationOptions(List.of(),Map.of(
                "/one/bind.jspf","<%@ taglib prefix='q' uri='lib.tld' %><q:echo label='a'>body</q:echo>",
                "/two/bind.jspf","<%@ taglib prefix='q' uri='lib.tld' %><q:echo label='b'/>"),false,Map.of(),libraries);
        assertEquals("[a:body]SECOND",render("<%@ include file='one/bind.jspf' %><%@ include file='two/bind.jspf' %>",false,options));
    }
    @Test void dynamicAttributesUseDirectInterfaceCalls() throws Exception {
        assertEquals("a=one;b=42;",render("<x:dynamic a='one' b='<%=42%>'/>"));
    }
    @Test void bufferedIterationsRetainContentUntilTheHandlerClearsIt() throws Exception {
        assertEquals("[ia][iaa]",render("<x:bufferedRepeat>a</x:bufferedRepeat>"));
    }
    @Test void unsupportedOrMissingMetadataFailsDuringTranslation() throws Exception {
        prepare();
        for (String source : List.of("<x:echo/>", "<x:echo label='a' count='bad'/>", "<x:echo label='a' unknown='b'/>",
                "<x:echo label='a'><% out.print(1); %></x:echo>", "<x:missing/>")) {
            assertThrows(PageParseException.class, () -> generator().generate("/tags.jsp", prefix()+source,
                    "fixture.GeneratedPage", options()));
        }
    }
    private String prefix() { return "<%@ page session='false' %><%@ taglib prefix='x' uri='urn:test' %>"; }
    private GenerationOptions options() {
        String tld = """
                <taglib><uri>urn:test</uri>
                <tag><name>echo</name><tag-class>fixture.Tags$Echo</tag-class><body-content>scriptless</body-content>
                  <attribute><name>label</name><required>true</required></attribute><attribute><name>count</name><type>int</type></attribute></tag>
                <tag><name>repeat</name><tag-class>fixture.Tags$Repeat</tag-class><body-content>JSP</body-content>
                  <attribute><name>count</name><type>int</type><required>true</required></attribute></tag>
                <tag><name>buffer</name><tag-class>fixture.Tags$Buffer</tag-class><body-content>scriptless</body-content></tag>
                <tag><name>guard</name><tag-class>fixture.Tags$Guard</tag-class><body-content>scriptless</body-content></tag>
                <tag><name>setterFailure</name><tag-class>fixture.Tags$SetterFailure</tag-class><body-content>scriptless</body-content></tag>
                <tag><name>fail</name><tag-class>fixture.Tags$Fail</tag-class><body-content>empty</body-content></tag>
                <tag><name>capture</name><tag-class>fixture.Tags$Capture</tag-class><body-content>scriptless</body-content></tag>
                <tag><name>parent</name><tag-class>fixture.Tags$Parent</tag-class><body-content>empty</body-content></tag>
                <tag><name>skip</name><tag-class>fixture.Tags$Skip</tag-class><body-content>empty</body-content></tag>
                <tag><name>simpleSkip</name><tag-class>fixture.Tags$SimpleSkip</tag-class><body-content>empty</body-content></tag>
                <tag><name>ownSkip</name><tag-class>fixture.Tags$OwnSkip</tag-class><body-content>empty</body-content></tag>
                <tag><name>countOnly</name><tag-class>fixture.Tags$CountOnly</tag-class><body-content>empty</body-content>
                  <attribute><name>count</name><type>java.lang.String</type></attribute></tag>
                <tag><name>objectValue</name><tag-class>fixture.Tags$ObjectValue</tag-class><body-content>empty</body-content>
                  <attribute><name>value</name><rtexprvalue>true</rtexprvalue></attribute></tag>
                <tag><name>overload</name><tag-class>fixture.Tags$Overload</tag-class><body-content>empty</body-content>
                  <attribute><name>value</name><rtexprvalue>true</rtexprvalue></attribute></tag>
                <tag><name>xmlnsLabel</name><tag-class>fixture.Tags$Echo</tag-class><body-content>scriptless</body-content>
                  <attribute><name>xmlnsLabel</name><required>true</required></attribute></tag>
                <tag><name>dynamic</name><tag-class>fixture.Tags$Dynamic</tag-class><body-content>empty</body-content><dynamic-attributes>true</dynamic-attributes></tag>
                <tag><name>bufferedRepeat</name><tag-class>fixture.Tags$BufferedRepeat</tag-class><body-content>scriptless</body-content></tag>
                <tag><name>runtimeText</name><tag-class>fixture.Tags$RuntimeText</tag-class><body-content>scriptless</body-content>
                  <attribute><name>label</name><type>java.lang.String</type><rtexprvalue>true</rtexprvalue><required>true</required></attribute></tag>
                <tag><name>runtimeCount</name><tag-class>fixture.Tags$CountOnly</tag-class><body-content>empty</body-content>
                  <attribute><name>count</name><type>int</type><rtexprvalue>true</rtexprvalue></attribute></tag>
                <tag><name>spaced</name><tag-class>fixture.Tags$Spaced</tag-class><body-content>empty</body-content></tag>
                <tag><name>fragment</name><tag-class>fixture.Tags$Fragment</tag-class><body-content>scriptless</body-content>
                  <attribute><name>piece</name><fragment>true</fragment><required>true</required></attribute></tag>
                <tag><name>classicFragment</name><tag-class>fixture.Tags$ClassicFragment</tag-class><body-content>empty</body-content>
                  <attribute><name>piece</name><fragment>true</fragment><required>true</required></attribute></tag>
                <tag><name>fragmentParent</name><tag-class>fixture.Tags$FragmentParent</tag-class><body-content>empty</body-content></tag>
                <tag><name>character</name><tag-class>fixture.Tags$CharacterValue</tag-class><body-content>empty</body-content>
                  <attribute><name>value</name><type>char</type><rtexprvalue>true</rtexprvalue></attribute></tag>
                <tag><name>namedGuard</name><tag-class>fixture.Tags$NamedGuard</tag-class><body-content>empty</body-content>
                  <attribute><name>count</name><type>int</type><rtexprvalue>true</rtexprvalue></attribute></tag>
                </taglib>
                """;
        var library = new TldParser().parse("/WEB-INF/tags.tld", tld.getBytes(StandardCharsets.UTF_8));
        return GenerationOptions.DEFAULT.withTagLibraries(Map.of("urn:test", library));
    }
    private PageGenerator generator() {
        var paths = new ArrayList<Path>(); paths.add(directory);
        for (String path : modulePath().split(java.util.regex.Pattern.quote(File.pathSeparator))) paths.add(Path.of(path));
        return new PageGenerator(paths);
    }
    private String render(String source) throws Exception {
        return render(source, false);
    }
    private String render(String source, boolean expectFailure) throws Exception {
        return render(source, expectFailure, options());
    }
    private String render(String source, boolean expectFailure, GenerationOptions options) throws Exception {
        prepare();
        var generated = generator().generate("/tags.jsp", prefix()+source, "fixture.GeneratedPage", options);
        String main = """
                package io.vidocq.ibarra.core.internal;
                import jakarta.servlet.*; import jakarta.servlet.jsp.*;
                public class TagMain {
                    public static void main(String[] args) throws Throwable {
                        if (ModuleLayer.boot().findModule("java.compiler").isPresent()) throw new AssertionError("Runtime compiler present");
                        JspFactory.setDefaultFactory(new IbarraJspFactory());
                        var context = new ServletDoubles.Context(); var response = new ServletDoubles.Response();
                        var page = new fixture.GeneratedPage();
                        page.init(new ServletConfig() {
                            public String getServletName() { return "tags"; }
                            public ServletContext getServletContext() { return context; }
                            public String getInitParameter(String name) { return null; }
                            public java.util.Enumeration<String> getInitParameterNames() { return java.util.Collections.emptyEnumeration(); }
                        });
                        String failureTrace="";
                        try { page.service(new ServletDoubles.Request(), response); }
                        catch (Throwable expected) { if (!EXPECT_FAILURE) throw expected; failureTrace=String.valueOf(context.getAttribute("trace")); }
                        if (EXPECT_FAILURE && failureTrace.isEmpty()) throw new AssertionError("Expected servlet failure");
                        System.out.print("RESULT=" + java.util.Base64.getEncoder().encodeToString(response.output.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    }
                }
                """.replace("EXPECT_FAILURE", String.valueOf(expectFailure)).replace("response.output.toString().getBytes", "(failureTrace+response.output).getBytes");
        Path root=Path.of(System.getProperty("user.dir")).toAbsolutePath(); if(!Files.isDirectory(root.resolve("ibarra-core"))) root=root.getParent();
        String doubles=Files.readString(root.resolve("ibarra-core/src/test/java/io/vidocq/ibarra/core/internal/ServletDoubles.java"));
        compile(List.of(generated,new GeneratedPage("io.vidocq.ibarra.core.internal.TagMain",main,Map.of()),
                new GeneratedPage("io.vidocq.ibarra.core.internal.ServletDoubles",doubles,Map.of())));
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),
                "--limit-modules","io.vidocq.ibarra.core","--module-path",modulePath(),
                "--patch-module","io.vidocq.ibarra.core="+directory,"-m","io.vidocq.ibarra.core/io.vidocq.ibarra.core.internal.TagMain")
                .redirectErrorStream(true).redirectOutput(directory.resolve("tags.log").toFile()).start();
        assertTrue(process.waitFor(30,TimeUnit.SECONDS));
        String output=Files.readString(directory.resolve("tags.log")); assertEquals(0,process.exitValue(),output);
        assertTrue(output.startsWith("RESULT="),output);
        return new String(Base64.getDecoder().decode(output.substring(7)),StandardCharsets.UTF_8);
    }
    private void compile(List<GeneratedPage> sources) throws Exception {
        new PageCompiler().compile(sources,directory,List.of("--module-path",modulePath(),"--add-modules","io.vidocq.ibarra.core",
                "--patch-module","io.vidocq.ibarra.core="+directory));
    }
    private String modulePath() {
        var names=Set.of("io.vidocq.ibarra.api","io.vidocq.ibarra.core","jakarta.servlet","jakarta.servlet.jsp","jakarta.el");
        String available=System.getProperty("jdk.module.path","")+File.pathSeparator+System.getProperty("surefire.test.class.path","");
        var paths=Arrays.stream(available.split(java.util.regex.Pattern.quote(File.pathSeparator))).filter(s->!s.isEmpty()).map(Path::of)
                .filter(p->p.toString().endsWith(".jar")||Files.isRegularFile(p.resolve("module-info.class"))).distinct().toArray(Path[]::new);
        return ModuleFinder.of(paths).findAll().stream().filter(r->names.contains(r.descriptor().name()))
                .map(r->Path.of(r.location().orElseThrow()).toString()).collect(java.util.stream.Collectors.joining(File.pathSeparator));
    }
    private void prepare() throws Exception {
        compile(List.of(new GeneratedPage("fixture.Tags", """
                package fixture;
                import jakarta.servlet.jsp.*; import jakarta.servlet.jsp.tagext.*;
                public class Tags {
                    public static class Echo extends SimpleTagSupport {
                        private String label; private int count=1;
                        public void setLabel(String value) { label=value; } public void setCount(int value) { count=value; }
                        public void setXmlnsLabel(String value) { label=value; }
                        public void doTag() throws JspException,java.io.IOException {
                            var out=getJspContext().getOut(); out.write("["+label+":");
                            if(getJspBody()!=null) for(int i=0;i<count;i++) getJspBody().invoke(null);
                            out.write("]");
                        }
                    }
                    public static class RuntimeText extends Echo {
                        public void doTag() throws JspException,java.io.IOException {setCount(2);super.doTag();}
                    }
                    public static class Spaced extends SimpleTagSupport {
                        public void doTag() throws java.io.IOException {getJspContext().getOut().write(" space ");}
                    }
                    public static class Fragment extends SimpleTagSupport {
                        private JspFragment piece; public void setPiece(JspFragment value){piece=value;}
                        public void doTag() throws JspException,java.io.IOException {
                            var target=new java.io.StringWriter();piece.invoke(target);getJspContext().getOut().write("<"+target+">");
                            piece.invoke(null);if(getJspBody()!=null)getJspBody().invoke(null);
                        }
                    }
                    public static class ClassicFragment extends TagSupport {
                        private JspFragment piece; public void setPiece(JspFragment value){piece=value;}
                        public int doStartTag() throws JspException {
                            try{var target=new java.io.StringWriter();piece.invoke(target);pageContext.getOut().write("<"+target+">");piece.invoke(null);}
                            catch(java.io.IOException e){throw new JspException(e);}return SKIP_BODY;
                        }
                    }
                    public static class FragmentParent extends SimpleTagSupport {
                        public void doTag() throws JspException,java.io.IOException {
                            if(!(getParent() instanceof Fragment))throw new JspException("wrong fragment parent");
                            getJspContext().getOut().write("P");
                        }
                    }
                    public static class Repeat extends TagSupport {
                        private int count; public void setCount(int value){count=value;}
                        public int doStartTag(){return count>0?EVAL_BODY_INCLUDE:SKIP_BODY;}
                        public int doAfterBody(){return --count>0?EVAL_BODY_AGAIN:SKIP_BODY;}
                        public void release(){try{pageContext.getOut().write("R");}catch(java.io.IOException e){throw new IllegalStateException(e);}super.release();}
                    }
                    public static class Buffer extends BodyTagSupport {
                        public int doAfterBody() throws JspException {
                            try{bodyContent.getEnclosingWriter().write("["+bodyContent.getString().toUpperCase(java.util.Locale.ROOT)+"]");}
                            catch(java.io.IOException e){throw new JspException(e);} return SKIP_BODY;
                        }
                    }
                    public static class Guard extends BodyTagSupport implements TryCatchFinally {
                        private void write(String value){Object trace=pageContext.getAttribute("trace",jakarta.servlet.jsp.PageContext.APPLICATION_SCOPE);pageContext.setAttribute("trace",(trace==null?"":trace.toString())+value,jakarta.servlet.jsp.PageContext.APPLICATION_SCOPE);try{pageContext.getOut().write(value);}catch(java.io.IOException e){throw new IllegalStateException(e);}}
                        public void doCatch(Throwable failure){write("C");} public void doFinally(){write("F");} public void release(){write("R");super.release();}
                    }
                    public static class SetterFailure extends Guard { public void setBodyContent(BodyContent body){throw new IllegalStateException("setter failure");} }
                    public static class NamedGuard extends Guard { public void setCount(int value){} }
                    public static class Fail extends SimpleTagSupport {public void doTag() throws JspException {throw new JspException("failure");}}
                    public static class Capture extends SimpleTagSupport {
                        public void doTag() throws JspException,java.io.IOException {var writer=new java.io.StringWriter();getJspBody().invoke(writer);getJspContext().getOut().write("<"+writer+">");}
                    }
                    public static class Parent extends TagSupport {
                        public int doStartTag() throws JspException {if(!(getParent() instanceof TagAdapter adapter)||!(adapter.getAdaptee() instanceof Capture))throw new JspException("wrong parent");
                            try{pageContext.getOut().write("P");}catch(java.io.IOException e){throw new JspException(e);}return SKIP_BODY;}
                    }
                    public static class Skip extends Repeat {public int doStartTag(){return SKIP_BODY;}public int doEndTag(){return SKIP_PAGE;}}
                    public static class SimpleSkip extends SimpleTagSupport {public void doTag() throws SkipPageException {throw new SkipPageException();}}
                    public static class OwnSkip extends TagSupport implements TryCatchFinally {
                        public int doEndTag(){return SKIP_PAGE;} public void doCatch(Throwable failure){throw new AssertionError("Own catch called after normal skip return");}
                        public void doFinally(){try{pageContext.getOut().write("F");}catch(java.io.IOException e){throw new IllegalStateException(e);}}
                        public void release(){try{pageContext.getOut().write("R");}catch(java.io.IOException e){throw new IllegalStateException(e);}super.release();}
                    }
                    public static class CountOnly extends SimpleTagSupport {
                        private int count; public void setCount(int value){count=value;} public void doTag() throws java.io.IOException {getJspContext().getOut().print(count);}
                    }
                    public static class CharacterValue extends SimpleTagSupport {
                        private char value; public void setValue(char character){value=character;}
                        public void doTag() throws java.io.IOException {getJspContext().getOut().print(value);}
                    }
                    public static class ObjectValue extends SimpleTagSupport {
                        private Object value; public void setValue(Object object){value=object;} public void doTag() throws java.io.IOException {getJspContext().getOut().print(value);}
                    }
                    public static class Overload extends SimpleTagSupport {
                        private String value; public void setValue(Object object){value="O"+object;} public void setValue(String text){value="S"+text;}
                        public void doTag() throws java.io.IOException {getJspContext().getOut().write(value);}
                    }
                    public static class Second extends Echo {public void doTag() throws java.io.IOException {getJspContext().getOut().write("SECOND");}}
                    public static class Dynamic extends SimpleTagSupport implements DynamicAttributes {
                        private final StringBuilder attributes=new StringBuilder();
                        public void setDynamicAttribute(String uri,String name,Object value){if(uri!=null)throw new AssertionError("Unexpected namespace");attributes.append(name).append("=").append(value).append(";");}
                        public void doTag() throws java.io.IOException {getJspContext().getOut().write(attributes.toString());}
                    }
                    public static class BufferedRepeat extends BodyTagSupport {
                        private int left=2; public void doInitBody() throws JspException {try{bodyContent.write("i");}catch(java.io.IOException e){throw new JspException(e);}}
                        public int doAfterBody() throws JspException {try{bodyContent.getEnclosingWriter().write("["+bodyContent.getString()+"]");}catch(java.io.IOException e){throw new JspException(e);}return --left>0?EVAL_BODY_AGAIN:SKIP_BODY;}
                    }
                }
                """, Map.of())));
    }
}
