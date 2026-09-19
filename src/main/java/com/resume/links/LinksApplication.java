// Why this file exists: the program's entry point. `@SpringBootApplication` turns on Spring's
// configuration: it scans this package (and everything under it) for components, wires them
// together with dependency injection, and starts an embedded web server. Anything more
// interesting belongs in the sub-packages.
//
// Ten things that are different from JS/TS, all of which show up in this codebase:
//
//  1. COMPILED TO BYTECODE, RUN ON THE JVM. `javac` turns source into .class files, packaged into
//     a jar; the Java Virtual Machine runs them and compiles hot code to machine code as it goes
//     (JIT). Type errors stop the build. Startup is slower than Go or Node, and the process is
//     heavier, in exchange for very fast steady-state performance and mature tooling.
//  2. STATICALLY TYPED, WITH ERASED GENERICS. `List<String>` is checked at compile time, but the
//     type argument is erased at runtime (you can't ask a List what it holds). `var` infers a
//     local's type; it is still static. There is no `any`: `Object` is the top type.
//  3. CLASSES ARE THE UNIT, ONE PUBLIC TYPE PER FILE. A file `Foo.java` holds `public class Foo`,
//     inside a `package` that mirrors the folder path. Members are `public`, `private`,
//     `protected` or package-private (the default: visible only in the same package). `final`
//     means "can't be reassigned / overridden"; records, enums, interfaces and `sealed` types
//     are all real runtime types, not erased like TypeScript's.
//  4. NULL IS EVERYWHERE, AND IT THROWS. Any reference can be `null`, and using it throws a
//     NullPointerException at runtime; the compiler does NOT track it (unlike C# or strict TS).
//     `Optional<T>` is the convention for "might be absent" in return types. There is no
//     `undefined`.
//  5. `==` COMPARES REFERENCES, `.equals()` COMPARES VALUES. `a == b` on two objects (strings
//     included) asks "are they the same object?", not "are they equal". Always use `.equals()`
//     (or `Objects.equals`) for values. Records generate a value-based `equals` for you.
//  6. CHECKED EXCEPTIONS EXIST. Some exceptions (IOException...) must be declared or caught or the
//     code won't compile; others (RuntimeException and its subclasses) needn't. Modern code
//     leans on unchecked ones, and Spring turns checked database exceptions into unchecked ones.
//  7. ANNOTATIONS DRIVE THE FRAMEWORK. `@RestController`, `@Transactional`, `@Entity`: metadata
//     that Spring reads at startup (via reflection) to build objects, wire dependencies and wrap
//     methods in proxies. There is no wiring code in `main`; you mostly declare intent.
//  8. THREADS, NOT AN EVENT LOOP. A request runs on a thread and simply BLOCKS while waiting on
//     the database: no async/await, no promises. To keep that cheap the app uses Java 21's
//     virtual threads (see application.properties), so a blocked request costs almost nothing.
//  9. STREAMS AND COLLECTIONS. `list.stream().map(...).toList()` is the `.map` chain: lazy until
//     the terminal call. Immutable collections are `List.of(...)`; `Map.of(...)`.
// 10. MAVEN, NOT NPM. `pom.xml` declares dependencies (with a parent "BOM" that pins compatible
//     versions of everything), `mvn package` builds one runnable "fat jar" containing the app AND
//     its dependencies, and `java -jar app.jar` starts it. Text blocks (`"""`) are multi-line
// strings.
package com.resume.links;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// JS/TS vs Java: a class-level annotation is a decorator applied at compile time and read by
// the framework at startup. @ConfigurationPropertiesScan finds the settings classes (see
// config/LinkProperties) and registers them as beans.
@SpringBootApplication
@ConfigurationPropertiesScan
public class LinksApplication {

  public static void main(String[] args) {
    SpringApplication.run(LinksApplication.class, args);
  }
}
