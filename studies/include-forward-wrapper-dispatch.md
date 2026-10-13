# Include-forward response wrapper boundary

Date: 2026-10-13. This records a withheld prototype, not delivered runtime support.

The signed prototype on `fix-include-forward` (`8d9c54ac98a99b1df1042bbacc9e6702ceac79b3`)
restores all 19 official include/forward methods. It clears ancestor JSP buffers,
records successful forwarding through a typed request-owned state, and stops
the including pages. Its original outer application response wrapper is preserved.

Independent review found that forwarding through that saved outer response
bypasses a wrapper added by an application during included dispatch. A concrete
red core test confirms that the target receives the underlying response and writes
`target`, rather than receiving the application wrapper and writing `TARGET`.
The prototype is therefore withheld from the parallel publication batch. The
original failing `positiveIncludeForwardTest` remains tracked as IBARRA-119;
the prototype wrapper regression is IBARRA-130.

## Reproduction

In the retained `fix-include-forward` worktree, add this temporary test to
`PageRuntimeTest`, using its committed `Web`/`Dispatch` helpers:

```java
@Test
void includedForwardKeepsAnApplicationWrapperAddedInsideTheInclude() throws Exception {
    var web = new Web();
    var included = web.dispatch("/included.jsp");
    ServletResponse[] delivered = {null};
    HttpServletResponse[] childWrapper = {null};
    web.context.dispatchers.put("/target.jsp", new RequestDispatcher() {
        public void forward(ServletRequest request, ServletResponse response) throws IOException {
            delivered[0] = response;
            response.getWriter().write("target");
            response.getWriter().flush();
        }
        public void include(ServletRequest request, ServletResponse response) {
            throw new UnsupportedOperationException();
        }
    });
    var parent = web.open(false, null);
    included.action = (request, response) -> {
        var wrapper = new HttpServletResponseWrapper((HttpServletResponse) response) {
            @Override public java.io.PrintWriter getWriter() throws IOException {
                return new java.io.PrintWriter(super.getWriter()) {
                    @Override public void write(String value, int offset, int length) {
                        super.write(value.substring(offset, offset + length)
                                .toUpperCase(java.util.Locale.ROOT), 0, length);
                    }
                };
            }
        };
        childWrapper[0] = wrapper;
        var child = new IbarraPageContext();
        child.initialize(web.page, request, wrapper, null, false, 64, true);
        try { child.forward("/target.jsp"); }
        finally { child.release(); }
    };
    parent.include("/included.jsp", false);
    assertAll(() -> assertSame(childWrapper[0], delivered[0]),
            () -> assertEquals("TARGET", web.response.output.toString()));
}
```

With Temurin Java 25, from that worktree:

```bash
./mvnw -o -ntp -pl ibarra-core -am test \
  -Dtest=PageRuntimeTest#includedForwardKeepsAnApplicationWrapperAddedInsideTheInclude \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Observed: one test, one failure, zero errors/skips; both assertions fail. The
temporary failing test was removed after preserving this reproduction.

## Required follow-up

[Servlet 6.1 sections 6.2.2 and 9.2](https://jakarta.ee/specifications/servlet/6.1/jakarta-servlet-spec-6.1.html)
require preservation of the applicable service/dispatch wrapper chain.
[Pages 4.0 section 5.5](https://jakarta.ee/specifications/pages/4.0/jakarta-server-pages-spec-4.0.html#jsp:forward)
requires buffer clearing and page termination without authorizing application
wrapper removal.

Core must remain container-neutral. Ordinary wrapper traversal identifies
Ibarra's own inclusion owner but cannot classify opaque container wrappers.
A follow-up must provide a typed dispatch contract with the container, preserve
application wrapper identity and retained delegates, and apply forward header,
status, flush/close and asynchronous-response rules. Mutating unknown wrapper
delegates or guessing their classes is not an acceptable replacement.
