package zio.quill.vertx

import io.vertx.sqlclient.{Pool, PoolOptions}
import org.scalatest.BeforeAndAfterAll
import zio.{Exit, Runtime, Scope, Unsafe, ZEnvironment, ZIO, ZLayer}
import zio.stream.{ZSink, ZStream}
import zio.quill.base.Spec

/** Lifecycle shared by the native non-PostgreSQL integration suites. */
trait MultiSqlZioVertxSpecSupport extends Spec with BeforeAndAfterAll {
  protected def poolLayer(maxSize: Int): ZLayer[Any, Throwable, Pool]

  private var poolScope: Scope.Closeable              = _
  private var poolEnvironment: ZEnvironment[Pool]     = _
  private var observerEnvironment: ZEnvironment[Pool] = _

  protected final def pool: Pool           = poolEnvironment.get[Pool]
  protected final def observerPool: Pool   = observerEnvironment.get[Pool]
  protected final def poolIsReady: Boolean = poolEnvironment != null

  protected final def unsafeRun[A](effect: ZIO[Pool, Throwable, A]): A =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(effect.provideEnvironment(poolEnvironment)).getOrThrow()
    }

  protected final def collect[A](stream: ZStream[Pool, Throwable, A]): List[A] =
    unsafeRun(stream.run(ZSink.collectAll).map(_.toList))

  protected final def unsafeRunObserver[A](effect: ZIO[Pool, Throwable, A]): A =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(effect.provideEnvironment(observerEnvironment)).getOrThrow()
    }

  override def beforeAll(): Unit = {
    super.beforeAll()
    Unsafe.unsafe { implicit unsafe =>
      val runtime = Runtime.default
      poolScope = runtime.unsafe.run(Scope.make).getOrThrow()
      poolEnvironment = runtime.unsafe.run(
        poolLayer(1).build.provideEnvironment(ZEnvironment(poolScope))
      ).getOrThrow()
      observerEnvironment = runtime.unsafe.run(
        poolLayer(2).build.provideEnvironment(ZEnvironment(poolScope))
      ).getOrThrow()
    }
  }

  override def afterAll(): Unit =
    try {
      if (poolScope != null)
        Unsafe.unsafe { implicit unsafe =>
          Runtime.default.unsafe.run(poolScope.close(Exit.unit)).getOrThrow()
        }
    } finally super.afterAll()
}

private[vertx] object MultiSqlTestEnv {
  def first(default: String, names: String*): String =
    names.iterator.flatMap(sys.env.get).take(1).toList.headOption.getOrElse(default)

  def value(primary: String, fallback: String, default: String): String =
    sys.env.get(primary).orElse(sys.env.get(fallback)).getOrElse(default)

  def port(primary: String, fallback: String, default: Int): Int =
    value(primary, fallback, default.toString).toInt
}
