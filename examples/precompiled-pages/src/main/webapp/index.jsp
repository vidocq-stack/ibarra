<!doctype html>
<%@ taglib prefix="example" uri="urn:ibarra:example" %>
<html><head><title>Ibarra precompiled pages</title></head><body>
<example:frame>
<jsp:attribute name="label">Build-generated tag</jsp:attribute>
<jsp:attribute name="heading" trim="false"> with a named fragment</jsp:attribute>
<jsp:body>
<jsp:useBean id="greeting" class="io.vidocq.ibarra.example.Greeting"/>
<h1>Hello, <jsp:getProperty name="greeting" property="name"/></h1>
<%@ include file="footer.jspf" %>
</jsp:body>
</example:frame>
</body></html>
