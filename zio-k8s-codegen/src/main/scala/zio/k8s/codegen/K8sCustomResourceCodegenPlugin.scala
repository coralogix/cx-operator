package zio.k8s.codegen

import sbt._
import sbt.Keys._

import zio.nio.core.file.{ Path => ZPath }

object K8sCustomResourceCodegenPlugin extends AutoPlugin {

  object autoImport {
    val externalCustomResourceDefinitions =
      settingKey[Seq[File]]("List of external K8s CRDs to generate clients and models for")

    // TODO: cache by source yaml hash

    lazy val generateSources =
      Def.task {
        val log = streams.value.log
        val runtime = zio.Runtime.default

        val crds = externalCustomResourceDefinitions.value
        val sourcesDir = (Compile / sourceManaged).value

        val cachedFun = FileFunction.cached(
          streams.value.cacheDirectory / "k8s-crd-src"
        ) { input: Set[File] =>
          input.foldLeft(Set.empty[File]) { (result, crdYaml) =>
            val fs = runtime.unsafeRun(
              Codegen.generateSource(
                ZPath.fromJava(crdYaml.toPath),
                ZPath.fromJava(sourcesDir.toPath),
                log
              )
            )
            result union fs.toSet
          }
        }

        cachedFun(crds.toSet).toSeq
      }

    lazy val copyResourceDefinitions =
      Def.task {
        import sbt.util.CacheImplicits._

        val s = streams.value
        val log = s.log
        val runtime = zio.Runtime.default

        val crds = externalCustomResourceDefinitions.value
        val resourcesDir = (Compile / resourceManaged).value

        val cachedFun = FileFunction.cached(
          streams.value.cacheDirectory / "k8s-crd-res"
        ) { input: Set[File] =>
          input.foldLeft(Set.empty[File]) { (result, crdYaml) =>
            val fs = runtime.unsafeRun(
              Codegen.generateResource(
                ZPath.fromJava(crdYaml.toPath),
                ZPath.fromJava(resourcesDir.toPath),
                log
              )
            )
            result union fs.toSet
          }
        }

        cachedFun(crds.toSet).toSeq
      }
  }

  import autoImport._

  override def projectSettings: Seq[Def.Setting[_]] =
    Seq(
      Compile / sourceGenerators += generateSources.taskValue,
      Compile / resourceGenerators += copyResourceDefinitions.taskValue,
      mappings in (Compile, packageSrc) ++= {
        val base = (sourceManaged in Compile).value
        val files = (managedSources in Compile).value
        files.map(f => (f, f.relativeTo(base).get.getPath))
      },
      externalCustomResourceDefinitions := Seq.empty
    )
}
