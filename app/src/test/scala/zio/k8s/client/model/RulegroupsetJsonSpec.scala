package zio.k8s.client.model

import java.time.OffsetDateTime

import cats.data.{ NonEmptyList, Validated }
import zio.k8s.client.com.coralogix.definitions.rulegroupset.v1.Rulegroupset
import zio.k8s.client.internal.CircePrettyFailure
import io.circe._
import io.circe.parser._
import io.circe.syntax._
import zio.random.Random
import zio.test.Assertion._
import zio.test.AssertionM.Render.param
import zio.test.TestAspect.{ samples, sized }
import zio.test._
import zio.test.magnolia._

/**
  * Serialization tests for the generated RuleGroup model class,
  * to ensure that the https://github.com/circe/circe/issues/561
  * does not apply for it.
  */
object RulegroupsetJsonSpec extends DefaultRunnableSpec {

  implicit val deriveJson: DeriveGen.Typeclass[Json] =
    DeriveGen.instance[Json](
      Gen.const(Json.obj())
    ) // empty objects are represented by Json in the generated model
  implicit val deriveOffsetDateTime: DeriveGen.Typeclass[OffsetDateTime] =
    DeriveGen.instance[OffsetDateTime](Gen.const(OffsetDateTime.now()))

  val anyRulegroupset: Gen[Random with Sized, Rulegroupset] = DeriveGen.gen[Rulegroupset].derive

  override def spec =
    suite("Rulegroupset JSON serialization")(
      testM("Random encode/decode")(
        check(anyRulegroupset) { ruleGroup =>
          val json = ruleGroup.asJson.toString()
          val reparsed = decodeAccumulating[Rulegroupset](json)
            .leftMap(_.map(CircePrettyFailure.prettyPrint))

          assert(reparsed)(isValid(equalTo(ruleGroup)))
        }
      ) @@ samples(20) @@ sized(20)
    )

  private def isValid[A](assertion: Assertion[A]): Assertion[Validated[NonEmptyList[String], A]] =
    Assertion.assertionRec("isValid")(param(assertion))(assertion) {
      case Validated.Invalid(a) => None
      case Validated.Valid(a)   => Some(a)
    }
}