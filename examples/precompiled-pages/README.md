# Precompiled pages example

This independent application module requires only `ibarra-api`, exports no
packages, and uses the `ibarra-maven-plugin:precompile` goal at `process-classes`.
The generated page index is registered in the compiled Java module descriptor.
The application JAR contains compiled pages, not the compiler or generated Java.
Its private application package also contains a SimpleTag handler described by
WEB-INF/example.tld. The page invokes it through direct construction/setters and
named scalar/fragment attributes and an explicit body; no package exports or opens
are needed.

Run from the Ibarra checkout root with Java 25:

```sh
./mvnw -ntp install
./mvnw -ntp -f examples/precompiled-pages/pom.xml clean package
jar --describe-module --file examples/precompiled-pages/target/ibarra-precompiled-pages-example-0.4.0-SNAPSHOT.jar
```

The generator's application-JAR test proves discovery and rendering against
servlet doubles with compiler modules excluded. Deploying this example through
Foy uses the application module layer through the binding described in the
Antora Foy reference. Immediate EL compiles through Peano into the application
JAR. The application explicitly requires peano-api; runtime deployment supplies
an ExpressionFactory provider. Packaging never enables interpreter preview.
