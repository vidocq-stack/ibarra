# Ibarra Arquillian Adapter Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move Ibarra's Arquillian TCK adapter out of `ibarra-tck/src/test` into a published,
default-reactor module `ibarra-arquillian` that `ibarra-tck` (and later Bodoni's `bodoni-tck`)
takes at test scope, with three small configuration hooks Bodoni's Tags TCK run needs.

**Architecture:** Task 1 relocates the code (package `io.vidocq.ibarra.tck` →
`io.vidocq.ibarra.arquillian`) with no behaviour change and records the official Pages TCK
result before the move. Task 2 gives `PagesContainer` one application-descriptor model and
named deployment steps. Tasks 3–4 add `arquillian.xml` properties on `PagesConfiguration`:
`applicationLibraries`, `jdkModules`, `applicationOpens`. With none set, the adapter produces
the same application descriptor and the same server command line as today. Task 5 is the
documentation. Task 6 records the official run after the move and clears the SonarQube
findings.

**Tech Stack:** Java 25 (Class-File API, `java.lang.module`, `javax.tools`), Maven 3.9.16,
Arquillian container SPI 1.8.0.Final, ShrinkWrap 1.2.6, JUnit 6 (`junit-bom` from
`vidocq-parent`), Foy + Chappe + Peano snapshots `0.4.0-SNAPSHOT`.

**Spec:** `/private/tmp/claude-501/-Users-yblazart-projects-perso-vidocq-workspace/90b9f66f-b932-420f-992b-d61241321759/scratchpad/ibarra-adapter-plan-brief.md`
(maintainer decisions of 2026-10-23). Read `AGENTS.md`, `ibarra-tck/README.md` and
`docs/en/modules/ROOT/pages/modules/official-tck-runner.adoc` before any task.

## Global Constraints

- Build with `JAVA_HOME=/Users/yblazart/.sdkman/candidates/java/25-tem` and `./mvnw -ntp`
  from the worktree root `/Users/yblazart/projects/perso/vidocq-workspace/ibarra/feat/arquillian-adapter`.
- New artifact: `io.vidocq.ibarra:ibarra-arquillian:0.4.0-SNAPSHOT`, name `Ibarra :: Arquillian adapter`,
  package `io.vidocq.ibarra.arquillian`. It is in the root `<modules>` (default reactor). It is
  deployed like the other modules: do not set `maven.deploy.skip`, `maven.install.skip`,
  `maven.javadoc.skip`, `maven.source.skip` or `gpg.skip` in it.
- `ibarra-tck` stays unpublished, in the reactor only under `-Ptck`. It takes `ibarra-arquillian` at `test` scope.
- Keep these dependency versions: `org.jboss.arquillian.container:arquillian-container-spi:1.8.0.Final`
  and `org.jboss.shrinkwrap:shrinkwrap-impl-base:1.2.6`. Add only
  `org.jboss.arquillian.container:arquillian-container-impl-base:1.8.0.Final` at `test` scope.
- **No `module-info.java` in `ibarra-arquillian`**: it is test tooling on the class path, like
  `ibarra-maven-plugin`. Do not add an `Automatic-Module-Name`. What the adapter deploys stays
  strictly modular: named `tck.application`, named `tck.host`, `--limit-modules`. The server
  still refuses `java.compiler`, `io.vidocq.ibarra.compiler` and `io.vidocq.peano.compiler`.
  `-Dpeano.reflection=deny` is still always set.
- Generated names stay as they are: `tck.application`, `tck.host`, `tck.generated.*`, and the
  system property `ibarra.tck.work` (default `target/pages-deployments`).
- With no configuration property set, the adapter must not change behaviour: same descriptor
  `requires`/`exports`/`provides`, same `java` command line. The official `all` result must
  equal the "before" row (Task 1) in classes, tests, failures, errors and skipped.
- Put the licence header from `etc/license-header.txt` on every new `.java` file (the
  `host/` resources included). JUnit 6, no Mockito. English only. Say "Java Modules", never the abbreviation.
- Commits use `git commit -S -s`, Conventional Commits in English, and end with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and
  `Claude-Session: https://claude.ai/code/session_01JrpAgoGhwva5krc8qVqBqh`. One PR in Vidocq/ibarra.
  Do not touch the `feat-tagdependent-body` or `feat-xml-output-prolog` worktrees.
- SonarQube "Sonar way" applies to this PR (workspace `QUALITY.md`). Moved code counts as new
  main code: no new issue, and new-code coverage ≥ 80 %. `ibarra-arquillian` is a `compile`
  dependency of `ibarra-coverage`.

## Review Focus

1. **The `arquillian.xml` property names reach `PagesConfiguration`.** Arquillian fills the
   configuration through `MapObject.populate`, and a misnamed setter is ignored without any
   error. Test: Task 3 `arquillianPropertiesPopulateTheConfiguration`, extended in Task 4.
2. **The collection-table rewrite keeps the added `requires` and `opens`.** `deploy` rewrites
   the descriptor a third time, from the compiled `module-info.class`. Today that rewrite copies
   only requires, exports and provides. Test: Task 2 `readRoundTripsOpensQualifiedExportsAndProviderOrder`
   and Task 4 `openedPackageSurvivesCollectionTables`.
3. **A bad module name fails at `setup`, with the name in the message, before any process
   starts.** That covers a library absent from the test class path, a library that is a host
   module, and a `jdkModules` entry that is not a JDK module. Tests: Task 3
   `unknownOrHostLibraryModulesFailAtSetup`, Task 4 `nonJdkModuleFailsValidation`.
4. **Automatic modules (JDBC drivers) are runtime-only.** They are never passed to
   `PagePrecompiler`, which rejects automatic modules, yet `Class.forName` from application
   code still loads them. Test: Task 3 `automaticLibraryIsRuntimeOnlyAndVisibleToApplicationCode`.
5. **A filter or listener declared in `web.xml` whose class sits in a `WEB-INF/lib` jar gets a
   direct factory.** That is the Tags TCK's `jstltck-common.jar` with `JstlDbFilter` and
   `SQLContextListener`. Test: Task 3 `webXmlFilterFromAWarLibraryGetsADirectFactory`
   (a characterization test that pins today's behaviour).

## Decisions (from evidence)

Evidence sources:
- the Tags TCK `jakarta.tck:tags-tck:11.0.3` classes in
  `.../scratchpad/tck-research/central/x` (`javap -c -p` of `createDeployment`, and a scan of the
  constant pools of its 36 server-side classes);
- the sources of the same suite in `.../scratchpad/tags-tck-{sql,xml}/tck/src/main`;
- Bodoni's working IT (`bodoni/main/bodoni-it-ibarra/.../CorePagesIT.java` and its `host/`);
- Bodoni's `PLAN.md` (open question 4, TCK plan).

`tck-research/findings.md` named in the brief does not exist.

| Bodoni need | Evidence | Adapter today | Decision |
|---|---|---|---|
| JSTL implementation and its TLDs visible to pages without being in each WAR | WARs carry JSPs plus `jstltck-common.jar` only. Pages use the URIs `jakarta.tags.*` (475 core, 153 fmt, 128 sql, 76 xml, 64 functions uses), whose TLDs are in `bodoni-core` `META-INF/`. `TldListeners.catalog` scans only the modules reachable through the application descriptor's `requires`. | No. `modulePaths` keeps a fixed host whitelist, and the descriptor's `requires` are fixed | Hook **`applicationLibraries`**: each module joins the application layer. An explicit module is also `requires`d by `tck.application` and put on the build-side module path |
| `bodoni-peano` (`AccessorProvider`), JSTL API `jakarta.servlet.jsp.jstl` | `ConfigTag`, `ScopeCheckTag` and `ResultSetQueryTag` in the WARs reference `jakarta.servlet.jsp.jstl.core/sql`. Bodoni's IT puts both, with `bodoni-core`, on the application layer (`APPLICATION_LIBRARIES`) | No | Same hook |
| JDBC driver (H2) seen by the WAR's class loader | `TckDataSourceWrapper` does `Class.forName(driver).newInstance()` and `DriverManager.getConnection`. The driver name comes from `jstl.db.*` client properties sent as headers (`SqlUrlClient`). H2 is an automatic module (`com.h2database`) | No | Same hook, which accepts automatic modules. The layer has one loader, so application code loads them. They stay off the build-side path. The DDL and the `jstl.db.*` properties are Bodoni's |
| JDK modules `java.sql`, `java.naming`, locale data | The WAR classes reference `java.sql`, `javax.sql`, `java.util.logging` and `org.xml.sax`; jstl's `requires transitive java.sql` makes these readable. The server boot layer is `--limit-modules tck.host,io.vidocq.chappe.core`, whose closure has `java.xml` and `java.net.http` (Foy, Chappe) but not `java.sql` or `java.naming`. `bodoni-core` requires `java.naming`. `jdk.localedata` (CLDR for fr/sw/en_GB) is a provider no `requires` reaches, as Bodoni's IT `JDK_MODULES` shows | No | Hook **`jdkModules`**, added to `--limit-modules` and `--add-modules` |
| Resource bundles of the WAR | `AlgoResources*.properties` and the `ListResourceBundle` classes sit in `com.sun.ts.tests.jstl.common.resources`, inside `tck.application`. Bodoni's PLAN open question 4 (JDK 25): a bundle in an encapsulated package of a named application module is not found from the library, and is found once that package is `opens`ed | No (the descriptor has no `opens`) | Hook **`applicationOpens`**: unqualified `opens` of the listed packages, which must exist in the application |
| Server system properties (e.g. `peano.reflection`) | `-Dpeano.reflection=deny` is already fixed, and Bodoni runs under deny. The fmt/i18n clients send `Accept-Language`, and the pages set explicit zones (`positiveFDTimeZoneNullEmptyTest`: `fmt:setTimeZone value="EST"`). No server-default dependence was found. `user.timezone` in Bodoni's IT guards BOD-005 in that IT only | Yes (deny) | **No hook** (YAGNI). Add one when a failing `bodoni-tck` run shows the need |
| WAR libraries, filters, listeners | `jstltck-common.jar` holds classes, `META-INF/*.tld` and two `.properties`: no `module-info`, services, fragments or `@Web*` annotations. Its filters and listener are declared in each `web.xml` | Yes: `WarLibraries.assemble` runs before `ComponentFactories.generate` | No hook. Pinned by a characterization test (Review Focus 5) |

Further rulings:
- **Module name `ibarra-arquillian`.**
- **Hook validation.** Names are validated at `setup` against the same search path the adapter
  already reads: `jdk.module.path` plus `surefire.test.class.path`, or else `java.class.path`.
  Each class-path entry is probed alone with `ModuleFinder.of(entry)`. An entry that cannot be
  read as a module is skipped.
- **Host sources move to package-relative resources.** They go to
  `io/vidocq/ibarra/arquillian/host/`, loaded with `PagesContainer.class.getResourceAsStream("host/" + file)`.
  The old root path `/host/...` would be shadowed by a consumer's own test resource `host/`:
  Bodoni's IT already has one.
- **The adapter's documentation moves with it.** It goes to `ibarra-arquillian/README.md` and
  the Antora page `modules/arquillian-adapter.adoc`. The runner pages keep only the parts
  specific to the Pages TCK.
- **No companion branch.** The change only adds an artifact. `ibarra-tck` is unpublished, and
  nothing published changes.
- **Out of scope, for Bodoni's B5:**
  - the JSTL 1.0 URIs `http://java.sun.com/jstl/*_rt` (compat, sql, xml WARs) have no TLD in `bodoni-core`;
  - the TLV fixtures (`etu/tlv`) need Ibarra compiler TLV support;
  - the Tags TCK uses Arquillian 1.9.4.Final while the adapter uses container SPI 1.8.0.Final,
    so `bodoni-tck` aligns them through `dependencyManagement`.

---

### Task 1: Record the baseline, create `ibarra-arquillian`, relocate the adapter

**Files:**
- Move (`git mv`) from `ibarra-tck/src/test/java/io/vidocq/ibarra/tck/` to
  `ibarra-arquillian/src/main/java/io/vidocq/ibarra/arquillian/`: `PagesContainer.java`,
  `PagesExtension.java`, `PagesConfiguration.java`, `WarLibraries.java`, `BeanAccessors.java`,
  `CollectionAccessors.java`, `ComponentFactories.java`
- Move to `ibarra-arquillian/src/test/java/io/vidocq/ibarra/arquillian/`: `PagesContainerTest.java`,
  `WarLibrariesTest.java`, `BeanAccessorsTest.java`, `CollectionAccessorsTest.java`,
  `ComponentFactoriesTest.java`, and `fixtures/*` → `.../arquillian/fixtures/`
- Move `ibarra-tck/src/test/resources/host/module-info.java` and `host/host/Main.java` →
  `ibarra-arquillian/src/main/resources/io/vidocq/ibarra/arquillian/host/module-info.java` and `.../host/host/Main.java`
- Move `ibarra-tck/src/test/resources/META-INF/services/org.jboss.arquillian.core.spi.LoadableExtension`
  → `ibarra-arquillian/src/main/resources/META-INF/services/` (content `io.vidocq.ibarra.arquillian.PagesExtension`)
- Copy `ibarra-tck/src/test/resources/junit-platform.properties` → `ibarra-arquillian/src/test/resources/`
- Create: `ibarra-arquillian/pom.xml`
- Modify: `pom.xml` (module after `ibarra-foy`; `dependencyManagement` entry for `ibarra-arquillian`),
  `ibarra-tck/pom.xml`, `ibarra-coverage/pom.xml`, `ibarra-tck/BASELINE.md`
- Stays in `ibarra-tck`: `OfficialTckRunnerTest.java`, `arquillian.xml`, `junit-platform.properties`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - public `PagesContainer implements DeployableContainer<PagesConfiguration>`,
    `PagesConfiguration implements ContainerConfiguration`, `PagesExtension implements LoadableExtension`;
  - package-private `WarLibraries`, `BeanAccessors`, `CollectionAccessors`, `ComponentFactories`
    in `io.vidocq.ibarra.arquillian`;
  - the host sources as resources under `io/vidocq/ibarra/arquillian/host/`;
  - a `BASELINE.md` section "Adapter extraction check (2026-10-23)" with the "before" row.

- [ ] **Step 1: Run the official suite before any change.** On the untouched tree:
  `./run-official-tck-pages-4.0.sh --no-prepare all 2>&1 | tee "${TMPDIR:-/tmp}/ibarra-arquillian-before.log"`.
  Take the classes count and the final `Tests run: N, Failures: F, Errors: E, Skipped: S` of the
  official invocation. The 2026-10-17 baseline says 92 classes and 682/682, but record what
  this run measures.
- [ ] **Step 2: Snapshot the resolved test class path.**
  `./mvnw -ntp -q -Ptck,tck-official -pl ibarra-tck dependency:list -Dsort=true -DoutputFile="${TMPDIR:-/tmp}/ibarra-tck-deps-before.txt"`
- [ ] **Step 3: Relocate the files** with `git mv` as listed. Change the package declarations
  and imports from `io.vidocq.ibarra.tck` to `io.vidocq.ibarra.arquillian`. This includes
  `import static io.vidocq.ibarra.arquillian.BeanAccessors.*` and the test fixtures, whose
  package becomes `io.vidocq.ibarra.arquillian.fixtures`. Change the host lookup in
  `PagesContainer` from `getResourceAsStream("/host/" + file)` to `getResourceAsStream("host/" + file)`.
  Change nothing else.
- [ ] **Step 4: Write `ibarra-arquillian/pom.xml`** with parent `ibarra-parent` and
  description `Arquillian adapter that precompiles Jakarta Pages WARs and deploys them on modular Foy — test tooling, never shipped in an application`.
  - `compile` dependencies: `ibarra-compiler`, `ibarra-core`, `ibarra-foy` (`${project.version}`),
    `foy-core`, `peano-core`, `chappe-core` (`${chappe.version}`), `arquillian-container-spi`
    1.8.0.Final, `shrinkwrap-impl-base` 1.2.6.
  - `test` dependencies: `junit-jupiter`.
  - No `module-info.java`, and no surefire override (the module has no descriptor, so its tests
    run on the class path).
  - A header comment that states the class-path decision and points to the Reference page.
- [ ] **Step 5: Reduce `ibarra-tck/pom.xml`.** Its default dependencies become `ibarra-arquillian`
  (`test`) and `junit-jupiter` (`test`). Keep the `tck-official` profile, the
  `useModulePath=false` surefire setting and the skip properties unchanged. Add
  `ibarra-arquillian` (`compile`) to `ibarra-coverage/pom.xml`.
- [ ] **Step 6: Build.** `./mvnw -ntp install`. Expected: `ibarra-arquillian` runs 37 tests
  (21 `PagesContainerTest`, 5 `BeanAccessorsTest`, 3 `CollectionAccessorsTest`,
  4 `ComponentFactoriesTest`, 4 `WarLibrariesTest`), 0 failures. BUILD SUCCESS.
- [ ] **Step 7: Run the runner tests.** `./mvnw -ntp -Ptck -pl ibarra-tck clean test`.
  Expected: `Tests run: 2, Failures: 0` (`OfficialTckRunnerTest`).
- [ ] **Step 8: Compare the class path.** Repeat Step 2 into `ibarra-tck-deps-after.txt` and
  `diff` the two files. Expected: the only difference is the added
  `io.vidocq.ibarra:ibarra-arquillian:jar:0.4.0-SNAPSHOT:test`. If any `org.jboss.*`,
  `jakarta.*` or `io.vidocq.*` coordinate changes version, pin it in `ibarra-tck/pom.xml` with
  a direct `test` dependency at the "before" version, then diff again.
- [ ] **Step 9: Run the smoke.** `./run-official-tck-pages-4.0.sh --no-prepare --no-install smoke`.
  Expected: 18 tests, 0 failures, 0 errors.
- [ ] **Step 10: Record the "before" row.** In `ibarra-tck/BASELINE.md`, add the section
  "Adapter extraction check (2026-10-23)". Give the base commit `53f9414`, the command from
  Step 1, and a table with columns `Run | Commit | Classes | Tests | Failures | Errors | Skipped`.
  Fill the "before" row from Step 1. Task 6 adds the "after" row.
- [ ] **Step 11: Commit** `refactor(tck): move the Arquillian adapter into the published ibarra-arquillian module`
  (body: the class-path decision, unchanged behaviour, the "before" figures; trailers).

### Task 2: One application-descriptor model, named deployment steps

**Files:**
- Create: `ibarra-arquillian/src/main/java/io/vidocq/ibarra/arquillian/ApplicationDescriptor.java`
- Modify: `ibarra-arquillian/src/main/java/io/vidocq/ibarra/arquillian/PagesContainer.java`
- Test: `ibarra-arquillian/src/test/java/io/vidocq/ibarra/arquillian/ApplicationDescriptorTest.java`,
  `.../PagesContainerTest.java` (one new test)

**Interfaces:**
- Consumes: Task 1 package and classes.
- Produces:
  - `final class ApplicationDescriptor`, a mutable builder whose methods return `this`:
    - `static final String MODULE = "tck.application"`;
    - `static ApplicationDescriptor base()`: requires `java.desktop`, `jakarta.el`,
      `io.vidocq.ibarra.api`, `io.vidocq.peano.api`, `io.vidocq.foy.api`;
    - `static ApplicationDescriptor read(Path moduleInfoClass) throws IOException`: requires,
      with `static`/`transitive` and without `MANDATED`, exports with targets, opens with
      targets, provides with provider order kept;
    - `ApplicationDescriptor requires(String module)`;
    - `ApplicationDescriptor exportsTo(String packageName, String target)`;
    - `ApplicationDescriptor opens(String packageName)`;
    - `ApplicationDescriptor provides(String service, List<String> providers)`, which appends
      after the providers the service already has;
    - `String source()`.
  - In `PagesContainer`: `static List<String> hostCommand(Path java, List<Path> runtime, List<String> jdkModules, List<Path> applicationPath, Path web, String context)`.

- [ ] **Step 1: Write the failing tests** (`ApplicationDescriptorTest`):

```java
@Test void baseRequiresTheFiveRuntimeModules() {
    assertEquals("module tck.application { requires io.vidocq.foy.api; requires io.vidocq.ibarra.api; "
            + "requires io.vidocq.peano.api; requires jakarta.el; requires java.desktop; }",
            ApplicationDescriptor.base().source());
}
@Test void readRoundTripsOpensQualifiedExportsAndProviderOrder(@TempDir Path work) throws Exception {
    // javac-compile (ToolProvider, --patch-module) a descriptor for tck.application with
    // "requires transitive jakarta.el; requires static java.desktop; exports app.beans to jakarta.el;
    //  opens app.bundles; provides app.Service with app.B, app.A;" over classes app.Service, app.A, app.B,
    // app.beans.Bean and the resource app/bundles/m.properties
    assertEquals("module tck.application { requires transitive jakarta.el; requires static java.desktop; "
            + "exports app.beans to jakarta.el; opens app.bundles; provides app.Service with app.B,app.A; }",
            ApplicationDescriptor.read(classes.resolve("module-info.class")).source());
}
```

  In `PagesContainerTest`:

```java
@Test void hostCommandWithoutConfigurationIsTheHistoricalOne() {
    assertEquals(List.of("/jdk/bin/java", "-Dpeano.reflection=deny", "--module-path", "/r1" + File.pathSeparator + "/r2",
            "--limit-modules", "tck.host,io.vidocq.chappe.core", "--add-modules", "io.vidocq.chappe.core",
            "-m", "tck.host/host.Main", "/w/application", "/w/web", "/ctx"),
        PagesContainer.hostCommand(Path.of("/jdk/bin/java"), List.of(Path.of("/r1"), Path.of("/r2")), List.of(),
            List.of(Path.of("/w/application")), Path.of("/w/web"), "/ctx"));
}
```

- [ ] **Step 2: Check the tests fail.**
  `./mvnw -ntp -pl ibarra-arquillian test -Dtest='ApplicationDescriptorTest,PagesContainerTest#hostCommandWithoutConfigurationIsTheHistoricalOne'`.
  Expected: compilation failure (`ApplicationDescriptor` and `hostCommand` do not exist).
- [ ] **Step 3: Implement `ApplicationDescriptor`.** `source()` emits, in this order:
  - requires, sorted by name, written `requires [static ][transitive ]name;`;
  - exports, sorted by package, `exports p[ to t1,t2];` with the targets sorted;
  - opens, sorted, in the same form;
  - provides, sorted by service, `provides S with P1,P2;` with providers in insertion order.

  Every statement is followed by one space, inside `module tck.application { ... }`.
  `read` uses `java.lang.module.ModuleDescriptor.read`.
- [ ] **Step 4: Rewire `PagesContainer.deploy`.** The three descriptor writes become `base()`
  plus WebComponent `provides`; then `exportsTo(p, "jakarta.el")` and AccessorProvider
  `provides`; then `read(module-info.class)` plus the collection providers. Split `deploy`
  into private steps (extract the archive, reject unadapted metadata, generate components,
  compile the descriptor, precompile pages, build the host, launch and await READY), each
  with cognitive complexity ≤ 15. The launch step builds its `ProcessBuilder` from
  `hostCommand(...)`, with `jdkModules` = `List.of()` and `applicationPath` =
  `List.of(classes)`. Write one statement per line. Put a comment in any intentionally empty
  `catch`. Restore the interrupt flag where `InterruptedException` is caught. Turn repeated
  literals into constants. Do not change messages, timeouts, file names or the order of
  generated providers.
- [ ] **Step 5: Run the module tests.** `./mvnw -ntp -pl ibarra-arquillian install`.
  Expected: 40 tests, 0 failures.
- [ ] **Step 6: Run the smoke.** `./run-official-tck-pages-4.0.sh --no-prepare smoke`.
  Expected: 18/18 passing.
- [ ] **Step 7: Commit** `refactor(arquillian): model the application descriptor and split the deployment steps`.

### Task 3: `applicationLibraries` — library modules deployed with the application

**Files:**
- Create: `ibarra-arquillian/src/main/java/io/vidocq/ibarra/arquillian/ApplicationLibraries.java`
- Modify: `PagesConfiguration.java`, `PagesContainer.java`,
  `src/main/resources/io/vidocq/ibarra/arquillian/host/host/Main.java`, `ibarra-arquillian/pom.xml`
  (test dependency `arquillian-container-impl-base` 1.8.0.Final)
- Create (test): `.../arquillian/FixtureModules.java`, `.../arquillian/ApplicationLibrariesTest.java`,
  `.../arquillian/fixtures/LibraryFilter.java`

**Interfaces:**
- Consumes: `ApplicationDescriptor.requires`, `PagesContainer.hostCommand` (Task 2).
- Produces:
  - `PagesConfiguration`:
    - `public String getApplicationLibraries()` and `public void setApplicationLibraries(String moduleNames)`:
      comma-separated, blanks trimmed, default `""`;
    - package-private `List<String> applicationLibraries()`;
    - `public void validate()`, which throws `org.jboss.arquillian.container.spi.ConfigurationException`
      for an invalid module name (`SourceVersion.isName`) or a duplicate.
  - `final class ApplicationLibraries`:
    - `record Library(String name, Path location, boolean automatic)`;
    - `static List<Library> resolve(List<String> names, List<Path> searchPath)`, which throws
      `ConfigurationException` when a name is not found ("not found on the test class path")
      or is a host module ("is a host module"). Host modules are the names `modulePaths`
      already keeps, plus `io.vidocq.chappe.*` and `io.vidocq.ibarra.compiler`.
  - `PagesContainer`:
    - `public PagesContainer()` reads the search path from the system properties;
    - package-private `PagesContainer(String searchPath)`, for tests;
    - `static List<Path> searchPath(String path)`;
    - `setup` resolves the libraries.
  - Host `Main`: `args[0]` becomes a `File.pathSeparator` list: the application classes first,
    then the library locations.
  - Test helper `FixtureModules`:
    - `static Path explicitModule(Path dir, String name, String descriptorBody, Map<String,String> sources, Map<String,String> resources)`;
    - `static Path automaticModule(Path dir, String jarFileName, Map<String,String> sources, Map<String,String> resources)`.

    Both compile with `ToolProvider` against the modular entries of the test class path and
    write a jar.

- [ ] **Step 1: Write the failing tests** (`ApplicationLibrariesTest`; each test builds its
  fixtures in a `@TempDir` and uses `new PagesContainer(testClassPath + File.pathSeparator + jar)`):

```java
@Test void explicitLibraryTagsAreDiscoveredOutsideTheWar() // module fixture.tags: requires jakarta.servlet.jsp;
    // exports fixture.tags; tag fixture.tags.ModuleTag (SimpleTagSupport) writes getClass().getModule().getName();
    // META-INF/fixture.tld uri urn:fixture, tag "module". applicationLibraries=fixture.tags.
    // WAR index.jsp: <%@ taglib prefix="f" uri="urn:fixture" %><f:module/>
    // → assertEquals(200, status); assertEquals("fixture.tags", body)
@Test void automaticLibraryIsRuntimeOnlyAndVisibleToApplicationCode() // fixture-driver.jar (automatic name
    // fixture.driver) with fixture.driver.Probe; applicationLibraries=fixture.driver;
    // index.jsp: <%= Class.forName("fixture.driver.Probe").getModule().getName() %>
    // → 200, "fixture.driver" (deployment succeeds, so the precompiler never saw the automatic module)
@Test void webXmlFilterFromAWarLibraryGetsADirectFactory() // lib.jar with fixtures.LibraryFilter (writes "lib|"
    // then chains), web.xml filter + mapping /*, index.jsp "page" → 200, "lib|page"
@Test void unknownOrHostLibraryModulesFailAtSetup()
    // "missing.module" → ConfigurationException message contains "missing.module" and "not found";
    // "io.vidocq.foy.core" → contains "io.vidocq.foy.core" and "host module";
    // "bad name!" → validate() throws ConfigurationException
@Test void arquillianPropertiesPopulateTheConfiguration() throws Exception {
    var configuration = new PagesConfiguration();
    org.jboss.arquillian.container.impl.MapObject.populate(configuration, Map.of("applicationLibraries", "a.b, c.d"));
    assertEquals(List.of("a.b", "c.d"), configuration.applicationLibraries());
    assertEquals(List.of(), new PagesConfiguration().applicationLibraries());
}
```

- [ ] **Step 2: Check the tests fail.** `./mvnw -ntp -pl ibarra-arquillian test -Dtest=ApplicationLibrariesTest`.
  Expected: compilation failure (`setApplicationLibraries`, `PagesContainer(String)`).
  Once it compiles: `webXmlFilterFromAWarLibraryGetsADirectFactory` passes (characterization)
  and the four others fail.
- [ ] **Step 3: Implement.**
  - `ApplicationLibraries.resolve` probes each search-path entry alone with `ModuleFinder.of(entry)`,
    skipping entries that throw `FindException`, and matches on the descriptor name.
  - In `deploy`:
    - the build-side module path (descriptor compile, `BeanAccessors`/`CollectionAccessors`,
      `precompileDeployment`) = `modulePaths(true)` plus the explicit libraries' locations;
    - `ApplicationDescriptor.requires(name)` for every explicit library;
    - `hostCommand(..., applicationPath = [classes] + every library location, ...)`.
  - The runtime boot path stays `modulePaths(false)`.
  - Host `Main` builds its application finder from every entry of the `args[0]` list, as
    Bodoni's IT host does (`bodoni-it-ibarra/src/test/resources/host/host/Main.java`).
- [ ] **Step 4: Run the module tests.** `./mvnw -ntp -pl ibarra-arquillian install`.
  Expected: 45 tests, 0 failures.
- [ ] **Step 5: Run the smoke.** `./run-official-tck-pages-4.0.sh --no-prepare --no-install smoke`.
  Expected: 18/18 passing.
- [ ] **Step 6: Commit** `feat(arquillian): deploy configured library modules in the application layer`.

### Task 4: `jdkModules` and `applicationOpens`

**Files:**
- Modify: `PagesConfiguration.java`, `PagesContainer.java`
- Test: `.../arquillian/ApplicationLibrariesTest.java` (extend `arquillianPropertiesPopulateTheConfiguration`),
  create `.../arquillian/ApplicationModuleOptionsTest.java`

**Interfaces:**
- Consumes: `ApplicationDescriptor.opens`, `hostCommand(..., jdkModules, ...)`, `FixtureModules`,
  `PagesContainer(String)`, `applicationLibraries` (Tasks 2–3).
- Produces:
  - `PagesConfiguration`:
    - `get/setJdkModules(String)` and `List<String> jdkModules()`; `validate` requires every
      name to be in `ModuleFinder.ofSystem()` ("is not a JDK module");
    - `get/setApplicationOpens(String)` and `List<String> applicationOpens()`; `validate`
      requires valid package names.
  - `deploy`:
    - appends the `jdkModules` to `--limit-modules` and `--add-modules`, comma-joined and
      without a trailing comma when empty;
    - adds `opens p;` for each configured package, after checking that `classes/<p as path>`
      directly holds at least one regular file (a subpackage alone does not count). Otherwise it throws
      `IOException("Configured package to open is absent from the application: " + p)`,
      wrapped in the usual `DeploymentException`.

- [ ] **Step 1: Write the failing tests** (`ApplicationModuleOptionsTest`):

```java
@Test void jdkModulesJoinTheServerBootLayer() // module fixture.naming requires java.naming; its tag writes
    // javax.naming.Context.class.getModule().getName(); applicationLibraries=fixture.naming.
    // Without jdkModules: DeploymentException, cause message contains "java.naming" (server log).
    // With jdkModules=java.naming on a new container: 200, "java.naming".
@Test void openedPackageSurvivesCollectionTables() // WAR class fixture.bundles.Messages + resource
    // fixture/bundles/messages.properties; applicationOpens=fixture.bundles;
    // index.jsp: <% java.util.List<String> items = new java.util.ArrayList<>(); %><%= getClass().getModule().isOpen("fixture.bundles") %>
    // → 200, "true"  (the ArrayList allocation forces the third, collection-table descriptor rewrite)
@Test void absentPackageToOpenFailsTheDeploymentAndLeavesTheAdapterReusable()
    // applicationOpens=fixture.absent → DeploymentException, cause message contains "fixture.absent";
    // the same container then deploys a plain "ready" WAR
@Test void nonJdkModuleFailsValidation()
    // setJdkModules("io.vidocq.foy.core"); validate() → ConfigurationException containing "io.vidocq.foy.core"
```

  In `arquillianPropertiesPopulateTheConfiguration`, add
  `"jdkModules", "java.sql, java.naming"` and `"applicationOpens", "p.q"`, and assert them.
  In `PagesContainerTest`, add the test `hostCommandAppendsJdkModules`: the `jdkModules`
  `List.of("java.naming", "jdk.localedata")` give the `--limit-modules` value
  `"tck.host,io.vidocq.chappe.core,java.naming,jdk.localedata"` and the `--add-modules` value
  `"io.vidocq.chappe.core,java.naming,jdk.localedata"`.
- [ ] **Step 2: Check the tests fail.**
  `./mvnw -ntp -pl ibarra-arquillian test -Dtest='ApplicationModuleOptionsTest,ApplicationLibrariesTest,PagesContainerTest#hostCommand*'`.
  Expected: compilation failure (the setters do not exist).
- [ ] **Step 3: Implement** the setters, the validation, the `opens` check and the command-line
  join described under Interfaces.
- [ ] **Step 4: Run the module tests.** `./mvnw -ntp -pl ibarra-arquillian install`.
  Expected: 50 tests, 0 failures.
- [ ] **Step 5: Run the smoke.** `./run-official-tck-pages-4.0.sh --no-prepare --no-install smoke`.
  Expected: 18/18 passing.
- [ ] **Step 6: Commit** `feat(arquillian): add JDK boot modules and opened application packages`.

### Task 5: Documentation

**Files:**
- Create: `ibarra-arquillian/README.md`, `docs/en/modules/ROOT/pages/modules/arquillian-adapter.adoc`
- Modify: `ibarra-tck/README.md`, `docs/en/modules/ROOT/pages/modules/official-tck-runner.adoc`,
  `docs/en/modules/ROOT/nav.adoc`, `docs/en/modules/ROOT/pages/reference.adoc`,
  `docs/en/modules/ROOT/pages/tck.adoc`, `README.md`, `AGENTS.md`, `PLAN.md`

**Interfaces:**
- Consumes: the property names and messages of Tasks 3–4.
- Produces: the pages that other docs link to (`modules/arquillian-adapter.adoc`, `ibarra-arquillian/README.md`).

- [ ] **Step 1: Write `modules/arquillian-adapter.adoc`** ("Arquillian adapter"; `:description:`
  Published Arquillian adapter that precompiles Jakarta Pages WARs and runs them on modular
  Foy — test tooling). It contains:
  - The coordinates, and a `WARNING`: test tooling, taken at `test` scope by TCK runners,
    never shipped in an application.
  - **Java Modules**: the jar has no `module-info`, because Arquillian, ShrinkWrap and the
    official TCK clients run on the class path. This is the same build-tooling exception as the
    Maven plugin. The deployed application (`tck.application`) and server (`tck.host`) are
    named modules under `--limit-modules`, without compilers.
  - **Test tooling dependencies**: a table with `arquillian-container-spi` 1.8.0.Final
    (`DeployableContainer` SPI), `shrinkwrap-impl-base` 1.2.6 (WAR model), `ibarra-compiler`
    (build-side precompilation), `foy-core` (web.xml parser and server), and `ibarra-core`,
    `ibarra-foy`, `peano-core`, `chappe-core` (server modules). These are the dependencies the
    workspace rule requires to be named on this page.
  - **Configuration**: an `arquillian.xml` snippet, and a table of the three properties
    (meaning, default empty, validation, failure message) taken from the Interfaces of Tasks 3–4.
  - **Using it from another project**: the test dependency, surefire `useModulePath=false`,
    and the Bodoni-shaped example
    `applicationLibraries=io.vidocq.bodoni.core,io.vidocq.bodoni.peano,jakarta.servlet.jsp.jstl,com.h2database`,
    `jdkModules=java.sql,java.naming,jdk.localedata`,
    `applicationOpens=com.sun.ts.tests.jstl.common.resources`.
  - The sections moved verbatim from `official-tck-runner.adoc`: "Runtime isolation"
    (generalised: "no added exports or opens unless `applicationOpens` names them"),
    "Descriptor component factories", "Legacy WAR libraries", "Private bean property tables"
    and "Collection method tables".
- [ ] **Step 2: Update the Pages-specific pages.**
  - `official-tck-runner.adoc` keeps the script, the official dependencies, the signature
    check and the artifact preparation. It links to the adapter page and states that
    `ibarra-tck` takes `ibarra-arquillian` at test scope.
  - `nav.adoc`: add `** xref:modules/arquillian-adapter.adoc[Arquillian adapter]` after the
    runner entry.
  - `reference.adoc`: add the Artefacts row
    `| io.vidocq.ibarra:ibarra-arquillian | — (test class path) | xref:modules/arquillian-adapter.adoc[Arquillian adapter], test tooling only`.
  - `tck.adoc` § Runner: "The adapter is the published `ibarra-arquillian` module".
- [ ] **Step 3: Update the Markdown files.**
  - Create `ibarra-arquillian/README.md`: purpose, a configuration table, and the adapter
    sections moved from `ibarra-tck/README.md` ("Deployment model and limits", "Private bean
    property access", "Collection method access" and the closing diagnostics paragraph).
  - `ibarra-tck/README.md`: link to that file, and fix the sentence about "twenty-six adapter
    regression tests". The script's `clean install` now runs the adapter tests, and
    `-Ptck -pl ibarra-tck` runs the runner tests.
  - `README.md`: add a module-table row for `ibarra-arquillian` (published Arquillian adapter,
    test tooling, default reactor), and the command `./mvnw -pl ibarra-arquillian test` for
    the adapter regression tests.
  - `AGENTS.md`:
    - Architecture block: `ibarra-arquillian ← Published Arquillian adapter (test tooling, class path), reused by bodoni-tck`;
    - Prerequisites: point at `ibarra-arquillian/README.md`;
    - the sentence "a plain `./mvnw install` neither builds nor downloads anything TCK-related"
      becomes: the default build adds the adapter's Arquillian/ShrinkWrap tooling but never the
      official TCK;
    - Constraint 10: the adapter is the second class-path exception (test tooling).
  - `PLAN.md`: add a row `ibarra-arquillian | — (class path) | test tooling, published | ...`
    to the Java Modules layout table, fix the `ibarra-tck` row ("Arquillian adapter later"),
    and add one line to the TCK plan bullet (the adapter is published for Bodoni, and the three
    hooks).
- [ ] **Step 4: Check for stale text.**
  `git grep -n -e 'io.vidocq.ibarra.tck.PagesExtension' -e 'ibarra-tck/src/test/java' -e 'Arquillian adapter later' -e 'twenty-six'`.
  Expected: no output. Then check that every `xref:modules/arquillian-adapter.adoc` target exists.
- [ ] **Step 5: Commit** `docs: document the ibarra-arquillian test-tooling module and its configuration`.

### Task 6: After-run, SonarQube, pull request

**Files:**
- Modify: `ibarra-tck/BASELINE.md`, plus any class flagged by SonarQube

**Interfaces:**
- Consumes: everything above.
- Produces: the PR and the "after" row.

- [ ] **Step 1: Check coverage.** `./mvnw -ntp -Pquality verify`. Expected: BUILD SUCCESS, and
  `ibarra-coverage/target/site/jacoco-aggregate/index.html` lists `io.vidocq.ibarra.arquillian`
  with line coverage ≥ 80 %.
- [ ] **Step 2: Open the PR.** Push `feat/arquillian-adapter` and open one PR in Vidocq/ibarra.
  The description gives the decisions table, the Java Modules ruling, the "before" figures and
  the new test-tooling dependencies (the workspace rule asks that they be justified in the PR).
  Ask the maintainer if pushing needs their credentials.
- [ ] **Step 3: Clear the SonarQube findings.** Read the PR's SonarQube comment and fix every
  new issue. For each batch of fixes: run `./mvnw -ntp -pl ibarra-arquillian install` (all
  green), then the smoke, then commit
  `refactor(arquillian): clear the SonarQube findings of the moved adapter` and push. An issue
  that is not a defect is resolved in SonarQube with a reason, never by adding an exclusion
  without the maintainer's agreement. Check the OSV/Trivy results for the new published
  dependencies.
- [ ] **Step 4: Run the official suite after the change.**
  `./run-official-tck-pages-4.0.sh --no-prepare all 2>&1 | tee "${TMPDIR:-/tmp}/ibarra-arquillian-after.log"`.
  Expected: the same classes, tests, failures, errors and skipped as the "before" row. If they
  differ, stop: diff the two Surefire report sets and log any regression with `/log-bug`
  before going further.
- [ ] **Step 5: Record the "after" row.** Add it to the `BASELINE.md` section with the head
  commit, then commit `docs(tck): record the official Pages run after the adapter extraction`
  and push. Then post the before/after numbers on the PR. Do not merge before the SonarQube
  comment is green.
