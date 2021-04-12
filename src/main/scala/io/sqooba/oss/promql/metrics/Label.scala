package io.sqooba.oss.promql.metrics

import io.circe.Decoder

object Label {

  implicit val stringDecode: Decoder[String] = Decoder.decodeString.map(value => value.trim)

}
