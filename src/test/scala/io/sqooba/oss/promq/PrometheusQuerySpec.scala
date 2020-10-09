package io.sqooba.oss.promq

import java.time.Instant

import zio.test.Assertion.{ contains, equalTo, not }
import zio.test._

import scala.concurrent.duration.DurationInt

object PrometheusQuerySpec extends DefaultRunnableSpec {

  val spec = suite("encoder")(
    test("strip None Option in range query") {
      val start   = Instant.ofEpochMilli(0)
      val end     = Instant.ofEpochMilli(10000)
      val query   = RangeQuery("""{}""", start, end, 10, None)
      val encoded = PrometheusQuery.formEncode(query)

      assert(encoded.keySet)(not(contains("timeout"))) && assert(encoded)(
        equalTo(
          Map(
            "query" -> "{}",
            "start" -> f"${start}",
            "end"   -> f"${end}",
            "step"  -> "10"
          )
        )
      )
    },
    test("strip None option in instant query") {
      val query   = InstantQuery("""{}""", None, None)
      val encoded = PrometheusQuery.formEncode(query)

      assert(encoded.keySet)(not(contains("time"))) &&
      assert(encoded.keySet)(not(contains("timeout"))) &&
      assert(encoded)(
        equalTo(Map("query" -> "{}"))
      )
    },
    test("unwrap the options") {
      val query   = InstantQuery("""{}""", Some(Instant.ofEpochMilli(0)), Some(10))
      val encoded = PrometheusQuery.formEncode(query)

      assert(encoded)(
        equalTo(
          Map(
            "query"   -> "{}",
            "time"    -> f"${Instant.ofEpochMilli(0)}",
            "timeout" -> "10"
          )
        )
      )
    },
    test("correctly split the query") {
      val start   = Instant.parse("2020-10-01T10:00:00Z")
      val end     = Instant.parse("2020-10-01T14:00:01Z")
      val query   = RangeQuery("", start, end, 10.seconds.toSeconds.toInt, None)
      val queries = RangeQuery.splitRangeQuery(query, 14)
      /*
        val diff = end.getEpochSecond - start.getEpochSecond  // 14401L
        val samples = diff / step = diff / 10s = 1440.1
        upper(samples / max_sample) = 103
       */

      assert(queries.length)(equalTo(103)) &&
      assert(queries.head.start)(equalTo(start)) &&
      assert(queries.last.end)(equalTo(end))
    },
    test("return a single query if not split") {
      val start   = Instant.parse("2020-10-01T10:00:00Z")
      val end     = Instant.parse("2020-10-01T10:01:01Z")
      val query   = RangeQuery("", start, end, 10.seconds.toSeconds.toInt, None)
      val queries = RangeQuery.splitRangeQuery(query, maxSample = 14)

      /*
        val diff = end.getEpochSecond - start.getEpochSecond  // 14401L
        val samples = diff / step = diff / 10s = 1440.1
        upper(samples / max_sample) = 1
       */

      assert(queries.length)(equalTo(1)) &&
      assert(queries.head.start)(equalTo(start)) &&
      assert(queries.last.end)(equalTo(end))
    }
  )
}
