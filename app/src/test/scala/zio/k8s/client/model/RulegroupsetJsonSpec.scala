package zio.k8s.client.model

import java.time.OffsetDateTime

import cats.data.{ NonEmptyList, Validated }
import com.coralogix.zio.k8s.client.com.coralogix.definitions.rulegroupset.v1.RuleGroupSet
import com.coralogix.zio.k8s.client.internal.CircePrettyFailure
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

  val anyRuleGroupSet: Gen[Random with Sized, RuleGroupSet] = DeriveGen.gen[RuleGroupSet].derive

  override def spec =
    suite("RuleGroupSet JSON serialization")(
      testM("Random encode/decode")(
        check(anyRuleGroupSet) { ruleGroup =>
          val json = ruleGroup.asJson.toString()
          val reparsed = decodeAccumulating[RuleGroupSet](json)
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
