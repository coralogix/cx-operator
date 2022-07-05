resolvers += Resolver.bintrayIvyRepo("coralogix", "sbt-plugins")

addSbtPlugin("org.scalameta"    % "sbt-scalafmt"        % "2.4.2")
addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.7.6")
addSbtPlugin("com.thesamet"     % "sbt-protoc"          % "1.0.0")
addSbtPlugin("org.scalameta"    % "sbt-native-image"    % "0.3.2")
addSbtPlugin("com.coralogix"    % "sbt-protodep"        % "0.0.11")
addSbtPlugin("com.coralogix"    % "zio-k8s-crd"         % "1.4.3")

libraryDependencies += "com.thesamet.scalapb.zio-grpc" %% "zio-grpc-codegen" % "0.5.1"
