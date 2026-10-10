<!doctype html>
<html><head><title>Ibarra precompiled pages</title></head><body>
<jsp:useBean id="greeting" class="io.vidocq.ibarra.example.Greeting"/>
<h1>Hello, <jsp:getProperty name="greeting" property="name"/></h1>
<%@ include file="footer.jspf" %>
</body></html>
