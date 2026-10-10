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
package io.vidocq.ibarra.core.internal;

/** Concrete servlet doubles; unused methods fail fast. */
final class ServletDoubles {
static final class Context implements jakarta.servlet.ServletContext {
final java.util.Map<String,Object> attributes = new java.util.HashMap<>();
final java.util.Map<String,jakarta.servlet.RequestDispatcher> dispatchers = new java.util.HashMap<>();
    @Override public java.lang.String getContextPath() { return ""; }
    @Override public jakarta.servlet.ServletContext getContext(java.lang.String a0) { throw new UnsupportedOperationException("getContext"); }
    @Override public int getMajorVersion() { throw new UnsupportedOperationException("getMajorVersion"); }
    @Override public int getMinorVersion() { throw new UnsupportedOperationException("getMinorVersion"); }
    @Override public int getEffectiveMajorVersion() { throw new UnsupportedOperationException("getEffectiveMajorVersion"); }
    @Override public int getEffectiveMinorVersion() { throw new UnsupportedOperationException("getEffectiveMinorVersion"); }
    @Override public java.lang.String getMimeType(java.lang.String a0) { throw new UnsupportedOperationException("getMimeType"); }
    @Override public java.util.Set<java.lang.String> getResourcePaths(java.lang.String a0) { throw new UnsupportedOperationException("getResourcePaths"); }
    @Override public java.net.URL getResource(java.lang.String a0) throws java.net.MalformedURLException { throw new UnsupportedOperationException("getResource"); }
    @Override public java.io.InputStream getResourceAsStream(java.lang.String a0) { throw new UnsupportedOperationException("getResourceAsStream"); }
    @Override public jakarta.servlet.RequestDispatcher getRequestDispatcher(java.lang.String a0) { return dispatchers.get(a0); }
    @Override public jakarta.servlet.RequestDispatcher getNamedDispatcher(java.lang.String a0) { throw new UnsupportedOperationException("getNamedDispatcher"); }
    @Override public void log(java.lang.String a0) { throw new UnsupportedOperationException("log"); }
    @Override public void log(java.lang.String a0, java.lang.Throwable a1) { throw new UnsupportedOperationException("log"); }
    @Override public java.lang.String getRealPath(java.lang.String a0) { throw new UnsupportedOperationException("getRealPath"); }
    @Override public java.lang.String getServerInfo() { throw new UnsupportedOperationException("getServerInfo"); }
    @Override public java.lang.String getInitParameter(java.lang.String a0) { throw new UnsupportedOperationException("getInitParameter"); }
    @Override public java.util.Enumeration<java.lang.String> getInitParameterNames() { throw new UnsupportedOperationException("getInitParameterNames"); }
    @Override public boolean setInitParameter(java.lang.String a0, java.lang.String a1) { throw new UnsupportedOperationException("setInitParameter"); }
    @Override public java.lang.Object getAttribute(java.lang.String a0) { return attributes.get(a0); }
    @Override public java.util.Enumeration<java.lang.String> getAttributeNames() { return java.util.Collections.enumeration(attributes.keySet()); }
    @Override public void setAttribute(java.lang.String a0, java.lang.Object a1) { if (a1 == null) attributes.remove(a0); else attributes.put(a0, a1); }
    @Override public void removeAttribute(java.lang.String a0) { attributes.remove(a0); }
    @Override public java.lang.String getServletContextName() { throw new UnsupportedOperationException("getServletContextName"); }
    @Override public jakarta.servlet.ServletRegistration.Dynamic addServlet(java.lang.String a0, java.lang.String a1) { throw new UnsupportedOperationException("addServlet"); }
    @Override public jakarta.servlet.ServletRegistration.Dynamic addServlet(java.lang.String a0, jakarta.servlet.Servlet a1) { throw new UnsupportedOperationException("addServlet"); }
    @Override public jakarta.servlet.ServletRegistration.Dynamic addServlet(java.lang.String a0, java.lang.Class<? extends jakarta.servlet.Servlet> a1) { throw new UnsupportedOperationException("addServlet"); }
    @Override public jakarta.servlet.ServletRegistration.Dynamic addJspFile(java.lang.String a0, java.lang.String a1) { throw new UnsupportedOperationException("addJspFile"); }
    @Override public <T extends jakarta.servlet.Servlet> T createServlet(java.lang.Class<T> a0) throws jakarta.servlet.ServletException { throw new UnsupportedOperationException("createServlet"); }
    @Override public jakarta.servlet.ServletRegistration getServletRegistration(java.lang.String a0) { throw new UnsupportedOperationException("getServletRegistration"); }
    @Override public java.util.Map<java.lang.String, ? extends jakarta.servlet.ServletRegistration> getServletRegistrations() { throw new UnsupportedOperationException("getServletRegistrations"); }
    @Override public jakarta.servlet.FilterRegistration.Dynamic addFilter(java.lang.String a0, java.lang.String a1) { throw new UnsupportedOperationException("addFilter"); }
    @Override public jakarta.servlet.FilterRegistration.Dynamic addFilter(java.lang.String a0, jakarta.servlet.Filter a1) { throw new UnsupportedOperationException("addFilter"); }
    @Override public jakarta.servlet.FilterRegistration.Dynamic addFilter(java.lang.String a0, java.lang.Class<? extends jakarta.servlet.Filter> a1) { throw new UnsupportedOperationException("addFilter"); }
    @Override public <T extends jakarta.servlet.Filter> T createFilter(java.lang.Class<T> a0) throws jakarta.servlet.ServletException { throw new UnsupportedOperationException("createFilter"); }
    @Override public jakarta.servlet.FilterRegistration getFilterRegistration(java.lang.String a0) { throw new UnsupportedOperationException("getFilterRegistration"); }
    @Override public java.util.Map<java.lang.String, ? extends jakarta.servlet.FilterRegistration> getFilterRegistrations() { throw new UnsupportedOperationException("getFilterRegistrations"); }
    @Override public jakarta.servlet.SessionCookieConfig getSessionCookieConfig() { throw new UnsupportedOperationException("getSessionCookieConfig"); }
    @Override public void setSessionTrackingModes(java.util.Set<jakarta.servlet.SessionTrackingMode> a0) { throw new UnsupportedOperationException("setSessionTrackingModes"); }
    @Override public java.util.Set<jakarta.servlet.SessionTrackingMode> getDefaultSessionTrackingModes() { throw new UnsupportedOperationException("getDefaultSessionTrackingModes"); }
    @Override public java.util.Set<jakarta.servlet.SessionTrackingMode> getEffectiveSessionTrackingModes() { throw new UnsupportedOperationException("getEffectiveSessionTrackingModes"); }
    @Override public void addListener(java.lang.String a0) { throw new UnsupportedOperationException("addListener"); }
    @Override public <T extends java.util.EventListener> void addListener(T a0) { throw new UnsupportedOperationException("addListener"); }
    @Override public void addListener(java.lang.Class<? extends java.util.EventListener> a0) { throw new UnsupportedOperationException("addListener"); }
    @Override public <T extends java.util.EventListener> T createListener(java.lang.Class<T> a0) throws jakarta.servlet.ServletException { throw new UnsupportedOperationException("createListener"); }
    @Override public jakarta.servlet.descriptor.JspConfigDescriptor getJspConfigDescriptor() { throw new UnsupportedOperationException("getJspConfigDescriptor"); }
    @Override public java.lang.ClassLoader getClassLoader() { throw new UnsupportedOperationException("getClassLoader"); }
    @Override public void declareRoles(java.lang.String... a0) { throw new UnsupportedOperationException("declareRoles"); }
    @Override public java.lang.String getVirtualServerName() { throw new UnsupportedOperationException("getVirtualServerName"); }
    @Override public int getSessionTimeout() { throw new UnsupportedOperationException("getSessionTimeout"); }
    @Override public void setSessionTimeout(int a0) { throw new UnsupportedOperationException("setSessionTimeout"); }
    @Override public java.lang.String getRequestCharacterEncoding() { throw new UnsupportedOperationException("getRequestCharacterEncoding"); }
    @Override public void setRequestCharacterEncoding(java.lang.String a0) { throw new UnsupportedOperationException("setRequestCharacterEncoding"); }
    @Override public java.lang.String getResponseCharacterEncoding() { throw new UnsupportedOperationException("getResponseCharacterEncoding"); }
    @Override public void setResponseCharacterEncoding(java.lang.String a0) { throw new UnsupportedOperationException("setResponseCharacterEncoding"); }
}

static final class Session implements jakarta.servlet.http.HttpSession {
final java.util.Map<String,Object> attributes = new java.util.HashMap<>();
    @Override public long getCreationTime() { throw new UnsupportedOperationException("getCreationTime"); }
    @Override public java.lang.String getId() { throw new UnsupportedOperationException("getId"); }
    @Override public long getLastAccessedTime() { throw new UnsupportedOperationException("getLastAccessedTime"); }
    @Override public jakarta.servlet.ServletContext getServletContext() { throw new UnsupportedOperationException("getServletContext"); }
    @Override public void setMaxInactiveInterval(int a0) { throw new UnsupportedOperationException("setMaxInactiveInterval"); }
    @Override public int getMaxInactiveInterval() { throw new UnsupportedOperationException("getMaxInactiveInterval"); }
    @Override public java.lang.Object getAttribute(java.lang.String a0) { return attributes.get(a0); }
    @Override public java.util.Enumeration<java.lang.String> getAttributeNames() { return java.util.Collections.enumeration(attributes.keySet()); }
    @Override public void setAttribute(java.lang.String a0, java.lang.Object a1) { if (a1 == null) attributes.remove(a0); else attributes.put(a0, a1); }
    @Override public void removeAttribute(java.lang.String a0) { attributes.remove(a0); }
    @Override public void invalidate() { throw new UnsupportedOperationException("invalidate"); }
    @Override public boolean isNew() { throw new UnsupportedOperationException("isNew"); }
}

static final class Request implements jakarta.servlet.http.HttpServletRequest {
final java.util.Map<String,Object> attributes = new java.util.HashMap<>();
Session session;
int sessionCalls;
String servletPath = "/pages/view.jsp";
    @Override public java.lang.String getAuthType() { throw new UnsupportedOperationException("getAuthType"); }
    @Override public jakarta.servlet.http.Cookie[] getCookies() { throw new UnsupportedOperationException("getCookies"); }
    @Override public long getDateHeader(java.lang.String a0) { throw new UnsupportedOperationException("getDateHeader"); }
    @Override public java.lang.String getHeader(java.lang.String a0) { throw new UnsupportedOperationException("getHeader"); }
    @Override public java.util.Enumeration<java.lang.String> getHeaders(java.lang.String a0) { throw new UnsupportedOperationException("getHeaders"); }
    @Override public java.util.Enumeration<java.lang.String> getHeaderNames() { throw new UnsupportedOperationException("getHeaderNames"); }
    @Override public int getIntHeader(java.lang.String a0) { throw new UnsupportedOperationException("getIntHeader"); }
    @Override public java.lang.String getMethod() { throw new UnsupportedOperationException("getMethod"); }
    @Override public java.lang.String getPathInfo() { return null; }
    @Override public java.lang.String getPathTranslated() { throw new UnsupportedOperationException("getPathTranslated"); }
    @Override public java.lang.String getContextPath() { return ""; }
    @Override public java.lang.String getQueryString() { throw new UnsupportedOperationException("getQueryString"); }
    @Override public java.lang.String getRemoteUser() { throw new UnsupportedOperationException("getRemoteUser"); }
    @Override public boolean isUserInRole(java.lang.String a0) { throw new UnsupportedOperationException("isUserInRole"); }
    @Override public java.security.Principal getUserPrincipal() { throw new UnsupportedOperationException("getUserPrincipal"); }
    @Override public java.lang.String getRequestedSessionId() { throw new UnsupportedOperationException("getRequestedSessionId"); }
    @Override public java.lang.String getRequestURI() { return servletPath; }
    @Override public java.lang.StringBuffer getRequestURL() { throw new UnsupportedOperationException("getRequestURL"); }
    @Override public java.lang.String getServletPath() { return servletPath; }
    @Override public jakarta.servlet.http.HttpSession getSession(boolean a0) { sessionCalls++; if (session == null && a0) session = new Session(); return session; }
    @Override public jakarta.servlet.http.HttpSession getSession() { sessionCalls++; if (session == null && true) session = new Session(); return session; }
    @Override public java.lang.String changeSessionId() { throw new UnsupportedOperationException("changeSessionId"); }
    @Override public boolean isRequestedSessionIdValid() { throw new UnsupportedOperationException("isRequestedSessionIdValid"); }
    @Override public boolean isRequestedSessionIdFromCookie() { throw new UnsupportedOperationException("isRequestedSessionIdFromCookie"); }
    @Override public boolean isRequestedSessionIdFromURL() { throw new UnsupportedOperationException("isRequestedSessionIdFromURL"); }
    @Override public boolean authenticate(jakarta.servlet.http.HttpServletResponse a0) throws java.io.IOException, jakarta.servlet.ServletException { throw new UnsupportedOperationException("authenticate"); }
    @Override public void login(java.lang.String a0, java.lang.String a1) throws jakarta.servlet.ServletException { throw new UnsupportedOperationException("login"); }
    @Override public void logout() throws jakarta.servlet.ServletException { throw new UnsupportedOperationException("logout"); }
    @Override public java.util.Collection<jakarta.servlet.http.Part> getParts() throws java.io.IOException, jakarta.servlet.ServletException { throw new UnsupportedOperationException("getParts"); }
    @Override public jakarta.servlet.http.Part getPart(java.lang.String a0) throws java.io.IOException, jakarta.servlet.ServletException { throw new UnsupportedOperationException("getPart"); }
    @Override public <T extends jakarta.servlet.http.HttpUpgradeHandler> T upgrade(java.lang.Class<T> a0) throws java.io.IOException, jakarta.servlet.ServletException { throw new UnsupportedOperationException("upgrade"); }
    @Override public java.lang.Object getAttribute(java.lang.String a0) { return attributes.get(a0); }
    @Override public java.util.Enumeration<java.lang.String> getAttributeNames() { return java.util.Collections.enumeration(attributes.keySet()); }
    @Override public java.lang.String getCharacterEncoding() { throw new UnsupportedOperationException("getCharacterEncoding"); }
    @Override public void setCharacterEncoding(java.lang.String a0) throws java.io.UnsupportedEncodingException { throw new UnsupportedOperationException("setCharacterEncoding"); }
    @Override public int getContentLength() { throw new UnsupportedOperationException("getContentLength"); }
    @Override public long getContentLengthLong() { throw new UnsupportedOperationException("getContentLengthLong"); }
    @Override public java.lang.String getContentType() { throw new UnsupportedOperationException("getContentType"); }
    @Override public jakarta.servlet.ServletInputStream getInputStream() throws java.io.IOException { throw new UnsupportedOperationException("getInputStream"); }
    @Override public java.lang.String getParameter(java.lang.String a0) { throw new UnsupportedOperationException("getParameter"); }
    @Override public java.util.Enumeration<java.lang.String> getParameterNames() { throw new UnsupportedOperationException("getParameterNames"); }
    @Override public java.lang.String[] getParameterValues(java.lang.String a0) { throw new UnsupportedOperationException("getParameterValues"); }
    @Override public java.util.Map<java.lang.String, java.lang.String[]> getParameterMap() { throw new UnsupportedOperationException("getParameterMap"); }
    @Override public java.lang.String getProtocol() { throw new UnsupportedOperationException("getProtocol"); }
    @Override public java.lang.String getScheme() { throw new UnsupportedOperationException("getScheme"); }
    @Override public java.lang.String getServerName() { throw new UnsupportedOperationException("getServerName"); }
    @Override public int getServerPort() { throw new UnsupportedOperationException("getServerPort"); }
    @Override public java.io.BufferedReader getReader() throws java.io.IOException { throw new UnsupportedOperationException("getReader"); }
    @Override public java.lang.String getRemoteAddr() { throw new UnsupportedOperationException("getRemoteAddr"); }
    @Override public java.lang.String getRemoteHost() { throw new UnsupportedOperationException("getRemoteHost"); }
    @Override public void setAttribute(java.lang.String a0, java.lang.Object a1) { if (a1 == null) attributes.remove(a0); else attributes.put(a0, a1); }
    @Override public void removeAttribute(java.lang.String a0) { attributes.remove(a0); }
    @Override public java.util.Locale getLocale() { throw new UnsupportedOperationException("getLocale"); }
    @Override public java.util.Enumeration<java.util.Locale> getLocales() { throw new UnsupportedOperationException("getLocales"); }
    @Override public boolean isSecure() { throw new UnsupportedOperationException("isSecure"); }
    @Override public jakarta.servlet.RequestDispatcher getRequestDispatcher(java.lang.String a0) { throw new UnsupportedOperationException("getRequestDispatcher"); }
    @Override public int getRemotePort() { throw new UnsupportedOperationException("getRemotePort"); }
    @Override public java.lang.String getLocalName() { throw new UnsupportedOperationException("getLocalName"); }
    @Override public java.lang.String getLocalAddr() { throw new UnsupportedOperationException("getLocalAddr"); }
    @Override public int getLocalPort() { throw new UnsupportedOperationException("getLocalPort"); }
    @Override public jakarta.servlet.ServletContext getServletContext() { throw new UnsupportedOperationException("getServletContext"); }
    @Override public jakarta.servlet.AsyncContext startAsync() throws java.lang.IllegalStateException { throw new UnsupportedOperationException("startAsync"); }
    @Override public jakarta.servlet.AsyncContext startAsync(jakarta.servlet.ServletRequest a0, jakarta.servlet.ServletResponse a1) throws java.lang.IllegalStateException { throw new UnsupportedOperationException("startAsync"); }
    @Override public boolean isAsyncStarted() { throw new UnsupportedOperationException("isAsyncStarted"); }
    @Override public boolean isAsyncSupported() { throw new UnsupportedOperationException("isAsyncSupported"); }
    @Override public jakarta.servlet.AsyncContext getAsyncContext() { throw new UnsupportedOperationException("getAsyncContext"); }
    @Override public jakarta.servlet.DispatcherType getDispatcherType() { throw new UnsupportedOperationException("getDispatcherType"); }
    @Override public java.lang.String getRequestId() { throw new UnsupportedOperationException("getRequestId"); }
    @Override public java.lang.String getProtocolRequestId() { throw new UnsupportedOperationException("getProtocolRequestId"); }
    @Override public jakarta.servlet.ServletConnection getServletConnection() { throw new UnsupportedOperationException("getServletConnection"); }
}

static final class Response implements jakarta.servlet.http.HttpServletResponse {
    int status = 200;
    @Override public void addCookie(jakarta.servlet.http.Cookie a0) { throw new UnsupportedOperationException("addCookie"); }
    @Override public boolean containsHeader(java.lang.String a0) { throw new UnsupportedOperationException("containsHeader"); }
    @Override public java.lang.String encodeURL(java.lang.String a0) { throw new UnsupportedOperationException("encodeURL"); }
    @Override public java.lang.String encodeRedirectURL(java.lang.String a0) { throw new UnsupportedOperationException("encodeRedirectURL"); }
    @Override public void sendError(int a0, java.lang.String a1) throws java.io.IOException { throw new UnsupportedOperationException("sendError"); }
    @Override public void sendError(int a0) throws java.io.IOException { throw new UnsupportedOperationException("sendError"); }
    @Override public void sendRedirect(java.lang.String a0, int a1, boolean a2) throws java.io.IOException { throw new UnsupportedOperationException("sendRedirect"); }
    @Override public void setDateHeader(java.lang.String a0, long a1) { throw new UnsupportedOperationException("setDateHeader"); }
    @Override public void addDateHeader(java.lang.String a0, long a1) { throw new UnsupportedOperationException("addDateHeader"); }
    @Override public void setHeader(java.lang.String a0, java.lang.String a1) { throw new UnsupportedOperationException("setHeader"); }
    @Override public void addHeader(java.lang.String a0, java.lang.String a1) { throw new UnsupportedOperationException("addHeader"); }
    @Override public void setIntHeader(java.lang.String a0, int a1) { throw new UnsupportedOperationException("setIntHeader"); }
    @Override public void addIntHeader(java.lang.String a0, int a1) { throw new UnsupportedOperationException("addIntHeader"); }
    @Override public void setStatus(int a0) { status = a0; }
    @Override public int getStatus() { return status; }
    @Override public java.lang.String getHeader(java.lang.String a0) { throw new UnsupportedOperationException("getHeader"); }
    @Override public java.util.Collection<java.lang.String> getHeaders(java.lang.String a0) { throw new UnsupportedOperationException("getHeaders"); }
    @Override public java.util.Collection<java.lang.String> getHeaderNames() { throw new UnsupportedOperationException("getHeaderNames"); }
final java.io.StringWriter output = new java.io.StringWriter();
boolean committed;
int acquisitions;
final java.io.PrintWriter writer = new java.io.PrintWriter(output) {
@Override public void flush() { super.flush(); committed = true; }
};
    @Override public java.lang.String getCharacterEncoding() { return "UTF-8"; }
    @Override public java.lang.String getContentType() { return "text/html"; }
    @Override public jakarta.servlet.ServletOutputStream getOutputStream() throws java.io.IOException { throw new UnsupportedOperationException("getOutputStream"); }
    @Override public java.io.PrintWriter getWriter() throws java.io.IOException { acquisitions++; return writer; }
    @Override public void setCharacterEncoding(java.lang.String a0) {  }
    @Override public void setContentLength(int a0) { throw new UnsupportedOperationException("setContentLength"); }
    @Override public void setContentLengthLong(long a0) { throw new UnsupportedOperationException("setContentLengthLong"); }
    @Override public void setContentType(java.lang.String a0) {  }
    @Override public void setBufferSize(int a0) { throw new UnsupportedOperationException("setBufferSize"); }
    @Override public int getBufferSize() { return 8192; }
    @Override public void flushBuffer() throws java.io.IOException { writer.flush(); }
    @Override public void resetBuffer() { if (committed) throw new IllegalStateException("Committed"); output.getBuffer().setLength(0); }
    @Override public boolean isCommitted() { return committed; }
    @Override public void reset() { throw new UnsupportedOperationException("reset"); }
    @Override public void setLocale(java.util.Locale a0) { throw new UnsupportedOperationException("setLocale"); }
    @Override public java.util.Locale getLocale() { throw new UnsupportedOperationException("getLocale"); }
}
}

