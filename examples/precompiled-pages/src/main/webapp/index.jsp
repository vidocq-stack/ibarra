<!doctype html>
<%@ taglib prefix="example" uri="urn:ibarra:example" %>
<%@ page import="java.util.Map.Entry" %>
<html><head><title>Ibarra precompiled pages</title></head><body>
<example:frame>
<jsp:attribute name="label">Build-generated tag</jsp:attribute>
<jsp:attribute name="heading" trim="false"> with a named fragment</jsp:attribute>
<jsp:body>
<jsp:useBean id="greeting" class="io.vidocq.ibarra.example.Greeting"/>
<h1>Hello, <jsp:getProperty name="greeting" property="name"/></h1>
<p>Compiled EL: ${1 + 2}; parameter: ${param.name}</p>
<p>Imported nested type: ${Entry != null}</p>
<%@ include file="footer.jspf" %>
</jsp:body>
</example:frame>
</body></html>
