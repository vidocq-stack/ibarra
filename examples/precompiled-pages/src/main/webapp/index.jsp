<!doctype html>
<%@ taglib prefix="example" uri="urn:ibarra:example" %>
<html><head><title>Ibarra precompiled pages</title></head><body>
<example:frame label="Build-generated tag">
<jsp:useBean id="greeting" class="io.vidocq.ibarra.example.Greeting"/>
<h1>Hello, <jsp:getProperty name="greeting" property="name"/></h1>
<%@ include file="footer.jspf" %>
</example:frame>
</body></html>
