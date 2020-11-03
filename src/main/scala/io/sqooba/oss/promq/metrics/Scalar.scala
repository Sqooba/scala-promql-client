package io.sqooba.oss.promq.metrics

import java.lang.Long
import io.circe.{ Decoder, DecodingFailure }
import scala.util.{ Failure, Success, Try }

/**
 * Represents a scalar value from prometheus
 * The supported values are described on [https://prometheus.io/docs/prometheus/latest/querying/basics/#float-literals](the documentation):
 *
 * [-+]?(
 *     [0-9]*\.?[0-9]+([eE][-+]?[0-9]+)?
 *     | 0[xX][0-9a-fA-F]+
 *     | [nN][aA][nN]
 *     | [iI][nN][fF]
 * )
 */
object Scalar {

  private val HEX_BASE = 16

  /**
   * Decode a scalar type from prometheus into a scala double representation
   *
   * Those values will be decoded in
   *  [[Double.NaN]] if +-nan is supplied (case insensitive)
   *  [[Double.NegativeInfinity]] if -inf is supplied (case insensitive)
   *  [[Double.PositiveInfinity]] if +inf or inf is supplied (case insensitive)
   *  The double value of the content for all other cases
   * @param str the string value to decode as double
   * @return A double in case of success, a [[DecodingFailure]] for all others cases
   */
  def fromString(str: String): Either[DecodingFailure, Double] = str.toLowerCase match { // scalastyle:ignore
    case "-inf"                  => Right(Double.NegativeInfinity)
    case "+inf" | "inf"          => Right(Double.PositiveInfinity)
    case "nan" | "-nan" | "+nan" => Right(Double.NaN)
    // scalastyle:off lowercase.pattern.match
    case hex if hex.startsWith("-0x") => Right(-parseHex(hex.substring(3)))
    case hex if hex.startsWith("0x")  => Right(parseHex(hex.substring(2)))
    case hex if hex.startsWith("+0x") => Right(parseHex(hex.substring(3)))
    case value =>
      Try(value.toDouble) match {
        case Success(parsed) => Right(parsed)
        case Failure(_)      => Left(DecodingFailure(s"Could not convert ${value} to double", List.empty))
      }
  }

  private def parseHex(str: String): Double = Long.valueOf(str, HEX_BASE).toDouble

  implicit val decodePrometheusScalar: Decoder[Double] = Decoder.decodeString.emap { str =>
    fromString(str).left.map(x => x.message)
  }
}
