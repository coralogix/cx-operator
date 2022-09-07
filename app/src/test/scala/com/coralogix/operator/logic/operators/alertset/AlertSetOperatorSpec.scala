package com.coralogix.operator.logic.operators.alertset

import zio.test.DefaultRunnableSpec
import zio.test._

object AlertSetOperatorSpec extends DefaultRunnableSpec {

  override def spec =
    suite("AlertSetOperatorSpec")(
      suite("filterLabels")(
        test("filters by regex list") {
          val alertLabels = List("CX_.*", ".*_TEST", "OPERATOR")
          val metadataLabels = Map(
            "CX_AZ"                 -> "eu-north-1a",
            "CX_CLOUDFLARE_SITE_ID" -> "af3dbe35ffaf30c9101725801c315767",
            "L1_TEST_1"             -> "VALUE_1",
            "L2_TEST"               -> "VALUE_2",
            "OPERATOR"              -> "VALUE_3",
            "UNKNOWN"               -> "VALUE_4"
          )

          val expected = Map(
            "CX_AZ"                 -> "eu-north-1a",
            "CX_CLOUDFLARE_SITE_ID" -> "af3dbe35ffaf30c9101725801c315767",
            "L2_TEST"               -> "VALUE_2",
            "OPERATOR"              -> "VALUE_3"
          )

          assertTrue(AlertSetOperator.filterLabels(alertLabels)(metadataLabels) == expected)
        },
        test("filters by empty list") {
          val alertLabels = List.empty[String]
          val metadataLabels = Map(
            "CX_AZ" -> "eu-north-1a",
            "CX_CLOUDFLARE_SITE_ID" -> "af3dbe35ffaf30c9101725801c315767",
            "L1_TEST_1" -> "VALUE_1",
            "L2_TEST" -> "VALUE_2",
            "OPERATOR" -> "VALUE_3",
            "UNKNOWN" -> "VALUE_4"
          )

          assertTrue(AlertSetOperator.filterLabels(alertLabels)(metadataLabels).isEmpty)
        }
      )
    )
}
