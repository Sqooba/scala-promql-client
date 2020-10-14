# Scala Prometheus Query Client

Welcome to `scala-promql-client`.

This is an sttp based scala client for _issuing queries against a Prometheus server_.

This is **not** a library to instrument your scala application.

This library is relied on in contexts where we use a Prometheus instance as a time-series store
and wish to run queries for analytical purposes from a Scala application.

It is in a draft state at the moment: we will avoid deep API changes if possible, but can't exclude them.

See the [changelog](CHANGELOG.md) for more details.
