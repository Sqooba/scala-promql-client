package io.sqooba.oss.promql

import java.time.Instant
import zio.test.Assertion.{ contains, equalTo, not }
import zio.test._

import scala.concurrent.duration.DurationInt
import org.junit.runner.RunWith

@RunWith(classOf[zio.test.junit.ZTestJUnitRunner])
class PrometheusQuerySpec extends DefaultRunnableSpec {

  val spec = suite("encoder")(
    test("strip None Option in range query") {
      val start   = Instant.ofEpochMilli(0)
      val end     = Instant.ofEpochMilli(10000)
      val query   = RangeQuery("""{}""", start, end, 10.seconds, None)
      val encoded = query.formEncode()

      assert(encoded.map(_._1))(not(contains("timeout"))) && assert(encoded)(
        equalTo(
          List(
            "query" -> "{}",
            "start" -> f"${start}",
            "end"   -> f"${end}",
            // NOTE: PromQL duration notation is required here
            "step" -> "10s"
          )
        )
      )
    },
    test("strip None option in instant query") {
      val query   = InstantQuery("""{}""", None, None)
      val encoded = query.formEncode()

      assert(encoded.map(_._1))(not(contains("time"))) &&
      assert(encoded.map(_._1))(not(contains("timeout"))) &&
      assert(encoded)(
        equalTo(List("query" -> "{}"))
      )
    },
    test("unwrap the options") {
      val query   = InstantQuery("""{}""", Some(Instant.ofEpochMilli(0)), Some(10.seconds))
      val encoded = query.formEncode()

      assert(encoded)(
        equalTo(
          List(
            "query" -> "{}",
            "time"  -> f"${Instant.ofEpochMilli(0)}",
            // NOTE: PromQL duration notation is required here
            "timeout" -> "10s"
          )
        )
      )
    },
    test("create sub-queries") {
      val start   = Instant.parse("2020-10-01T00:00:00Z")
      val end     = Instant.parse("2020-10-01T00:00:50Z")
      val step    = 10.seconds
      val query   = RangeQuery("", start, end, step, None)
      val queries = RangeQuery.splitRangeQuery(query, 3)

      // We want a sampling of at most 3 points, with a step of 10 seconds
      val first  = RangeQuery("", start, start.plusSeconds(10 * 2), step, None)
      val second = RangeQuery("", first.end, start.plusSeconds(10 * 4), step, None)
      val third  = RangeQuery("", second.end, end, step, None)

      assert(queries.length)(equalTo(3)) &&
      assert(queries)(
        equalTo(
          List(first, second, third)
        )
      )
    },
    test("correctly split the query") {
      val start   = Instant.parse("2020-10-01T10:00:00Z")
      val end     = Instant.parse("2020-10-01T14:00:01Z")
      val query   = RangeQuery("", start, end, 10.seconds, None)
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
      val start   = Instant.parse("2020-10-01T10:00:00Z")
      val end     = Instant.parse("2020-10-01T10:01:01Z")
      val query   = RangeQuery("", start, end, 10.seconds, None)
      val queries = RangeQuery.splitRangeQuery(query, maxSample = 14)

      /*
        val diff = end.getEpochSecond - start.getEpochSecond  // 14401L
        val samples = diff / step = diff / 10s = 1440.1
        upper(samples / (max_sample - 1)) = 1
       */

      assert(queries.length)(equalTo(1)) &&
      assert(queries.head.start)(equalTo(start)) &&
      assert(queries.last.end)(equalTo(end))
    },
    test("labels query should set match array") {
      val start = Instant.parse("2020-10-01T10:00:00Z")
      val end   = Instant.parse("2020-10-01T10:01:01Z")
      val query = LabelsQuery(
        Some(
          List(
            "up",
            """process_start_time_seconds{job="prometheus"}"""
          )
        ),
        Some(start),
        Some(end)
      )

      assert(query.formEncode())(
        equalTo(
          List(
            ("start", "2020-10-01T10:00:00Z"),
            ("end", "2020-10-01T10:01:01Z"),
            ("match[]", "up"),
            ("match[]", """process_start_time_seconds{job="prometheus"}""")
          )
        )
      )
    },
    test("labels query should work without any parameter") {
      val query = LabelsQuery(None, None, None)

      assert(query.formEncode())(equalTo(List()))
    },
    test("series query should set match array") {
      val start = Instant.parse("2020-10-01T10:00:00Z")
      val end   = Instant.parse("2020-10-01T10:01:01Z")
      val query = SeriesQuery(
        List(
          "up",
          """process_start_time_seconds{job="prometheus"}"""
        ),
        start,
        end
      )

      assert(query.formEncode())(
        equalTo(
          List(
            ("start", "2020-10-01T10:00:00Z"),
            ("end", "2020-10-01T10:01:01Z"),
            ("match[]", "up"),
            ("match[]", """process_start_time_seconds{job="prometheus"}""")
          )
        )
      )
    },
    test("labelsvalues query should correctly encode its elements when none") {
      val query = LabelValuesQuery(
        "job",
        None,
        None,
        None
      )

      assert(query.formEncode())(
        equalTo(
          List(
            ("label", "job")
          )
        )
      )
    },
    test("labelsvalues query should correctly encode its elements when all sets") {
      val start = Instant.parse("2020-10-01T10:00:00Z")
      val end   = Instant.parse("2020-10-01T10:01:01Z")
      val query = LabelValuesQuery(
        "job",
        Some(Seq("up", "down")),
        Some(start),
        Some(end)
      )

      assert(query.formEncode().toSet)(
        equalTo(
          Set(
            ("label", "job"),
            ("start", "2020-10-01T10:00:00Z"),
            ("end", "2020-10-01T10:01:01Z"),
            ("match[]", "up"),
            ("match[]", "down")
          )
        )
      )
    }
  )
}
