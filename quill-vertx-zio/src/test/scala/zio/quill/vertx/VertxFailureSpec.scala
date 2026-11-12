package zio.quill.vertx

import io.vertx.sqlclient.Tuple
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers._
import zio.{Duration, Ref, ZIO}
import zio.quill.{MappedEncoding, Query}
import zio.quill.context.vertx.VertxRuntime

object VertxFailureSpec {
  case class DeferredUniqueRow(id: Int, uniqueValue: Int)

  case class ExplodingMapped(value: Int)
  implicit val explodingMappedDecoder: MappedEncoding[Int, ExplodingMapped] =
    MappedEncoding(_ => throw new IllegalArgumentException("intentional mapped decoder failure"))
}

class VertxFailureSpec extends VertxPostgresSpecSupport with BeforeAndAfterEach {
  import VertxFailureSpec._
  import context._

  private val deferredRows = quote(
    querySchema[DeferredUniqueRow]("quill_vertx_failure_deferred", _.uniqueValue -> "unique_value")
  )

  private def sql(statement: String): Unit =
    unsafeRun(VertxRuntime.fromFuture(pool.query(statement).execute()).unit)

  private def bounded[A](effect: ZIO[io.vertx.sqlclient.Pool, Throwable, A]): A =
    unsafeRun(effect.timeout(Duration.fromSeconds(5)).flatMap(result =>
      ZIO.fromOption(result).orElseFail(new RuntimeException("native PostgreSQL failure operation timed out"))
    ))

  override def beforeAll(): Unit = {
    super.beforeAll()
    sql("DROP TABLE IF EXISTS quill_vertx_failure_deferred")
    sql(
      "CREATE TABLE quill_vertx_failure_deferred (" +
        "id INT PRIMARY KEY, unique_value INT NOT NULL, " +
        "CONSTRAINT quill_vertx_failure_deferred_unique UNIQUE (unique_value) " +
        "DEFERRABLE INITIALLY DEFERRED)"
    )
  }

  override def afterAll(): Unit =
    try if (poolIsReady) sql("DROP TABLE IF EXISTS quill_vertx_failure_deferred")
    finally super.afterAll()

  override def beforeEach(): Unit = {
    super.beforeEach()
    sql("TRUNCATE TABLE quill_vertx_failure_deferred")
  }

  "a deferred uniqueness violation fails at commit, rolls back all writes and returns the pool connection" in {
    val observed = Ref.make(false).flatMap { reachedSecondWrite =>
      val transaction = context.transaction {
        for {
          first  <- context.run(deferredRows.insertValue(lift(DeferredUniqueRow(1, 77))))
          second <- context.run(deferredRows.insertValue(lift(DeferredUniqueRow(2, 77))))
          _      <- reachedSecondWrite.set(true)
        } yield (first, second)
      }
      transaction.either.flatMap(result => reachedSecondWrite.get.map(result -> _))
    }

    val (result, bothWritesSucceeded) = bounded(observed)
    bothWritesSucceeded shouldBe true
    result.isLeft shouldBe true
    result.swap.toOption match {
      case Some(error: io.vertx.pgclient.PgException) => error.getCode shouldBe "23505"
      case other                                      => fail(s"expected PostgreSQL unique violation at commit, got $other")
    }
    bounded(context.run(deferredRows.map(_.id))) shouldBe empty
    bounded(context.run(deferredRows.insertValue(lift(DeferredUniqueRow(3, 88))))) shouldBe 1L
    bounded(context.run(deferredRows.map(_.id))) shouldBe List(3)
  }

  "terminating the transaction backend after a write fails the transaction and leaves the pool reusable" in {
    val backendPid          = quote(infix"SELECT pg_backend_pid()".as[Query[Int]])
    val terminationObserved = Ref.make(false).flatMap { terminationSucceeded =>
      val terminateAfterWrite = context.transaction {
        for {
          _          <- context.run(deferredRows.insertValue(lift(DeferredUniqueRow(10, 100))))
          pid        <- context.run(backendPid).map(_.head)
          terminated <- VertxRuntime.fromFuture(
                          observerPool.preparedQuery("SELECT pg_terminate_backend($1)").execute(Tuple.tuple().addInteger(pid))
                        ).map(_.iterator().next().getBoolean(0))
          _ <- terminationSucceeded.set(terminated)
          _ <- ZIO.fail(new RuntimeException(s"could not terminate transaction backend $pid")).unless(terminated)
        } yield ()
      }
      terminateAfterWrite.either.flatMap(result => terminationSucceeded.get.map(result -> _))
    }

    val (terminationResult, backendWasTerminated) = bounded(terminationObserved)
    backendWasTerminated shouldBe true
    terminationResult.isLeft shouldBe true
    bounded(context.run(deferredRows.map(_.id))) shouldBe empty
    bounded(context.run(deferredRows.insertValue(lift(DeferredUniqueRow(11, 101))))) shouldBe 1L
    bounded(context.run(deferredRows.map(_.id))) shouldBe List(11)
  }

  "a throwing mapped decoder is a typed query failure and the native pool remains usable" in {
    val badDecode = quote(infix"SELECT 1::integer".as[Query[ExplodingMapped]])

    val failure = bounded(context.run(badDecode).either)
    failure.isLeft shouldBe true
    failure.swap.toOption.map(_.getMessage).getOrElse("") should include("intentional mapped decoder failure")
    bounded(context.run(quote(infix"SELECT 42".as[Query[Int]]))) shouldBe List(42)
  }
}
