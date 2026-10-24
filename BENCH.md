# BENCH.md — Ibarra

JMH performance numbers. Entry format: `date · hardware/JVM · exact command ·
raw results · delta vs previous run`. No performance numbers elsewhere (README, commit)
without a corresponding entry here.

## 2026-10-25 · Page resolver chain: conversion skips the resolvers that cannot convert (Vidocq/peano#63)

- **Hardware and OS:** Apple M5 Max, 18 cores, 128 GB RAM; Darwin 27.0.0 arm64.
- **JVM:** Temurin 25+36-LTS, default flags; JMH 1.37.
- **Code under test:**
  - **Before:** ibarra `589b5c0` (`main`).
  - **After:** the same commit with `PageResolverChain` (branch `perf/conversion-chain`).
  - **Common to both:** the `0.4.0-SNAPSHOT` jars of bodoni and of peano `df3b997`. Only
    the `ibarra-core` jar changes between the two runs.
- **What is measured:** the page and harness of Vidocq/bodoni `BENCH.md` BENCH-20261024-01,
  variant A only.
  - **The page:** `<c:forEach items="${items}" var="it"><c:if test="${it.even}"><c:out
    value="${it.name}"/></c:if></c:forEach>`. Ibarra's `PageGenerator` emits it with
    Bodoni's `c.tld`, and Peano compiles its EL.
  - **The data:** a `List` of N public beans.
  - **One operation** is one `service()` call through `IbarraJspFactory` with servlet doubles.
  - **The harness** is the uncommitted spike of that entry. The `ibarra-bench` module is
    milestone I9.
- **Exact command**, run for each state with its class path:
  ```bash
  java -cp target/classes:pages:aux-classes:<spike class path> org.openjdk.jmh.Main bench.PageBench \
      -p variant=A -f 3 -wi 5 -w 1s -i 5 -r 1s -prof gc
  ```
- **Raw results** (avgt, one thread, ± 99.9 % error; decimal comma from the workstation
  locale):
  ```text
  before
  PageBench.page                       10  A  avgt  15    2875,030 ±   51,145  ns/op
  PageBench.page:gc.alloc.rate.norm    10  A  avgt  15   28048,064 ±    0,181   B/op
  PageBench.page                     1000  A  avgt  15  183338,488 ± 1725,646  ns/op
  PageBench.page:gc.alloc.rate.norm  1000  A  avgt  15   99569,266 ±    0,016   B/op
  after
  PageBench.page                       10  A  avgt  15    2546,941 ±   13,701  ns/op
  PageBench.page:gc.alloc.rate.norm    10  A  avgt  15   28432,018 ±    0,001   B/op
  PageBench.page                     1000  A  avgt  15  146245,758 ± 1049,835  ns/op
  PageBench.page:gc.alloc.rate.norm  1000  A  avgt  15   99713,011 ±    0,011   B/op
  ```
- **Delta vs before:**
  - **Time:** −11.4 % for N=10 and −20.2 % for N=1000. That is about 183 → 146 ns per item.
  - **Allocation:** +384 B/op for N=10 and +144 B/op for N=1000, a constant cost per
    request: the two arrays of the chain. It does not grow with N.
- **Delta vs previous run:** first entry in this file. Before is the state that bodoni
  BENCH-20261024-01 measured: 177,248 ns/op there and 183,338 ns/op here for N=1000, a 3.4 %
  spread on an uncontrolled workstation.
- **Notes:**
  - **Where the time went.** A JFR profile of the before state (`-prof jfr`) put 75 % of the
    page's samples under `ELContext.convertToType`, called from
    `CompiledValueExpression.getValue`, out of 168 samples. The profile was taken at N=1000,
    without `-XX:+DebugNonSafepoints`.
    - **The cause:** the page chain is a `CompositeELResolver` of 17 resolvers, plus the 3 of
      Peano's provider composite, and conversion asked every one of them on every
      evaluation.
    - **What it was not:** the property reads and `ExpressionFactory.coerceToType` stayed
      under 1 % each.
  - **Peano-side alternatives**, measured on top of this change with the same page, 2 or 3
    forks:
    - **Conversion through `GeneratedAccessorELResolver` only:** Peano's provider composite
      asked only that resolver to convert. This gave 143,777 vs 144,916 ns/op, no gain.
    - **Early return:** `GeneratedAccessorELResolver.convertToType` returned before its
      context binding when no table and no lambda applied. This gave 141,287 ± 1,485 vs
      143,286 ± 1,482 ns/op, −1.4 %, within the noise budget.

    Neither was kept.
  - **Spec faithfulness:** conversion to `Object` cannot be skipped. `OptionalELResolver`
    unwraps a present `Optional` there, and an application resolver may convert. Only the
    resolvers that keep `ELResolver`'s default `convertToType`, which resolves nothing, are
    skipped. `PageResolverChainTest` checks that default by reflection on every skipped
    resolver.
