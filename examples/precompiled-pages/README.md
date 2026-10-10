# Precompiled pages example

This independent application module requires only `ibarra-api`, exports no
packages, and uses the `ibarra-maven-plugin:precompile` goal at `process-classes`.
The generated page index is registered in the compiled Java module descriptor.
The application JAR contains compiled pages, not the compiler or generated Java.

Run from the Ibarra checkout root with Java 25:

```sh
./mvnw -ntp install
./mvnw -ntp -f examples/precompiled-pages/pom.xml clean package
jar --describe-module --file examples/precompiled-pages/target/ibarra-precompiled-pages-example-0.4.0-SNAPSHOT.jar
```

The generator's application-JAR test proves discovery and rendering against
servlet doubles with compiler modules excluded. Deploying this example through
Foy remains milestone I5. EL build-time compilation still awaits Peano; packaging
does not enable the startup interpretation preview.
