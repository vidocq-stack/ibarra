# Official Jakarta Pages 4.0 TCK runner

The runner uses the unmodified `jakarta.tck:jakarta-pages-tck:4.0.0` artifact
from the [official Eclipse distribution](https://download.eclipse.org/jakartaee/pages/4.0/jakarta-pages-tck-4.0.0.zip).
The first smoke subset passes; the full suite does **not** pass. See
[BASELINE.md](BASELINE.md) for measured results and deployment limitations.

## Run

Use Java 25, Maven 3.9.16 and Python 3 from the repository checkout:

```bash
./run-official-tck-pages-4.0.sh smoke
./run-official-tck-pages-4.0.sh all
./run-official-tck-pages-4.0.sh -Dtest=ee.jakarta.tck.pages.api.jakarta_servlet.jsp.jspwriter.URLClientIT
```

The script verifies/downloads the official artifacts, installs them locally,
builds the current Ibarra reactor with `clean install`, runs four adapter
regression tests, then runs the requested official classes. The default smoke
selects three `JspFactory` tests and one `JspEngineInfo` test. CI runs this smoke
on pull requests and `main`; it does not claim full-suite certification.

`--no-install` skips the reactor build only when its current artifacts have
already been installed. `--no-prepare` skips artifact preparation. Ordinary
Maven properties are forwarded to the official run. Run modes are processed
left to right; the last `smoke`/`all`/`-Dtest=` selection takes precedence.

```bash
./mvnw -Ptck -pl ibarra-tck clean test
./mvnw -Ptck,tck-official -pl ibarra-tck test \
  -Dtest=ee.jakarta.tck.pages.api.jakarta_servlet.jsp.jspfactory.URLClientIT
```

Normal reactor builds never resolve official TCK artifacts. `-Ptck` adds only
the unpublished harness; `tck-official` enables dependency scanning of `*IT`
classes. Clients retain the upstream Arquillian/JUnit dependencies. The runner
sets `porting.ts.url.class.1` to the Pages distribution's `SunRIURL` implementation;
the upstream common runtime otherwise defaults to a missing REST implementation.

## Artifact preparation

`prepare-tck.py` uses Python's standard library and the Maven wrapper. It
preserves the official jar's embedded Maven POM, including the upstream common
runtime version `11.0.0-M1`. The standalone 4.0 archive omits its non-Central
`javatest:javatest:5.0` dependency. Eclipse's earlier Pages 3.1 distribution
contains that exact JavaTest version (`META-INF/buildInfo.txt`); only the
JavaTest jar is extracted. No older test classes are installed or executed.

Verified SHA-256 fingerprints:

| Artifact | SHA-256 |
|---|---|
| Pages 4.0 distribution | `5446aa866601a7c4c425f74054590cfc7f4fc05592a572d399ecc8694e265489` |
| Pages 3.1 distribution supplying JavaTest | `e9f33d463ca35bdf8a9985cc8871858d7e6ea8a6e90adb00a91a001b1e43e025` |
| JavaTest 5.0 jar | `73d0c322e76839c405f2000b568cc8906977b430ba23e83f20d420345e8a13a4` |

Set `TCK_PAGES_ZIP` and `TCK_JAVATEST_ZIP` to local copies to avoid downloading
the distributions. Their checksums are still mandatory. Maven may separately
need network access to resolve Central dependencies. Downloaded files are
cached under `target/pages-tck-distribution`; `clean` removes that cache.

## Deployment model and limits

Arquillian clients and ShrinkWrap use a test-only classpath, as in `foy-tck`.
The adapter copies WAR bytecode into a named `tck.application` module, compiles
its descriptor and pages on the build machine, then launches a named `tck.host`
with Foy and Chappe. Generated page indexes construct pages directly. The server
asserts that both compilers and `java.compiler` are absent, and starts with
`peano.reflection=deny`. Application packages receive no exports or opens.

Each archive has its own process, application layer, port and resource root.
Multiple Arquillian deployments can coexist. Undeployment drains/stops the
server and releases the application; forced cleanup waits for termination and
preserves interruption. Work directories and `server.log` remain under
`ibarra-tck/target/pages-deployments` for diagnosis, until the next runner clean.
Surefire XML/text reports are under `ibarra-tck/target/surefire-reports`.

Current adapter diagnostics explicitly reject WAR libraries, descriptor or
annotated Servlet components requiring generated factories, ServletContainerInitializer
providers, and JSP property groups. Bean/accessor adaptation and signature-test
configuration remain incomplete. Invalid pages fail eager WAR precompilation;
the runner cannot yet expose individual translation failures at request time.
Unsupported compiler semantics retain their existing diagnostics. None of
these failures is silently skipped or converted into a passing test.
