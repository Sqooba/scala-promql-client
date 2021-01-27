package io.sqooba.oss.promql

/**
 * The goal is to have a single trait as supertype for all our error
 * This should include applicative error as well as network, communication
 * or any other error kind
 * This will prove useful due to the way errors are handled by ZIO
 */
sealed trait PrometheusError                  extends Exception
case class PrometheusClientError(msg: String) extends PrometheusError

/*
 This case class is pretty redundant with ErrorResponse from PrometheusResponse, it carries all the
 same information, except the potential data return alongside the errors
 */
case class PrometheusErrorResponse(
  errorType: String,
  error: String,
  warnings: Option[Seq[String]]
) extends PrometheusError {
  override def toString: String = error
}
