package io.sqooba.oss.promql

import java.time.Instant

import zio.test.Assertion.{contains, equalTo, not}
import zio.test._

import scala.concurrent.duration.DurationInt
import zio.test.junit.ZTestJUnitRunner
import org.junit.runner.RunWith

@RunWith(classOf[zio.test.junit.ZTestJUnitRunner])
class PrometheusQuerySpec extends DefaultRunnableSpec {

  val spec = suite("encoder")(
    test("strip None Option in range query") {
      val start = Instant.ofEpochMilli(0)
      val end = Instant.ofEpochMilli(10000)
      val query = RangeQuery("""{}""", start, end, 10.seconds, None)
      val encoded = PrometheusQuery.formEncode(query)

      assert(encoded.keySet)(not(contains("timeout"))) && assert(encoded)(
        equalTo(
          Map(
            "query" -> "{}",
            "start" -> f"${start}",
            "end" -> f"${end}",
            // NOTE: PromQL duration notation is required here
            "step" -> "10s"
          )
        )
      )
    },
    test("strip None option in instant query") {
      val query = InstantQuery("""{}""", None, None)
      val encoded = PrometheusQuery.formEncode(query)

      assert(encoded.keySet)(not(contains("time"))) &&
      assert(encoded.keySet)(not(contains("timeout"))) &&
      assert(encoded)(
        equalTo(Map("query" -> "{}"))
      )
    },
    test("unwrap the options") {
      val query = InstantQuery("""{}""", Some(Instant.ofEpochMilli(0)), Some(10.seconds))
      val encoded = PrometheusQuery.formEncode(query)

      assert(encoded)(
        equalTo(
          Map(
            "query" -> "{}",
            "time" -> f"${Instant.ofEpochMilli(0)}",
            // NOTE: PromQL duration notation is required here
            "timeout" -> "10s"
          )
        )
      )
    },
    test("create sub-queries") {
      val start = Instant.parse("2020-10-01T00:00:00Z")
      val end = Instant.parse("2020-10-01T00:00:50Z")
      val step = 10.seconds
      val query = RangeQuery("", start, end, step, None)
      val queries = RangeQuery.splitRangeQuery(query, 3)

      // We want a sampling of at most 3 points, with a step of 10 seconds
      val first = RangeQuery("", start, start.plusSeconds(10 * 2), step, None)
      val second = RangeQuery("", first.end, start.plusSeconds(10 * 4), step, None)
      val third = RangeQuery("", second.end, end, step, None)

      assert(queries.length)(equalTo(3)) &&
      assert(queries)(
        equalTo(
          List(first, second, third)
        )
      )
    },
    test("correctly split the query") {
      val start = Instant.parse("2020-10-01T10:00:00Z")
      val end = Instant.parse("2020-10-01T14:00:01Z")
      val query = RangeQuery("", start, end, 10.seconds, None)
      val queries = RangeQuery.splitRangeQuery(query, 14)

      /*
        val diff = end.getEpochSecond - start.getEpochSecond  // 14401L
        val samples = diff / step = diff / 10s = 1440.1
        upper(samples / (max_sample - 1)) = 111
       */

      assert(queries.length)(equalTo(111)) &&
      assert(queries.head.start)(equalTo(start)) &&
      assert(queries.last.end)(equalTo(end))
    },
    test("return a single query if not split") {
      val start = Instant.parse("2020-10-01T10:00:00Z")
      val end = Instant.parse("2020-10-01T10:01:01Z")
      val query = RangeQuery("", start, end, 10.seconds, None)
      val queries = RangeQuery.splitRangeQuery(query, maxSample = 14)

      /*
        val diff = end.getEpochSecond - start.getEpochSecond  // 14401L
        val samples = diff / step = diff / 10s = 1440.1
        upper(samples / (max_sample - 1)) = 1
       */

      assert(queries.length)(equalTo(1)) &&
      assert(queries.head.start)(equalTo(start)) &&
      assert(queries.last.end)(equalTo(end))
    }
  )
}
