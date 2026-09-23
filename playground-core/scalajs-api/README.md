# Scala.js adapter API

This standalone sbt project supplies JavaScript-compatible execution data, `ExecutionBackend`, and `JsonRpcBackend` for compiler bindings written in Scala.js. It depends only on the Scala.js standard library in production. It has no dependency on a compiler, the TypeScript IDE, Monaco, or LSP.

The IDE does not depend on this library. A language binding may use it to return objects matching the IDE's neutral worker contracts, or may implement those contracts directly in another language.

Build and test this directory with Java 21 or newer and an sbt launcher:

```sh
sbt test
```

The build pins Scala 3.8.4 and Scala.js 1.22.0. A consuming compiler build can add this project with `dependsOn(RootProject(file(pathToApi)))`, using a path relative to that compiler build. The compiler binding owns JavaScript exports and TypeScript declarations. The API installs no application entry point or global export.

Public Scala doc comments describe the object fields, optional values, output streaming, JSON bridge, failures, and ownership. The [formal Scala.js API](../docs/playground-scalajs-api.md) provides matching definitions and worker integration examples.
