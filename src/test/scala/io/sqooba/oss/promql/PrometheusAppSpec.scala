package io.sqooba.oss.promql

import io.sqooba.oss.promql.PrometheusService.PrometheusService
import zio.test.Assertion
import zio.test.Assertion.{ contains, equalTo, hasSize, isSubtype }
import io.sqooba.oss.promql.metrics.MatrixMetric
import io.sqooba.oss.utils.PromClientRunnable
import io.sqooba.oss.utils.Utils.{ insertFakePercentage, randomOtherLabelTS }
import zio.ZIO
import zio.test.{ assert, suite, testM }

import java.time.Instant
import scala.concurrent.duration.DurationInt

object PrometheusAppSpec extends PromClientRunnable {

  val spec: PromClientRunnable = suite("VictoriaMetricsApp")(
    suite("put")(
      testM("Should retrieve the inserted points") {
        val start = Instant.parse("2020-12-12T00:00:00.000Z")
        val end   = start.plusSeconds(4.minutes.toSeconds)
        val step  = 10.seconds
        val label = "cpu"
        val query = f"""$label{type="workstation"}"""

        val queryFromProm: ZIO[PrometheusService, PrometheusError, ResponseData] =
          PrometheusService.query(
            RangeQuery(
              query,
              start,
              end,
              step = step,
              timeout = None
            )
          )

        val cpuMetric = Map("__name__" -> label, "type" -> "workstation")
        val insertDataPoints =
          insertFakePercentage(start, end, cpuMetric, step)

        val queries = insertDataPoints <*> queryFromProm

        for {
          (metrics, result) <- queries
        } yield assert({
          val relevantMetrics =
            result.asInstanceOf[MatrixResponseData].result.filter((p: MatrixMetric) => p.metric.equals(cpuMetric))
          val compatibleSerie = relevantMetrics.head.values.map(i => (i._1, i._2.toDouble))
          // MatrixMetrics are Strings, in order to compare, we have to convert them back to Double
          // exceptionally, this is safe, because the generating function  Utils.randomBetween casts these Doubles from Int values.
          val exclusiveEnd = compatibleSerie.slice(0, compatibleSerie.size - 1)
          exclusiveEnd // insertFakePercentage is exclusive end, RangeQuery is inclusive, hence generates one more result than was stored
        })(
          equalTo( // Seq[Long, Double]
            metrics.timestamps
              .zip(metrics.values)
              .map {
                case (ts, v) =>
                  (Instant.ofEpochMilli(ts), v)
              }
              .toList
          )
        )
      }
    ),
    suite("series Query")(
      testM("should find no Series or Labels in empty instance") {
        val start = Instant.parse("2020-12-12T00:00:00.000Z")
        val end   = start.plusSeconds(4.minutes.toSeconds)

        val querySeriesFromProm: ZIO[PrometheusService, PrometheusError, ResponseData] =
          PrometheusService.query(
            SeriesQuery(
              matches = Seq("{__name__=~\"..*\"}"),
              start,
              end
            )
          )
        val queryPatternLabelsFromProm: ZIO[PrometheusService, PrometheusError, ResponseData] =
          PrometheusService.query(
            LabelsQuery(
              matches = Some(Seq("{__name__=~\"..*\"}")),
              Some(start),
              Some(end)
            )
          )
        val queryAnyLabelsFromProm: ZIO[PrometheusService, PrometheusError, ResponseData] =
          PrometheusService.query(
            LabelsQuery(
              None,
              Some(start),
              Some(end)
            )
          )
        for {
          result_serie        <- querySeriesFromProm
          result_label_regexp <- queryPatternLabelsFromProm
          result_label_any    <- queryAnyLabelsFromProm
        } yield assert(result_serie)(
          isSubtype[EmptyResponseData](Assertion.anything)
        ) && assert(result_label_regexp)(
          isSubtype[EmptyResponseData](Assertion.anything)
        ) && assert(result_label_any)(
          isSubtype[EmptyResponseData](Assertion.anything)
        )
      },
      testM("should find only the inserted serie") {
        val start = Instant.parse("2020-12-12T00:00:00.000Z")
        val end   = start.plusSeconds(4.minutes.toSeconds)
        val step  = 10.seconds
        val label = "cpu"

        val queryFromProm: ZIO[PrometheusService, PrometheusError, ResponseData] =
          PrometheusService.query(
            SeriesQuery(
              matches = Seq(
                "{__name__=~\"..*\"}"
              ), // the Regexp-match for "..*" is specific for Prometheus, which has a fool-guard preventing you from querying all series and possibly impacting production performance severely. Victoria-metrics does not implement this feature of the API.
              start,
              end
            )
          )

        val cpuMetric = Map("__name__" -> label, "type" -> "workstation")
        val insertDataPoints =
          insertFakePercentage(start, end, cpuMetric, step)

        for {
          (_, result) <- insertDataPoints <*> queryFromProm
        } yield assert(result)(
          isSubtype[MetricListResponseData](Assertion.anything)
        ).&&(
          assert(result.asInstanceOf[MetricListResponseData].data)(
            hasSize[Map[String, String]](equalTo(1)).&&(
              contains[Map[String, String]](Map("__name__" -> "cpu", "type" -> "workstation"))
            )
          )
        )

      }
    ),
    suite("labels Queries")(
      testM("should find only the inserted Label, given no constraints") {
        val start = Instant.parse("2020-12-12T00:00:00.000Z")
        val end   = start.plusSeconds(4.minutes.toSeconds)
        val step  = 10.seconds
        val label = "cpu"

        val queryFromProm: ZIO[PrometheusService, PrometheusError, ResponseData] =
          PrometheusService.query(
            LabelsQuery(
              matches = None,
              None,
              None
            )
          )

        val cpuMetric = Map("__name__" -> label, "type" -> "workstation")
        val insertDataPoints =
          insertFakePercentage(start, end, cpuMetric, step)

        for {
          (_, result) <- insertDataPoints <*> queryFromProm
        } yield assert(result)(
          isSubtype[StringListResponseData](Assertion.anything)
        ) &&
          assert(result.asInstanceOf[StringListResponseData].data)(
            hasSize(equalTo(2))
          ) &&
          assert(result.asInstanceOf[StringListResponseData].data)(
            contains[String]("type") && contains[String]("__name__")
          )
      },
      testM("should find only the inserted Label, given a time range and a match-all Regexp constraint") {
        val start = Instant.parse("2020-12-12T00:00:00.000Z")
        val end   = start.plusSeconds(4.minutes.toSeconds)
        val step  = 10.seconds
        val label = "cpu"

        val queryFromProm: ZIO[PrometheusService, PrometheusError, ResponseData] =
          PrometheusService.query(
            LabelsQuery(
              matches = Some(
                Seq("{__name__=~\"..*\"}")
              ),
              // the Regexp-match for "..*" is specific for Prometheus, which has a fool-guard preventing you from querying all series and possibly impacting production performance severely.
              // Victoria-metrics does not implement this feature of the API.
              Some(start),
              Some(end)
            )
          )

        val cpuMetric = Map("__name__" -> label, "type" -> "workstation")
        val insertDataPoints =
          insertFakePercentage(start, end, cpuMetric, step)

        for {
          (_, result) <- insertDataPoints <*> queryFromProm
        } yield assert(result)(
          isSubtype[StringListResponseData](Assertion.anything)
        ) &&
          assert(result.asInstanceOf[StringListResponseData].data)(
            hasSize(equalTo(2))
          ) &&
          assert(result.asInstanceOf[StringListResponseData].data)(
            contains[String]("type") && contains[String]("__name__")
          )
      },
      testM("should find only the inserted Label that matches the given filter") {
        val start        = Instant.parse("2020-12-12T00:00:00.000Z")
        val end          = start.plusSeconds(4.minutes.toSeconds)
        val step         = 10.seconds
        val cpuLabel     = "cpu"
        val cpuCoreLabel = "cpuCore"
        val memoryLabel  = "memory"

        val queryFromProm: ZIO[PrometheusService, PrometheusError, ResponseData] =
          PrometheusService.query(
            LabelsQuery(
              matches = Some(
                Seq("{__name__=~\"cpu\"}")
              ),
              // the Regexp-match for "..*" is specific for Prometheus, which has a fool-guard preventing you from querying all series and possibly impacting production performance severely.
              // Victoria-metrics does not implement this feature of the API.
              Some(start),
              Some(end)
            )
          )

        val cpuMetric     = Map("__name__" -> cpuLabel, "type" -> "workstation")
        val cpuCoreMetric = Map("__name__" -> cpuCoreLabel, "type" -> "workstation")
        val memoryMetric =
          Map(
            "__name__" -> memoryLabel,
            "extract"  -> "false",
            "type"     -> "workstation"
          ) // the extract field should not be returned because __name__ does not match the filter
        val insertDataPoints =
          insertFakePercentage(start, end, cpuMetric, step) *>
            insertFakePercentage(start, end, cpuCoreMetric, step) *>
            insertFakePercentage(start, end, memoryMetric, step)

        for {
          (_, result) <- insertDataPoints <*> queryFromProm
        } yield assert(result)(
          isSubtype[StringListResponseData](Assertion.anything)
        ) &&
          assert(result.asInstanceOf[StringListResponseData].data)(
            hasSize(equalTo(2))
          ) &&
          assert(result.asInstanceOf[StringListResponseData].data)(
            contains[String]("__name__") && contains[String]("type")
          )
      }
      /*,
      testM("should find only the inserted Label, over all time, no specifying start & end") {
        // FIXME: This Test succeeds for Prometheus, but fails for Victoria Metrics - not sure how to handle it
        // in the context of these Test Suites. A separate Promnetheus Ruinner ?
        val start = Instant.parse("2020-12-12T00:00:00.000Z")
        val end = start.plusSeconds(4.minutes.toSeconds)
        val step = 10.seconds
        val label = "cpu"

        val queryFromProm: ZIO[PrometheusService, PrometheusError, ResponseData] =
          PrometheusService.query(
            LabelsQuery(
              matches = Some(
                Seq("{__name__=~\"c.*\"}")
              ), // the Regexp-match for "..*" is specific for Prometheus, which has a fool-guard preventing you from querying all series and possibly impacting production performance severely. Victoria-metrics does not implement this feature of the API.
              None, None
            )
          )

        val cpuMetric = Map("__name__" -> label, "type" -> "workstation")
        val insertDataPoints =
          insertFakePercentage(start, end, cpuMetric, step)

        for {
          (_, result) <- insertDataPoints <*> queryFromProm
        } yield {

          assert(result) {
            isSubtype[EmptyResponseData](Assertion.anything) // Victoria
          }.||( // this is the wrong way, does not work
          assert(result)(
            isSubtype[StringListResponseData](Assertion.anything) // Prometheus
          ) &&
            assert(result.asInstanceOf[StringListResponseData].data)(
              hasSize(equalTo(2))
            ) &&
            assert(result.asInstanceOf[StringListResponseData].data)(
              contains[String]("type") && contains[String]("__name__")
            )
          )


        }
      } */

    ),
    suite("labels values Queries")(
      testM("should find all the inserted label values, given no constraints") {
        val start = Instant.parse("2020-12-12T00:00:00.000Z")
        val end   = start.plusSeconds(4.minutes.toSeconds)
        val step  = 10.seconds
        val label = "cpu"

        val queryFromProm: ZIO[PrometheusService, PrometheusError, ResponseData] =
          PrometheusService.query(
            LabelValuesQuery(
              "type",
              matches = None,
              None,
              None
            )
          )

        val cpuMetric  = Map("__name__" -> label, "type" -> "workstation")
        val cpuMetric2 = Map("__name__" -> label, "type" -> "server")

        val insertDataPoints =
          insertFakePercentage(start, end, cpuMetric, step) <*> insertFakePercentage(start, end, cpuMetric2, step)

        for {
          (_, result) <- insertDataPoints <*> queryFromProm
        } yield assert(result)(
          isSubtype[StringListResponseData](Assertion.anything)
        ) &&
          assert(result.asInstanceOf[StringListResponseData].data)(
            hasSize(equalTo(2))
          ) &&
          assert(result.asInstanceOf[StringListResponseData].data)(
            contains[String]("workstation")
          )
      },
      testM("should find only the inserted label values, given a time range and a match-all Regexp constraint") {
        val start = Instant.parse("2020-12-12T00:00:00.000Z")
        val end   = start.plusSeconds(4.minutes.toSeconds)
        val step  = 10.seconds
        val label = "cpu"

        val queryFromProm: ZIO[PrometheusService, PrometheusError, ResponseData] =
          PrometheusService.query(
            LabelValuesQuery(
              "type",
              matches = Some(
                Seq("{__name__=~\"..*\"}")
              ),
              // the Regexp-match for "..*" is specific for Prometheus, which has a fool-guard preventing you from querying all series and possibly impacting production performance severely.
              // Victoria-metrics does not implement this feature of the API.
              Some(start),
              Some(end)
            )
          )

        val cpuMetric = Map("__name__" -> label, "type" -> "workstation")
        val insertDataPoints =
          insertFakePercentage(start, end, cpuMetric, step)

        for {
          (_, result) <- insertDataPoints <*> queryFromProm
        } yield assert(result)(
          isSubtype[StringListResponseData](Assertion.anything)
        ) &&
          assert(result.asInstanceOf[StringListResponseData].data)(
            hasSize(equalTo(1))
          ) &&
          assert(result.asInstanceOf[StringListResponseData].data)(
            contains[String]("workstation")
          )
      },
      testM("should find only the inserted label value, given a time range and a label constraint") {
        val start       = Instant.parse("2020-12-12T00:00:00.000Z")
        val end         = start.plusSeconds(4.minutes.toSeconds)
        val step        = 10.seconds
        val cpuLabel    = "cpu"
        val memoryLabel = "memory"

        val queryFromProm: ZIO[PrometheusService, PrometheusError, ResponseData] =
          PrometheusService.query(
            LabelValuesQuery(
              "type",
              matches = Some(
                Seq("""{__name__="cpu"}""")
              ),
              Some(start),
              Some(end)
            )
          )

        val cpuMetric       = Map("__name__" -> cpuLabel, "type" -> "workstation")
        val serverCpuMetric = Map("__name__" -> cpuLabel, "type" -> "server")
        val memoryMetric    = Map("__name__" -> memoryLabel, "type" -> "old") // this should not be returned by the query
        val insertDataPoints =
          insertFakePercentage(start, end, cpuMetric, step) *> insertFakePercentage(start, end, serverCpuMetric, step)
        val noiseDataPoints =
          randomOtherLabelTS(start, end, cpuMetric, step, 5) *> insertFakePercentage(start, end, memoryMetric, step)

        for {
          (_, result) <- (insertDataPoints <*> noiseDataPoints) <*> queryFromProm
        } yield assert(result)(
          isSubtype[StringListResponseData](Assertion.anything)
        ) &&
          assert(result.asInstanceOf[StringListResponseData].data)(
            hasSize(equalTo(2))
          ) &&
          assert(result.asInstanceOf[StringListResponseData].data.toSet)(
            equalTo(Set("workstation", "server"))
          )
      },
      testM("should find only the inserted label value, given a time range and multiple label constraint") {
        val start       = Instant.parse("2020-12-12T00:00:00.000Z")
        val end         = start.plusSeconds(4.minutes.toSeconds)
        val step        = 10.seconds
        val cpuLabel    = "cpu"
        val memoryLabel = "memory"
        val powerLabel  = "power"

        val queryFromProm: ZIO[PrometheusService, PrometheusError, ResponseData] =
          PrometheusService.query(
            LabelValuesQuery(
              "type",
              matches = Some(
                Seq("""{__name__="cpu"}""", """{__name__="memory"}""")
              ),
              Some(start),
              Some(end)
            )
          )

        val cpuMetric       = Map("__name__" -> cpuLabel, "type" -> "workstation")
        val serverCpuMetric = Map("__name__" -> cpuLabel, "type" -> "server")
        val memoryMetric    = Map("__name__" -> memoryLabel, "type" -> "old")
        val powerMetric     = Map("__name__" -> powerLabel, "type" -> "iot") // Should not be returned

        val insertDataPoints =
          insertFakePercentage(start, end, cpuMetric, step) *> insertFakePercentage(
            start,
            end,
            serverCpuMetric,
            step
          ) *> insertFakePercentage(start, end, powerMetric, step)
        val noiseDataPoints =
          randomOtherLabelTS(start, end, cpuMetric, step, 5) *> insertFakePercentage(start, end, memoryMetric, step)

        for {
          (_, result) <- (insertDataPoints <*> noiseDataPoints) <*> queryFromProm
        } yield assert(result)(
          isSubtype[StringListResponseData](Assertion.anything)
        ) &&
          assert(result.asInstanceOf[StringListResponseData].data)(
            hasSize(equalTo(3))
          ) &&
          assert(result.asInstanceOf[StringListResponseData].data.toSet)(
            equalTo(Set("workstation", "server", "old"))
          )
      }
    )
  )
}
