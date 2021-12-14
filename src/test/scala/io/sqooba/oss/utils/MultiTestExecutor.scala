package io.sqooba.oss.utils

import zio._
import zio.test._

abstract class MultiTestExecutor[+R <: Has[_], E] extends TestExecutor[R, E]

object MultiTestExecutor {
  def fromList[R <: Annotations, E](
    env: Seq[(String, Layer[Nothing, R])]
  ): TestExecutor[R, E] = new TestExecutor[R, E] {

    def run(spec: ZSpec[R, E], defExec: ExecutionStrategy): UIO[ExecutedSpec[E]] = {
      val runs: UIO[Seq[ExecutedSpec[E]]] = UIO.collectAll(
        env.map {
          case (v: String, vmLayer: Layer[Nothing, R]) => TestExecutor.default(vmLayer).run(suite(v)(spec), defExec)
        }
      )

      val eList: Seq[ExecutedSpec[E]] = zio.Runtime.default
        .unsafeRun(runs)

      val label = "multiversion"
      val specs = ExecutedSpec.multiple(Chunk.fromIterable(eList))

      IO.succeed(
        ExecutedSpec.labeled(label, specs)
      )
    }
    // unsed, but present in the signature of "super"
    val environment: Layer[Nothing, R] = env.head._2
  }
}
