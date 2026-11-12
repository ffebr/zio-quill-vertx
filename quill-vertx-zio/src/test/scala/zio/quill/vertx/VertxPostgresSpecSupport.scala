package zio.quill.vertx

import io.vertx.pgclient.PgConnectOptions
import io.vertx.sqlclient.{Pool, PoolOptions}
import org.scalatest.BeforeAndAfterAll
import zio.{Exit, Runtime, Scope, Unsafe, ZEnvironment, ZIO}
import zio.stream.{ZSink, ZStream}
import zio.quill.{Literal, PostgresZioVertxContext}
import zio.quill.context.vertx.VertxPool

/**
 * Owns one native pool and its layer scope for the lifetime of a ScalaTest
 * suite.
 */
trait VertxPostgresSpecSupport extends zio.quill.base.Spec {
  val context: PostgresZioVertxContext[Literal] = new PostgresZioVertxContext(Literal)
  import context._

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

  private def connectOptions(host: String, port: Int, database: String, user: String, password: String): PgConnectOptions =
    new PgConnectOptions().setHost(host).setPort(port).setDatabase(database).setUser(user).setPassword(password)

  override def beforeAll(): Unit = {
    super.beforeAll()
    // Defaults match the repository DB build; tools/vertx/test.sh selects its isolated harness.
    val host     = sys.env.getOrElse("POSTGRES_HOST", "127.0.0.1")
    val port     = sys.env.get("POSTGRES_PORT").map(_.toInt).getOrElse(15432)
    val database = sys.env.getOrElse("POSTGRES_DB", "quill_test")
    val user     = sys.env.getOrElse("POSTGRES_USER", "postgres")
    val password = sys.env.getOrElse("POSTGRES_PASSWORD", "postgres")
    Unsafe.unsafe { implicit unsafe =>
      val runtime = Runtime.default
      poolScope = runtime.unsafe.run(Scope.make).getOrThrow()
      poolEnvironment = runtime.unsafe.run(
        VertxPool.live(connectOptions(host, port, database, user, password), new PoolOptions().setMaxSize(1))
          .build.provideEnvironment(ZEnvironment(poolScope))
      ).getOrThrow()
      observerEnvironment = runtime.unsafe.run(
        VertxPool.live(connectOptions(host, port, database, user, password), new PoolOptions().setMaxSize(2))
          .build.provideEnvironment(ZEnvironment(poolScope))
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
