package zio.k8s.codegen.codegen

import com.twilio.guardrail.generators.syntax._
import zio._
import zio.k8s.codegen.k8smodel.CustomResourceDefinition
import zio.nio.core.file.Path

import scala.meta._

object CustomResourceModuleGenerator {
  private val basePackage = q"com.coralogix.operator.client"

  def generateModuleCode(
    crd: CustomResourceDefinition,
    version: String,
    yamlPath: Path
  ): Task[String] =
    ZIO.effect {
      val moduleName = Term.Name(crd.spec.names.singular.getOrElse(crd.spec.names.plural))
      val entityName = Term.Name(moduleName.value.toPascalCase)
      val entityT = Type.Name(entityName.value)

      val ver = Term.Name(version)

      val kind = crd.spec.names.kind
      val group = crd.spec.group

      val pluaralLit = Lit.String(crd.spec.names.plural)
      val groupLit = Lit.String(group)
      val versionLit = Lit.String(version)

      val kindLit = Lit.String(kind)
      val apiVersionLit = Lit.String(s"${group}/${version}")

      val yamlPathLit = Lit.String("/" + yamlPath.toString)

      val dtoPackage = q"$basePackage.definitions.$moduleName.$ver"
      val entityImport =
        Import(List(Importer(dtoPackage, List(Importee.Name(Name(entityName.value))))))

      val isNamespaced = crd.spec.scope == "Namespaced"
      val hasStatus = crd.spec.versions
        .find(_.name == version)
        .flatMap(_.subresources.flatMap(_.status))
        .isDefined

      val statusT =
        if (hasStatus)
          Type.Select(Term.Name(entityName.value), Type.Name("Status"))
        else
          t"Nothing"

      val code =
        if (isNamespaced)
          q"""package $basePackage.$moduleName {

          $entityImport
          import $basePackage._
          import $basePackage.model.generated.apimachinery.v1.{ DeleteOptions, Status }
          import $basePackage.model.generated.apiextensions.v1.CustomResourceDefinition
          import $basePackage.model.{
            K8sCluster,
            K8sNamespace,
            K8sResourceType,
            ResourceMetadata,
            TypedWatchEvent
          }
          import sttp.client3.httpclient.zio.SttpClient
          import zio.blocking.Blocking
          import zio.clock.Clock
          import zio.config.ZConfig
          import zio.stream.{ZStream, ZTransducer}
          import zio.{ Has, Task, ZIO, ZLayer }

          package object $ver {
            implicit val metadata: ResourceMetadata[$entityT] =
              new ResourceMetadata[$entityT] {
                override val kind: String = $kindLit
                override val apiVersion: String = $apiVersionLit
                override val resourceType: K8sResourceType = K8sResourceType($pluaralLit, $groupLit, $versionLit)
              }

            implicit val customResourceDefinition: ZIO[Blocking, Throwable, CustomResourceDefinition] =
              for {
                rawYaml <- ZStream.fromInputStream(getClass.getResourceAsStream($yamlPathLit))
                  .transduce(ZTransducer.utf8Decode)
                  .fold("")(_ ++ _).orDie
                crd <- ZIO.fromEither(io.circe.yaml.parser.parse(rawYaml).flatMap(_.as[CustomResourceDefinition]))
              } yield crd

            def live: ZLayer[SttpClient with ZConfig[K8sCluster], Nothing, Has[Resource[$statusT, $entityT]]] =
              ResourceClient.namespaced.live[$statusT, $entityT](metadata.resourceType)

              def getAll(
                namespace: Option[K8sNamespace],
                chunkSize: Int = 10
              ): ZStream[Has[Resource[$statusT, $entityT]], K8sFailure, $entityT] =
                ResourceClient.namespaced.getAll(namespace, chunkSize)

              def watch(
                namespace: Option[K8sNamespace],
                resourceVersion: Option[String]
              ): ZStream[Has[Resource[$statusT, $entityT]], K8sFailure, TypedWatchEvent[$entityT]] =
                ResourceClient.namespaced.watch(namespace, resourceVersion)

              def watchForever[T, R, E](
                namespace: Option[K8sNamespace]
              ): ZStream[Has[Resource[$statusT, $entityT]] with Clock, K8sFailure, TypedWatchEvent[$entityT]] =
                ResourceClient.namespaced.watchForever(namespace)

              def get(
                name: String,
                namespace: Option[K8sNamespace]
              ): ZIO[Has[Resource[$statusT, $entityT]], K8sFailure, $entityT] =
                ResourceClient.namespaced.get(name, namespace)

              def create(
                newResource: $entityT,
                namespace: Option[K8sNamespace],
                dryRun: Boolean = false
              ): ZIO[Has[Resource[$statusT, $entityT]], K8sFailure, $entityT] =
                ResourceClient.namespaced.create(newResource, namespace, dryRun)

              def replace(
                name: String,
                updatedResource: $entityT,
                namespace: Option[K8sNamespace],
                dryRun: Boolean = false
              ): ZIO[Has[Resource[$statusT, $entityT]], K8sFailure, $entityT] =
                ResourceClient.namespaced.replace(name, updatedResource, namespace, dryRun)

              def replaceStatus(
                of: $entityT,
                updatedStatus: $statusT,
                namespace: Option[K8sNamespace],
                dryRun: Boolean = false
              ): ZIO[Has[Resource[$statusT, $entityT]], K8sFailure, $entityT] =
                ResourceClient.namespaced.replaceStatus(of, updatedStatus, namespace, dryRun)

              def delete(
                name: String,
                deleteOptions: DeleteOptions,
                namespace: Option[K8sNamespace],
                dryRun: Boolean = false
              ): ZIO[Has[Resource[$statusT, $entityT]], K8sFailure, Status] =
                ResourceClient.namespaced.delete(name, deleteOptions, namespace, dryRun)
          }
          }
          """
        else
          q"""package $basePackage.$moduleName {

          $entityImport
          import $basePackage._
          import $basePackage.model.generated.{ DeleteOptions, Status }
          import $basePackage.model.{
            K8sCluster,
            K8sNamespace,
            K8sResourceType,
            ResourceMetadata,
            TypedWatchEvent
          }
          import sttp.client3.httpclient.zio.SttpClient
          import zio.blocking.Blocking
          import zio.clock.Clock
          import zio.config.ZConfig
          import zio.stream.{ZStream, ZTransducer}
          import zio.{ Has, Task, ZIO, ZLayer }

          package object $ver {
            implicit val metadata: ResourceMetadata[$entityT] =
              new ResourceMetadata[$entityT] {
                override val kind: String = $kindLit
                override val apiVersion: String = $apiVersionLit
                override val resourceType: K8sResourceType = K8sResourceType($pluaralLit, $groupLit, $versionLit)
              }

            implicit val customResourceDefinition: ZIO[Blocking, Throwable, CustomResourceDefinition] =
              for {
                rawYaml <- ZStream.fromInputStream(getClass.getResourceAsStream($yamlPathLit))
                  .transduce(ZTransducer.utf8Decode)
                  .fold("")(_ ++ _).orDie
                crd <- ZIO.fromEither(io.circe.yaml.parser.parse(rawYaml).flatMap(_.as[CustomResourceDefinition]))
              } yield crd

            def live: ZLayer[SttpClient with ZConfig[K8sCluster], Nothing, Has[ClusterResource[$statusT, $entityT]]] =
              ResourceClient.cluster.live[$statusT, $entityT](metadata.resourceType)

              def getAll(
                chunkSize: Int = 10
              ): ZStream[Has[ClusterResource[$statusT, $entityT]], K8sFailure, $entityT] =
                ResourceClient.cluster.getAll(chunkSize)

              def watch(
                resourceVersion: Option[String]
              ): ZStream[Has[ClusterResource[$statusT, $entityT]], K8sFailure, TypedWatchEvent[$entityT]] =
                ResourceClient.cluster.watch(resourceVersion)

              def watchForever[T, R, E](
              ): ZStream[Has[ClusterResource[$statusT, $entityT]] with Clock, K8sFailure, TypedWatchEvent[$entityT]] =
                ResourceClient.cluster.watchForever()

              def get(name: String): ZIO[Has[ClusterResource[$statusT, $entityT]], K8sFailure, $entityT] =
                ResourceClient.cluster.get(name)

              def create(
                newResource: $entityT,
                dryRun: Boolean = false
              ): ZIO[Has[ClusterResource[$statusT, $entityT]], K8sFailure, $entityT] =
                ResourceClient.cluster.create(newResource, dryRun)

              def replace(
                name: String,
                updatedResource: $entityT,
                dryRun: Boolean = false
              ): ZIO[Has[ClusterResource[$statusT, $entityT]], K8sFailure, $entityT] =
                ResourceClient.cluster.replace(name, updatedResource, dryRun)

              def replaceStatus(
                of: $entityT,
                updatedStatus: $statusT,
                dryRun: Boolean = false
              ): ZIO[Has[ClusterResource[$statusT, $entityT]], K8sFailure, $entityT] =
                ResourceClient.cluster.replaceStatus(of, updatedStatus, dryRun)

              def delete(
                name: String,
                deleteOptions: DeleteOptions,
                dryRun: Boolean = false
              ): ZIO[Has[ClusterResource[$statusT, $entityT]], K8sFailure, Status] =
                ResourceClient.cluster.delete(name, deleteOptions, dryRun)
          }
          }
          """

      code.toString()
    }
}
