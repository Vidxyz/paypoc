// Settings applied during project definition loading
// This allows scala-xml version conflicts to be resolved

import sbt.util.Level

ThisBuild / evictionErrorLevel := Level.Warn
ThisBuild / libraryDependencySchemes += "org.scala-lang.modules" %% "scala-xml" % "always"
