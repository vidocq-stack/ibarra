# Official Jakarta Pages 4.0 TCK runner

The runner uses the unmodified `jakarta.tck:jakarta-pages-tck:4.0.0` artifact
from the [official Eclipse distribution](https://download.eclipse.org/jakartaee/pages/4.0/jakarta-pages-tck-4.0.0.zip).
The first smoke subset passes; the full suite does **not** pass. See
[BASELINE.md](BASELINE.md) for measured results and deployment limitations.

The runner deploys the TCK WARs through the published Arquillian adapter
[`ibarra-arquillian`](../ibarra-arquillian/README.md), taken at `test` scope with no
configuration property set. Its deployment model, configuration and limits are
documented there; this file keeps what is specific to the Pages TCK.

## Run

Use Java 25, Maven 3.9.16 and Python 3 from the repository checkout:

```bash
./run-official-tck-pages-4.0.sh smoke
./run-official-tck-pages-4.0.sh all
./run-official-tck-pages-4.0.sh -Dtest=ee.jakarta.tck.pages.api.jakarta_servlet.jsp.jspwriter.URLClientIT
```

The script verifies/downloads the official artifacts, installs them locally,
builds the current Ibarra reactor with `clean install` (which runs the adapter
regression tests of `ibarra-arquillian`), runs the runner tests with
`-Ptck -pl ibarra-tck`, then runs the requested official classes. The default smoke
selects three `JspFactory` tests, one `JspEngineInfo` test and fourteen descriptor
character-sequence/EL/encoding tests (18 total). CI runs this smoke
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
the unpublished runner; `tck-official` enables dependency scanning of `*IT`
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

## Official API signature check

The official-only profile uses maven-dependency-plugin 3.8.1 to copy the exact
configured Pages, Servlet and EL API versions into target/signature-api. The
unchanged upstream driver receives those API paths through sigTestClasspath and
its extracted java.base image through jimage.dir. No signature file or expected
member is altered. The signature tool's build-side classpath is separate from the
application/host module path; no implementation or Maven tooling is shipped.

```bash
./mvnw -ntp -Ptck,tck-official -pl ibarra-tck clean test \
  -Dtest=ee.jakarta.tck.pages.signaturetest.jsp.JSPSigTestIT
```

The `-Ptck` profile alone does not activate this resource-copy execution.

## Deployment

The adapter's [deployment model and limits](../ibarra-arquillian/README.md#deployment-model-and-limits)
apply unchanged: the runner sets no adapter property. Work directories and
`server.log` remain under `ibarra-tck/target/pages-deployments` for diagnosis, until
the next runner clean. Surefire XML/text reports are under
`ibarra-tck/target/surefire-reports`.
