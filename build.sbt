name := "training"

version := "0.1"

scalaVersion := "2.13.5"

libraryDependencies += "org.typelevel" %% "cats-core" % "2.3.0"

libraryDependencies += "org.typelevel" %% "cats-effect" % "3.0.1" withSources() withJavadoc()

scalacOptions ++= Seq(
  "-feature",
  "-deprecation",
  "-unchecked",
  "-language:postfixOps"
)