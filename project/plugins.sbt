resolvers += Resolver.bintrayIvyRepo("coralogix", "sbt-plugins")

addSbtPlugin("org.scalameta"    % "sbt-scalafmt"        % "2.4.2")
addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.7.6")
addSbtPlugin("com.thesamet"     % "sbt-protoc"          % "1.0.0-RC4")
addSbtPlugin("org.scalameta"    % "sbt-native-image"    % "0.2.2")
addSbtPlugin("com.coralogix"    % "sbt-protodep"        % "0.0.7")

libraryDependencies += "com.thesamet.scalapb.zio-grpc" %% "zio-grpc-codegen" % "0.4.2"

lazy val codegen = project
  .in(file("."))
  .dependsOn(ProjectRef(file("../zio-k8s-codegen"), "zio-k8s-codegen"))


