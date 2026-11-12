package zio.quill.vertx

import java.util.UUID
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers._
import zio.quill.base.Spec
import zio.quill.context.vertx.VertxRuntime
import zio.ZIO
import zio.quill.Query

class VertxPostgresNativeSpec extends Spec with VertxPostgresSpecSupport with BeforeAndAfterEach {
  import context._

  case class NativeRow(id: Int, label: Option[String], payload: Array[Byte], token: UUID, numbers: List[Int])
  val rows = quote(querySchema[NativeRow]("quill_vertx_native_rows"))

  private def sql(statement: String): Unit =
    unsafeRun(VertxRuntime.fromFuture(pool.query(statement).execute()).unit)

  override def beforeAll(): Unit = {
    super.beforeAll()
    sql("DROP TABLE IF EXISTS quill_vertx_native_rows")
    sql("CREATE TABLE quill_vertx_native_rows (id INT PRIMARY KEY, label TEXT NULL, payload BYTEA NOT NULL, token UUID NOT NULL, numbers INT[] NOT NULL)")
  }

  override def afterAll(): Unit =
    try if (poolIsReady) sql("DROP TABLE IF EXISTS quill_vertx_native_rows")
    finally super.afterAll()

  override def beforeEach(): Unit = {
    super.beforeEach()
    sql("TRUNCATE TABLE quill_vertx_native_rows")
  }

  "native PostgreSQL CRUD and codecs" in {
    val token = UUID.fromString("20e4c76a-7958-4fc0-a521-57811a34993d")
    val row   = NativeRow(1, Some("first"), Array[Byte](0, 1, -1), token, List(2, 5, 8))
    unsafeRun(context.run(rows.insertValue(lift(row)))) shouldBe 1L
    val found = unsafeRun(context.run(rows.filter(_.id == lift(1))))
    found.map(r => (r.id, r.label, r.payload.toList, r.token, r.numbers)) shouldBe
      List((1, Some("first"), List[Byte](0, 1, -1), token, List(2, 5, 8)))

    unsafeRun(context.run(rows.filter(_.id == lift(1)).update(_.label -> lift(None: Option[String])))) shouldBe 1L
    unsafeRun(context.run(rows.filter(_.id == lift(1)))).head.label shouldBe None
    unsafeRun(context.run(rows.filter(_.id == lift(1)).delete)) shouldBe 1L
    unsafeRun(context.run(rows)) shouldBe empty
  }

  "native PostgreSQL insert returning and batch actions" in {
    val first    = NativeRow(10, Some("ten"), Array[Byte](10), UUID.randomUUID(), List(10))
    val second   = NativeRow(11, None, Array[Byte](11), UUID.randomUUID(), List(11, 12))
    val inserted = unsafeRun(context.run(liftQuery(List(first, second)).foreach(r => rows.insertValue(r))))
    inserted shouldBe List(1L, 1L)
    val returned = unsafeRun(context.run(rows.insertValue(lift(NativeRow(12, Some("twelve"), Array[Byte](12), UUID.randomUUID(), List(12)))).returning(r => r.id)))
    returned shouldBe 12
  }

  "transactions roll back and nested transactions share the scoped connection" in {
    val rollbackId = 20
    val failed     = context.transaction {
      context.run(rows.insertValue(lift(NativeRow(rollbackId, None, Array[Byte](20), UUID.randomUUID(), List(20))))) *>
        zio.ZIO.fail(new RuntimeException("rollback marker"))
    }
    unsafeRun(failed.either).isLeft shouldBe true
    unsafeRun(context.run(rows.filter(_.id == lift(rollbackId)))) shouldBe empty

    unsafeRun(context.transaction {
      context.run(rows.insertValue(lift(NativeRow(21, None, Array[Byte](21), UUID.randomUUID(), List(21))))) *>
        context.transaction(context.run(rows.insertValue(lift(NativeRow(22, None, Array[Byte](22), UUID.randomUUID(), List(22))))))
    }) shouldBe 1L
    unsafeRun(context.run(rows.filter(r => r.id == lift(21) || r.id == lift(22)))).map(_.id).sorted shouldBe List(21, 22)
  }

  "stream take closes its cursor and returns the single-connection pool" in {
    val records = (30 to 39).map(id => NativeRow(id, None, Array[Byte](id.toByte), UUID.randomUUID(), List(id))).toList
    unsafeRun(context.run(liftQuery(records).foreach(r => rows.insertValue(r)))) shouldBe List.fill(records.size)(1L)
    val firstTwo = collect(context.stream(rows.sortBy(_.id), 1).take(2))
    firstTwo.map(_.id) shouldBe List(30, 31)
    unsafeRun(context.run(rows.filter(_.id == lift(39)))).map(_.id) shouldBe List(39)
  }

  "a failed native query releases its connection for the next operation" in {
    val duplicate = NativeRow(40, None, Array[Byte](40), UUID.randomUUID(), List(40))
    unsafeRun(context.run(rows.insertValue(lift(duplicate)))) shouldBe 1L
    unsafeRun(context.run(rows.insertValue(lift(duplicate))).either).isLeft shouldBe true
    unsafeRun(context.run(rows.filter(_.id == lift(40)))).map(_.id) shouldBe List(40)
  }

  "interrupting a context query cancels the PostgreSQL statement and releases the single-connection pool" in {
    val taggedQuery = quote(infix"SELECT 1 FROM (SELECT pg_sleep(30)) AS delayed /* quill_vertx_cancel */".as[Query[Int]])
    val activeSql   = "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname = current_database() AND state = 'active' AND wait_event = 'PgSleep' AND query LIKE '%quill_vertx_cancel%')"

    def isTaggedQueryActive: zio.Task[Boolean] =
      VertxRuntime.fromFuture(observerPool.query(activeSql).execute()).map(_.iterator().next().getBoolean(0))

    def awaitActivity(expected: Boolean, attempts: Int): zio.Task[Unit] =
      isTaggedQueryActive.flatMap { active =>
        if (active == expected) ZIO.unit
        else if (attempts <= 0) ZIO.fail(new RuntimeException(s"tagged PostgreSQL query activity did not become $expected"))
        else ZIO.sleep(zio.Duration.fromMillis(50)) *> awaitActivity(expected, attempts - 1)
      }

    val check = for {
      queryFiber     <- context.run(taggedQuery).unit.fork
      _              <- awaitActivity(expected = true, attempts = 100)
      cancelStarted  <- zio.Clock.nanoTime
      _              <- queryFiber.interrupt
      cancelFinished <- zio.Clock.nanoTime
      _              <- ZIO.fail(new RuntimeException("query interruption took five seconds or more")).when(cancelFinished - cancelStarted >= 5000000000L)
      _              <- awaitActivity(expected = false, attempts = 100)
      reusable       <- context.run(quote(infix"SELECT 1".as[Query[Int]])).timeout(zio.Duration.fromSeconds(5))
      _              <- ZIO.fromOption(reusable).orElseFail(new RuntimeException("pool connection was not reusable after query interruption"))
    } yield ()
    unsafeRun(check)
  }
}
