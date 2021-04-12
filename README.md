# Scala Prometheus Query Client

Welcome to `scala-promql-client`.

This is an sttp based scala client for _issuing queries against a Prometheus server_.

This is **not** a library to instrument your scala application.

This library is relied on in contexts where we use a Prometheus instance as a time-series store
and wish to run queries for analytical purposes from a Scala application.

It is in a draft state at the moment: we will avoid deep API changes if possible, but can't exclude them.

# Installation

The library is available on sonatype, to use it in an SBT project add the following line:

```scala
libraryDependencies += "io.sqooba.oss" %% "scala-promql-client" % "0.4.0"
```

For maven:

```xml
<dependency>
    <groupId>io.sqooba.oss</groupId>
    <artifactId>scala-promql-client_2.13</artifactId>
    <version>0.4.0</version>
</dependency>
```

# Usage

## Configuration

In order to work properly, the client needs a configuration. This configuration is available and can be constructed using a `PrometheusClientConfig` case class.
The following parameters are required:

- Server's hostname or ip
- Server's port
- The maximum number of points a prometheus endpoint can return before we have to split queries (defined by the server, VictoriaMetrics has this value set to 30 000 by default)
- Number of retries to perform in case of a failed query
- Number of requests that can be made in parallel when splitting queries, be careful when changing this value

It is also possible to load this configuration from a file.
The `PrometheusClient.liveDefault` method will look inside `application.conf` for a block named `promql-client` and that contains the following values:

```
promql-client {
    host = localhost
    port = 8428
    maxPointsPerTimeseries = 30000
    retryNumber = 3
    parallelRequests = 1
}
```

Both `live` methods inside the `PrometheusClient` object can be used to create a layer providing a `PrometheusService` given a configuration.

## Importing data

`PrometheusService` has a `put` method that can be used to insert datapoints inside Prometheus, it can be used that way:

```scala
import java.time.Instant
import io.sqooba.oss.promql.metrics.PrometheusInsertMetric
import io.sqooba.oss.promql.{PrometheusClient, PrometheusService}

object Main extends zio.App {

  def run(args: List[String]) = {
    val now = Instant.now
    val layer = PrometheusClient.liveDefault // Load configuration from file

    PrometheusService
      .put(
        Seq(
          PrometheusInsertMetric(
            Map(
                "__name__" -> "timeseries_label"
            ),
            Seq(1, 2, 3),
            Seq(now.toEpochMilli, now.minusSeconds(60).toEpochMilli, now.minusSeconds(120).toEpochMilli)
          )
        )
      )
      .provideLayer(layer)
      .exitCode
  }

}
```

This will insert three points with value 1, 2 and 3 for the last three minutes.

#### Addings tags

It is possible to add tags to a timeseries by using the `Map` given as first argument to `PrometheusInsertMetric`.
The tag called `__name__` is a special tag that contains the name of the timeseries in prometheus.

## Running queries

Using the `query` method available on a `PrometheusService` it is possible to run arbitrary Prometheus queries.
As described on Prometheus' documentation, there are a few different queries that can be run, this client currently supports:

- InstantQuery [documentation](https://prometheus.io/docs/prometheus/latest/querying/api/#instant-queries) to run a query at a single point in time
- RangeQuery [documentation](https://prometheus.io/docs/prometheus/latest/querying/api/#range-queries) to run a query over a range of time

The following meta queries allow querying the set of available metrics for a specific time range:
- SeriesQuery [documentation](https://prometheus.io/docs/prometheus/latest/querying/api/#finding-series-by-label-matchers) to find the metrics, identified by combinations of labels
  - returns `MetricListReponseData` or `EmptyResponseData`
- LabelsQuery [documentation](https://prometheus.io/docs/prometheus/latest/querying/api/#getting-label-names) to get a list of actually used lables
  - returns `StringListResponsedata` or `EmptyResponseData`
- LabelValuesQuery [documentation](https://prometheus.io/docs/prometheus/latest/querying/api/#querying-label-values) to get a list of actually used values for a given label
  - returns `StringListResponsedata` or `EmptyResponseData`

The meta queries are not precisely specified, and the behaviour may differ sightly from one promQL compatible server implementation to another.

The `query` can be used in the following way:

```scala
import java.time.Instant
import scala.concurrent.duration._
import io.sqooba.oss.promql.metrics.PrometheusInsertMetric
import io.sqooba.oss.promql.{RangeQuery, PrometheusClient, PrometheusService}

object Main extends zio.App {

  def run(args: List[String]) = {
    val start = Instant.now.minusSeconds(24 * 60 * 60)
    val layer = PrometheusClient.liveDefault

    val query = RangeQuery(
      "timeseries_label",
      start,
      Instant.now,
      1.hour,
      None
    )

    PrometheusService
      .query(query)
      .provideLayer(layer)
      .exitCode
  }

}
```

A way to run multiple queries is by using a for-comprehension:

```scala
for {
    firstData <- PrometheusService.query(query)
    secondData <- PrometheusService.query(secondQuery)
} yield {
    (firstData, secondData) match {
        case (x @ MatrixResponseData(_), y @ MatrixResponseData(_)) => Some(x.merge(y))
        case _ => None
    }
}
```

Don't forget to pattern match and provide for a regular `case _`. Meta queries have to deal with empty responses by the means of the EmptyResponseData type.


# Versions and releases

See the [changelog](CHANGELOG.md) for more details.
