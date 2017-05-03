# Airframe  [![Gitter Chat][gitter-badge]][gitter-link] [![Build Status](https://travis-ci.org/wvlet/airframe.svg?branch=master)](https://travis-ci.org/wvlet/airframe) [![Latest version](https://index.scala-lang.org/wvlet/airframe/airframe/latest.svg?color=orange)](https://index.scala-lang.org/wvlet/airframe) [![codecov](https://codecov.io/gh/wvlet/airframe/branch/master/graph/badge.svg)](https://codecov.io/gh/wvlet/airframe) [![Scala.js](https://www.scala-js.org/assets/badges/scalajs-0.6.15.svg)](https://www.scala-js.org)

[circleci-badge]: https://circleci.com/gh/wvlet/airframe.svg?style=svg
[circleci-link]: https://circleci.com/gh/wvlet/airframe
[gitter-badge]: https://badges.gitter.im/Join%20Chat.svg
[gitter-link]: https://gitter.im/wvlet/wvlet?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge
[coverall-badge]: https://coveralls.io/repos/github/wvlet/airframe/badge.svg?branch=master
[coverall-link]: https://coveralls.io/github/wvlet/airframe?branch=master

Airframe is a dependency injection (DI) library tailored to Scala. While Google's [Guice](https://github.com/google/guice) is designed for injecting Java objects (e.g., using constructors or providers), Airframe redesigned it for Scala traits so that we can mix-in traits that have several object dependencies.

- [Documentation](http://wvlet.org/airframe/docs/)
- [Use Cases](http://wvlet.org/airframe/docs/use-cases.html)

Airframe can be used in three steps:
- ***Bind***: Inject necessary classes with `bind[X]`:
```scala
import wvlet.airframe._

trait App {
  val x = bind[X]
  val y = bind[Y]
  val z = bind[Z]
  // Do something with X, Y, and Z
}
```
- ***Design***: Describe how to provide object instances:
```scala
val design : Design =
   newDesign
     .bind[X].toInstance(new X)  // Bind type X to a concrete instance
     .bind[Y].toSingleton        // Bind type Y to a singleton object
     .bind[Z].to[ZImpl]          // Bind type Z to an instance of ZImpl

// Note that *Design* is *immutable*, so you can safely reuse and extend it by adding more bindings.     
```

- ***Build***: Create a concrete instance:
```scala
val session = design.newSession
val app : App = session.build[App]
```

Airframe builds an instance of `App` based on the binding rules specified in the *Design* object.
`Session` manages the lifecycle of objects generated by Airframe. For example, singleton objects that are instantiated within a Session will be discarded when `Session.shutdown` is called.

The major advantages of Airframe include:
- Simple usage. Just `import wvlet.airframe._` and do the above three steps to enjoy DI in Scala!
- *Design* remembers how to build complex objects on your behalf.
  - For example, you can avoid code duplications in your test and production codes. Compare writing `new App(new X, new Y(...), new Z(...), ...)` every time and just calling `session.build[App]`.
  - When writing application codes, you only need to care about how to ***use*** objects, rather than how to ***provide*** them. *Design* already knows how to provide objects to your class.
- You can enjoy the flexibility of Scala traits and dependency injection (DI) at the same time.
  - Mixing traits is far easier than calling object constructors. This is because traits can be combined in an arbitrary order. So you no longer need to remember the order of the constructor arguments.
- Scala macro based binding generation.
- Scala 2.11, 2.12, Scala.js support.

# Usage

[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.wvlet/airframe_2.11/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.wvlet/airframe_2.11)

- [Release Notes](http://wvlet.org/airframe/docs/release-notes.html)

**build.sbt**
```
libraryDependencies += "org.wvlet" %% "airframe" % "(version)"

# For Scala.js (supported since airframe 0.12)
libraryDependencies += "org.wvlet" %%% "airframe" % "(version)"
```

See [Documentation](http://wvlet.org/airframe/docs/) for further details.


# LICENSE

[Apache v2](https://github.com/wvlet/airframe/blob/master/LICENSE)
