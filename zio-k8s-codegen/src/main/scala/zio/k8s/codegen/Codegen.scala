package zio.k8s.codegen

import cats.data.NonEmptyList
import cats.implicits._
import com.twilio.guardrail.core.CoreTermInterp
import com.twilio.guardrail.generators.ScalaModule
import com.twilio.guardrail.languages.{ JavaLanguage, ScalaLanguage }
import com.twilio.guardrail.terms.CoreTerms
import com.twilio.guardrail.{
  Args,
  CLI,
  CLICommon,
  CodegenTarget,
  Context,
  Target,
  UnparseableArgument
}
import io.circe._
import io.circe.syntax._
import io.circe.yaml.parser._
import org.scalafmt.interfaces.Scalafmt
import sbt.AutoPlugin
import sbt.util.Logger
import zio._
import zio.blocking.Blocking
import zio.k8s.codegen.codegen._
import zio.k8s.codegen.k8smodel._
import zio.nio.core.file.Path
import zio.nio.file.Files
import zio.stream.{ Transducer, ZStream }

import java.io.{ File, IOException }
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.FileAttribute
import java.nio.file.{ StandardCopyOption, Path => JPath }

object Codegen extends AutoPlugin {

  import scala.meta._

  class K8sCodegen(implicit k8sContext: K8sCodegenContext) extends CLICommon {
    override implicit def scalaInterpreter: CoreTerms[ScalaLanguage, Target] =
      new CoreTermInterp[ScalaLanguage](
        "zio-k8s",
        ScalaModule.extract,
        {
          case "zio-k8s" => new ZioK8s
        },
        _.parse[Importer].toEither.bimap(
          err => UnparseableArgument("import", err.toString),
          importer => Import(List(importer))
        )
      )

    override implicit def javaInterpreter: CoreTerms[JavaLanguage, Target] =
      CLI.javaInterpreter
  }

  private def adjustSchema(schema: JsonSchemaProps): JsonSchemaProps =
    schema.copy(properties = schema.properties.map { props =>
      if (props.contains("metadata"))
        props
      else
        props.updated(
          "metadata",
          JsonSchemaProps(
            $ref = Some("ObjectMeta")
          )
        )
    })

  // TODO: configurable root package
  private def generateForVersion(
    crd: CustomResourceDefinition,
    version: CustomResourceDefinitionVersion,
    yamlPath: Path,
    outputRoot: Path
  ): ZIO[Blocking, Throwable, List[Path]] = {
    val name = crd.spec.names.singular.getOrElse(crd.spec.names.plural)
    version.schema.flatMap(_.openApiv3Schema) match {
      case Some(originalSchema) =>
        val schema = adjustSchema(originalSchema)
        val schemaFragment = schema.asJson.deepDropNullValues
        val fullSchema = Json.obj(
          "swagger" := "2.0",
          "info" := Json.obj(
            "title"   := s"${crd.spec.names.plural}.${crd.spec.group}",
            "version" := crd.spec.versions(0).name
          ),
          "paths" := Json.obj(),
          "definitions" := Json.obj(
            name := schemaFragment
          )
        )
        val schemaYaml = yaml.Printer(preserveOrder = true).pretty(fullSchema)
        for {
          schemaYamlPath <-
            Files.createTempFile(prefix = None, fileAttributes = Iterable.empty[FileAttribute[_]])
          _ <- writeTextFile(schemaYamlPath, schemaYaml)

          codegen = new K8sCodegen()(
                      K8sCodegenContext(
                        crd.spec.names.kind,
                        crd.spec.group,
                        version.name
                      )
                    )
          guardrailResult <- codegen
                               .guardrailRunner(
                                 Map(
                                   "scala" -> NonEmptyList.one(
                                     Args.empty.copy(
                                       kind = CodegenTarget.Models,
                                       packageName =
                                         Some(List("com", "coralogix", "operator", "client")),
                                       specPath = Some(schemaYamlPath.toString()),
                                       outputPath = Some(outputRoot.toString),
                                       dtoPackage = List(name, version.name),
                                       printHelp = false,
                                       context = Context.empty.copy(
                                         framework = Some("zio-k8s")
                                       ),
                                       defaults = false,
                                       imports = List(
                                         "com.coralogix.operator.client.model.generated.apimachinery.v1.ObjectMeta"
                                       )
                                     )
                                   )
                                 )
                               )
                               .fold(
                                 error =>
                                   ZIO.fail(new RuntimeException(s"Guardrail failed with $error")),
                                 files => ZIO.succeed(files)
                               )

          crdModule <- CustomResourceModuleGenerator.generateModuleCode(crd, version.name, Path("crds") / yamlPath.filename)
          modulePath =
            outputRoot / "com" / "coralogix" / "operator" / "client" / name / version.name / "package.scala"
          _ <- Files.createDirectories(modulePath.parent.get)
          _ <- writeTextFile(modulePath, crdModule)
        } yield modulePath :: guardrailResult.map(Path.fromJava)
      case None =>
        ZIO.succeed(List.empty)
    }
  }

  private def readTextFile(path: Path): ZIO[Blocking, Throwable, String] =
    ZStream
      .fromFile(path.toFile.toPath)
      .transduce(Transducer.utf8Decode)
      .fold("")(_ ++ _)

  private def writeTextFile(path: Path, contents: String): ZIO[Blocking, IOException, Unit] =
    Files.writeBytes(
      path,
      Chunk.fromArray(contents.getBytes(StandardCharsets.UTF_8))
    )

  private def generateForResource(
    path: Path,
    targetDir: Path
  ): ZIO[Blocking, Throwable, Set[Path]] =
    for {
      yaml   <- readTextFile(path)
      rawCrd <- ZIO.fromEither(parse(yaml))
      crd    <- ZIO.fromEither(rawCrd.as[CustomResourceDefinition])
      paths  <- ZIO.foreachPar(crd.spec.versions.toSet)(generateForVersion(crd, _, path, targetDir))
    } yield paths.flatten

  private def format(scalafmt: Scalafmt, path: Path) =
    for {
      code <- readTextFile(path)
      formatted = scalafmt.format(JPath.of(".scalafmt.conf"), path.toFile.toPath, code)
      _ <- writeTextFile(path, formatted)
    } yield path

  def generateSource(
    yaml: Path,
    targetDir: Path,
    log: Logger
  ): ZIO[Blocking, Throwable, Seq[File]] =
    for {
      scalafmt <- ZIO.effect(Scalafmt.create(this.getClass.getClassLoader))
      paths <- ZStream
                 .fromEffect(generateForResource(yaml, targetDir))
                 .map(Chunk.fromIterable)
                 .flattenChunks
                 .mapMPar(4)(format(scalafmt, _))
                 .runCollect
      _ <- ZIO.effect(log.info(s"Generated:\n${paths.mkString("\n")}"))
    } yield paths.map(_.toFile)

  def generateResource(
    yaml: Path,
    targetDir: Path,
    log: Logger
  ): ZIO[Blocking, Throwable, Seq[File]] = {
    val crdResources = targetDir / "crds"
    for {
      _ <- Files.createDirectories(crdResources)
      copiedYaml = crdResources / yaml.filename
      _ <- Files.copy(yaml, copiedYaml, StandardCopyOption.REPLACE_EXISTING)
      _ <- ZIO.effect(log.info(s"Copied CRD to $copiedYaml"))
    } yield Seq(copiedYaml.toFile)
  }
}
