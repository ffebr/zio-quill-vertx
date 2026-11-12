package zio.quill.vertx

import io.vertx.core.{Vertx, VertxOptions}
import io.vertx.pgclient.{PgConnectOptions, PgException}
import io.vertx.sqlclient.{Pool, PoolOptions}
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.matchers.should.Matchers._
import zio.{Duration, Exit, Runtime, Scope, Unsafe, ZEnvironment, ZIO}
import zio.quill.Query
import zio.quill.context.vertx.{VertxPool, VertxRuntime}

class VertxTransportSpec extends VertxPostgresSpecSupport {
  import context._

  private def connectOptions(password: String): PgConnectOptions =
    new PgConnectOptions()
      .setHost(sys.env.getOrElse("POSTGRES_HOST", "127.0.0.1"))
      .setPort(sys.env.get("POSTGRES_PORT").map(_.toInt).getOrElse(15432))
      .setDatabase(sys.env.getOrElse("POSTGRES_DB", "quill_test"))
      .setUser(sys.env.getOrElse("POSTGRES_USER", "postgres"))
      .setPassword(password)

  private def bounded[A](effect: ZIO[Pool, Throwable, A], environment: ZEnvironment[Pool]): A =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(
        effect.timeout(Duration.fromSeconds(5)).flatMap(result =>
          ZIO.fromOption(result).orElseFail(new RuntimeException("native PostgreSQL operation timed out"))
        ).provideEnvironment(environment)
      ).getOrThrow()
    }

  "a native authentication failure keeps PostgreSQL's SQLSTATE and does not affect the shared pool" in {
    val rightPassword    = sys.env.getOrElse("POSTGRES_PASSWORD", "postgres")
    val badPassword      = rightPassword + "-deliberately-wrong"
    val scope            = Unsafe.unsafe { implicit unsafe => Runtime.default.unsafe.run(Scope.make).getOrThrow() }
    val wrongEnvironment = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(
        VertxPool.live(connectOptions(badPassword), new PoolOptions().setMaxSize(1))
          .build.provideEnvironment(ZEnvironment(scope))
      ).getOrThrow()
    }

    try {
      val result = bounded(VertxRuntime.fromFuture(wrongEnvironment.get[Pool].query("SELECT 1").execute()).unit.either, wrongEnvironment)
      result.swap.toOption match {
        case Some(error: PgException) => error.getCode shouldBe "28P01"
        case other                    => fail(s"expected native PostgreSQL authentication error with SQLSTATE 28P01, got $other")
      }
      unsafeRun(
        context.run(quote(infix"SELECT 1".as[Query[Int]])).timeout(Duration.fromSeconds(5)).flatMap(result =>
          ZIO.fromOption(result).orElseFail(new RuntimeException("shared pool health check timed out"))
        )
      ) shouldBe List(1)
    } finally Unsafe.unsafe { implicit unsafe => Runtime.default.unsafe.run(scope.close(Exit.unit)).getOrThrow() }
  }

  "a native query leaves its Vert.x event loop responsive while PostgreSQL sleeps" in {
    val vertx       = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(1))
    val scope       = Unsafe.unsafe { implicit unsafe => Runtime.default.unsafe.run(Scope.make).getOrThrow() }
    val environment = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(
        VertxPool.fromVertx(vertx, connectOptions(sys.env.getOrElse("POSTGRES_PASSWORD", "postgres")), new PoolOptions().setMaxSize(1))
          .build.provideEnvironment(ZEnvironment(scope))
      ).getOrThrow()
    }
    val heartbeats = new AtomicInteger(0)
    val timerId    = vertx.setPeriodic(10L, _ => heartbeats.incrementAndGet())
    val tagged     = quote(infix"SELECT 1 FROM (SELECT pg_sleep(30)) AS delayed /* quill_vertx_heartbeat */".as[Query[Int]])
    val activeSql  = "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname = current_database() AND state = 'active' AND wait_event = 'PgSleep' AND query LIKE '%quill_vertx_heartbeat%')"

    def isTaggedQueryActive: ZIO[Any, Throwable, Boolean] =
      VertxRuntime.fromFuture(observerPool.query(activeSql).execute()).map(_.iterator().next().getBoolean(0))

    def awaitActivity(expected: Boolean, attempts: Int): ZIO[Any, Throwable, Unit] =
      isTaggedQueryActive.flatMap { active =>
        if (active == expected) ZIO.unit
        else if (attempts <= 0) ZIO.fail(new RuntimeException(s"tagged PostgreSQL query activity did not become $expected"))
        else ZIO.sleep(Duration.fromMillis(50)) *> awaitActivity(expected, attempts - 1)
      }

    def awaitHeartbeats(start: Int, target: Int, queryFiber: zio.Fiber.Runtime[Throwable, List[Int]], attempts: Int): ZIO[Any, Throwable, Unit] =
      for {
        completed <- queryFiber.poll
        beats     <- ZIO.succeed(heartbeats.get())
        _         <-
          if (completed.isDefined) ZIO.fail(new RuntimeException("tagged PostgreSQL sleep query completed before heartbeat observation"))
          else if (beats >= target) ZIO.unit
          else if (attempts <= 0) ZIO.fail(new RuntimeException(s"event-loop heartbeat advanced only ${beats - start} times during active pg_sleep"))
          else ZIO.sleep(Duration.fromMillis(10)) *> awaitHeartbeats(start, target, queryFiber, attempts - 1)
      } yield ()

    try {
      val check = ZIO.scoped {
        for {
          queryFiber <- context.run(tagged).provideEnvironment(environment).forkScoped
          _          <- awaitActivity(expected = true, attempts = 100).timeout(Duration.fromSeconds(5)).flatMap(value =>
                 ZIO.fromOption(value).orElseFail(new RuntimeException("tagged PostgreSQL query did not start within five seconds"))
               )
          start <- ZIO.succeed(heartbeats.get())
          _     <- awaitHeartbeats(start, start + 3, queryFiber, attempts = 500)
          _     <- queryFiber.interrupt
          _     <- awaitActivity(expected = false, attempts = 100).timeout(Duration.fromSeconds(5)).flatMap(value =>
                 ZIO.fromOption(value).orElseFail(new RuntimeException("tagged PostgreSQL query remained active after interruption"))
               )
          reusable <- context.run(quote(infix"SELECT 1".as[Query[Int]])).provideEnvironment(environment)
                        .timeout(Duration.fromSeconds(5)).flatMap(value =>
                          ZIO.fromOption(value).orElseFail(new RuntimeException("native pool was not reusable after query interruption"))
                        )
          _ <- ZIO.fail(new RuntimeException("native pool returned an unexpected result after interruption")).unless(reusable == List(1))
        } yield ()
      }
      Unsafe.unsafe { implicit unsafe => Runtime.default.unsafe.run(check).getOrThrow() }
    } finally {
      vertx.cancelTimer(timerId)
      try Unsafe.unsafe { implicit unsafe => Runtime.default.unsafe.run(scope.close(Exit.unit)).getOrThrow() }
      finally Unsafe.unsafe { implicit unsafe => Runtime.default.unsafe.run(VertxRuntime.fromFuture(vertx.close())).getOrThrow() }
    }
  }
}
