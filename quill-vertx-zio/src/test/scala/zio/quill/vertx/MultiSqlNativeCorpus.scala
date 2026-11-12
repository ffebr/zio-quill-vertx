package zio.quill.vertx

import io.vertx.db2client.DB2ConnectOptions
import io.vertx.mysqlclient.MySQLConnectOptions
import io.vertx.sqlclient.{Pool, PoolOptions, Tuple}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers._
import zio.{Duration, Promise, ZIO}
import zio.quill.{DB2ZioVertxContext, Literal, MappedEncoding, MysqlZioVertxContext, Query}
import zio.quill.context.vertx.{VertxBaseDecoders, VertxBaseEncoders, VertxPool, VertxRuntime}
import MultiSqlNativeTestOps.awaitPending

import java.time.LocalDateTime
import java.util.UUID

object MultiSqlNativeFixtures {
  final case class Label(value: String)
  object Label {
    implicit val encode: MappedEncoding[Label, String] = MappedEncoding(_.value)
    implicit val decode: MappedEncoding[String, Label] = MappedEncoding(Label.apply)
  }
  final case class NativeRow(id: Long, token: UUID, amount: BigDecimal, occurred: LocalDateTime, label: Label, note: Option[String])
  final case class GeneratedRow(id: Long, label: String)
  final case class RenameRow(from: String, to: String)
  final case class IdentityOnly(id: Long)
}

/**
 * Shared lifecycle and assertion names; QDSL expressions live in each
 * concretely typed context runner.
 */
trait MultiSqlNativeCorpus extends MultiSqlZioVertxSpecSupport with BeforeAndAfterEach {
  import MultiSqlNativeFixtures._

  protected def createRows: String
  protected def dropRows: String
  protected def clearRows: String
  protected def createGenerated: String
  protected def dropGenerated: String
  protected def clearGenerated: String

  protected def verifyCrud(): Unit
  protected def verifyGeneratedBatch(): Unit
  protected def verifyTransactions(): Unit
  protected def verifyStreaming(): Unit
  protected def verifyPrepared(): Unit
  protected def verifyNativeError(): Unit
  protected def verifyTransportAbort(): Unit

  protected final def executeSql(statement: String): Unit =
    unsafeRun(VertxRuntime.fromFuture(pool.query(statement).execute()).unit)

  protected final def dropTable(statement: String): Unit =
    unsafeRun(VertxRuntime.fromFuture(pool.query(statement).execute()).either).fold(
      error => if (MultiSqlNativeTestOps.sqlState(error).contains("42704")) () else throw error,
      _ => ()
    )

  override def beforeAll(): Unit = {
    super.beforeAll()
    dropTable(dropRows)
    executeSql(createRows)
    dropTable(dropGenerated)
    executeSql(createGenerated)
  }

  override def afterAll(): Unit =
    try {
      if (poolIsReady) {
        dropTable(dropGenerated)
        dropTable(dropRows)
      }
    } finally super.afterAll()

  override def beforeEach(): Unit = {
    super.beforeEach()
    executeSql(clearGenerated)
    executeSql(clearRows)
  }

  "native SQL CRUD, nullable values, UUID, decimal, time and mapped values" in { verifyCrud() }
  "generated keys, returning batch order and batch update counts" in { verifyGeneratedBatch() }
  "transaction commit, typed failure, defect, nesting and interruption roll back" in { verifyTransactions() }
  "bounded cursor early take, empty and failure all release a one-connection pool" in { verifyStreaming() }
  "prepared query/action reuse bindings and synchronous probe reports its native limitation" in { verifyPrepared() }
  "a native constraint error retains driver diagnostics and returns the pool connection" in { verifyNativeError() }
  "interrupting a confirmed pending driver query promptly aborts and replaces its physical session" in { verifyTransportAbort() }
}

trait MysqlNativeSupport extends MultiSqlNativeCorpus {
  import MultiSqlNativeFixtures._

  val context: MysqlZioVertxContext[Literal] with VertxBaseEncoders with VertxBaseDecoders =
    new MysqlZioVertxContext(Literal)
  import context._

  private val maria  = this.getClass.getSimpleName.startsWith("MariaDB")
  private val prefix = if (maria) "MARIADB" else "MYSQL"

  override protected def poolLayer(maxSize: Int) = VertxPool.mysql(
    new MySQLConnectOptions()
      .setHost(MultiSqlTestEnv.value(s"${prefix}_HOST", "MYSQL_HOST", "127.0.0.1"))
      .setPort(MultiSqlTestEnv.port(s"${prefix}_PORT", "MYSQL_PORT", if (maria) 53307 else 53306))
      .setDatabase(MultiSqlTestEnv.first("quill_vertx_test", s"${prefix}_DB", s"${prefix}_DATABASE", "MYSQL_DB", "MYSQL_DATABASE"))
      .setUser(MultiSqlTestEnv.value(s"${prefix}_USER", "MYSQL_USER", "root"))
      .setPassword(MultiSqlTestEnv.value(s"${prefix}_PASSWORD", "MYSQL_PASSWORD", "quill_test_only")),
    new PoolOptions().setMaxSize(maxSize)
  )
  override protected val createRows      = "CREATE TABLE quill_m07_native_rows (id BIGINT PRIMARY KEY, token CHAR(36) NOT NULL, amount DECIMAL(18,4) NOT NULL, occurred DATETIME(6) NOT NULL, label VARCHAR(80) NOT NULL, note VARCHAR(200) NULL)"
  override protected val dropRows        = "DROP TABLE IF EXISTS quill_m07_native_rows"
  override protected val clearRows       = "DELETE FROM quill_m07_native_rows"
  override protected val createGenerated = "CREATE TABLE quill_m07_generated_rows (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, label VARCHAR(80) NOT NULL)"
  override protected val dropGenerated   = "DROP TABLE IF EXISTS quill_m07_generated_rows"
  override protected val clearGenerated  = "DELETE FROM quill_m07_generated_rows"

  override def beforeAll(): Unit = {
    super.beforeAll()
    val version = unsafeRun(VertxRuntime.fromFuture(pool.query("SELECT VERSION()").execute())
      .map(_.iterator().next().getString(0)))
    val isMariaDb = version.toLowerCase(java.util.Locale.ROOT).contains("mariadb")
    if (isMariaDb != maria)
      throw new IllegalStateException(
        s"${this.getClass.getSimpleName} expected ${if (maria) "MariaDB" else "MySQL"}, connected to $version"
      )
  }

  private lazy val rows      = quote(querySchema[NativeRow]("quill_m07_native_rows"))
  private lazy val generated = quote(querySchema[GeneratedRow]("quill_m07_generated_rows"))

  override protected def verifyCrud(): Unit = {
    val stamp = LocalDateTime.of(2024, 2, 3, 4, 5, 6, 123000000)
    val token = UUID.fromString("681f7468-8cf5-4197-ad84-1430226aace1")
    val row   = NativeRow(1L, token, BigDecimal("19.8750"), stamp, Label("typed"), None)
    unsafeRun(context.run(rows.insertValue(lift(row)))) shouldBe 1L
    unsafeRun(context.run(rows.filter(_.id == lift(1L)))) shouldBe List(row)
    unsafeRun(context.run(rows.filter(_.id == lift(1L)).update(_.note -> lift(Some("updated"): Option[String])))) shouldBe 1L
    unsafeRun(context.run(rows.filter(_.id == lift(1L)).map(_.note))) shouldBe List(Some("updated"))
    unsafeRun(context.run(rows.filter(_.id == lift(1L)).delete)) shouldBe 1L
    unsafeRun(context.run(rows)) shouldBe empty
    unsafeRun(context.run(quote(lift("42").toInt))) shouldBe 42
  }

  override protected def verifyGeneratedBatch(): Unit = {
    val first = unsafeRun(context.run(generated.insert(_.label -> lift("first")).returningGenerated(_.id)))
    first should be > 0L
    val labels = List("second", "third", "fourth")
    val ids    = unsafeRun(context.run(liftQuery(labels).foreach(label => generated.insert(_.label -> label).returningGenerated(_.id))))
    ids should have size labels.size
    ids shouldBe ids.sorted
    ids.forall(_ > first) shouldBe true
    unsafeRun(context.run(generated.sortBy(_.id).map(_.label))) shouldBe ("first" :: labels)
    unsafeRun(context.run(generated.sortBy(_.id).drop(1).take(2).map(_.label))) shouldBe List("second", "third")

    val batchCounts = unsafeRun(context.run(liftQuery(List(RenameRow("first", "first-x"), RenameRow("second", "second-x"))).foreach(change =>
      generated.filter(_.label == change.from).update(_.label -> change.to)
    )))
    batchCounts shouldBe List(1L, 1L)
    unsafeRun(context.run(generated.sortBy(_.id).map(_.label))).take(2) shouldBe List("first-x", "second-x")

    unsafeRun(context.run(generated.filter(_.label != lift("missing")).update(_.label -> lift("batch-updated")))) shouldBe 4L
    unsafeRun(context.run(generated.sortBy(_.id).map(_.label))) shouldBe List.fill(4)("batch-updated")
  }

  override protected def verifyTransactions(): Unit = {
    def row(id: Long, label: String): NativeRow =
      NativeRow(id, UUID.randomUUID(), BigDecimal("1.25"), LocalDateTime.of(2024, 1, 1, 0, 0), Label(label), None)

    unsafeRun(context.transaction(context.run(rows.insertValue(lift(row(10, "commit")))))) shouldBe 1L
    val typed = context.transaction(context.run(rows.insertValue(lift(row(11, "typed")))) *> ZIO.fail(new IllegalStateException("rollback typed")))
    unsafeRun(typed.either).isLeft shouldBe true
    val defect     = context.transaction(context.run(rows.insertValue(lift(row(12, "defect")))) *> ZIO.die(new IllegalArgumentException("rollback defect")))
    val defectExit = unsafeRun(defect.exit)
    defectExit.isFailure shouldBe true
    defectExit.causeOption.flatMap(_.defects.headOption).map(_.getMessage) shouldBe Some("rollback defect")

    unsafeRun(context.transaction {
      context.run(rows.insertValue(lift(row(13, "outer")))) *>
        context.transaction(context.run(rows.insertValue(lift(row(14, "nested")))))
    }) shouldBe 1L

    val interrupted = for {
      inserted <- Promise.make[Nothing, Unit]
      fiber    <- context.transaction {
                 context.run(rows.insertValue(lift(row(15, "interrupted")))) *> inserted.succeed(()) *> ZIO.sleep(Duration.fromSeconds(30))
               }.fork
      _ <- inserted.await
      _ <- fiber.interrupt
    } yield ()
    unsafeRun(interrupted)
    unsafeRun(context.run(rows.sortBy(_.id).map(_.id))) shouldBe List(10L, 13L, 14L)
  }

  override protected def verifyStreaming(): Unit = {
    val values = (20L to 28L).map(id => NativeRow(id, UUID.randomUUID(), BigDecimal(id), LocalDateTime.of(2024, 3, 1, 0, 0), Label(s"row-$id"), None)).toList
    unsafeRun(context.run(liftQuery(values).foreach(value => rows.insertValue(value)))) shouldBe List.fill(values.size)(1L)
    collect(context.stream(rows.sortBy(_.id), 2).take(2)).map(_.id) shouldBe List(20L, 21L)
    collect(context.stream(rows.sortBy(_.id), 2)).map(_.id) shouldBe (20L to 28L).toList
    collect(context.stream(rows.filter(_.id < lift(0L)), 2)) shouldBe empty
    val bad = quote(infix"SELECT * FROM quill_m07_missing_table".as[Query[NativeRow]])
    collect(context.stream(bad, 1).take(1).either).exists(_.isLeft) shouldBe true
    unsafeRun(context.run(rows.filter(_.id == lift(28L)))).map(_.id) shouldBe List(28L)
  }

  override protected def verifyPrepared(): Unit = {
    val inserted = for {
      action <- context.prepare(generated.insert(_.label -> lift("prepared")))
      before <- context.run(generated)
      _      <- ZIO.attempt(before shouldBe empty)
      _      <- action.execute
      query  <- context.prepare(generated.filter(_.label == lift("prepared")))
      first  <- query.execute.map(_.iterator().hasNext)
      again  <- query.execute.map(_.iterator().hasNext)
    } yield (first, again)
    unsafeRun(inserted) shouldBe ((true, true))
    context.probe("SELECT 1").failed.get shouldBe a[UnsupportedOperationException]
  }

  override protected def verifyNativeError(): Unit = {
    val row = NativeRow(30L, UUID.randomUUID(), BigDecimal("2.50"), LocalDateTime.of(2024, 4, 1, 0, 0), Label("unique"), None)
    unsafeRun(context.run(rows.insertValue(lift(row)))) shouldBe 1L
    val failure = unsafeRun(context.run(rows.insertValue(lift(row))).either).swap.toOption.get
    failure.getMessage should not be empty
    MultiSqlNativeTestOps.sqlState(failure) shouldBe defined
    unsafeRun(context.run(rows.filter(_.id == lift(30L)))).map(_.id) shouldBe List(30L)
  }

  override protected def verifyTransportAbort(): Unit = {
    val identity                   = quote(infix"SELECT CONNECTION_ID()".as[Query[Long]])
    val delayed                    = quote(infix"SELECT SLEEP(30) /* quill_m07_transport_abort */".as[Query[Int]])
    val sessionId                  = unsafeRun(context.run(identity)).head
    def pending: zio.Task[Boolean] = VertxRuntime.fromFuture(observerPool.query(
      s"SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE ID = $sessionId AND INFO LIKE '%quill_m07_transport_abort%'"
    ).execute()).map(_.iterator().next().getLong(0) > 0L)
    val exercise = for {
      fiber <- context.run(delayed).fork
      _     <- awaitPending(pending, 100)
      start <- zio.Clock.nanoTime
      _     <- fiber.interrupt
      end   <- zio.Clock.nanoTime
      _     <- ZIO.fail(new RuntimeException("native transport abort took five seconds or more")).when(end - start >= 5000000000L)
      next  <- context.run(identity).map(_.head)
      _     <- ZIO.fail(new RuntimeException(s"pool returned aborted MySQL session $sessionId instead of replacing it")).when(next == sessionId)
    } yield ()
    unsafeRun(exercise)
  }
}

class MysqlZioVertxNativeSpec   extends MysqlNativeSupport
class MariaDBZioVertxNativeSpec extends MysqlNativeSupport

class DB2ZioVertxNativeSpec extends MultiSqlNativeCorpus {
  import MultiSqlNativeFixtures._
  val context: DB2ZioVertxContext[Literal] with VertxBaseEncoders with VertxBaseDecoders = new DB2ZioVertxContext(Literal)
  import context._
  case class IdentityOnly(id: Long)
  override protected def poolLayer(maxSize: Int) = VertxPool.db2(
    new DB2ConnectOptions().setHost(MultiSqlTestEnv.value("DB2_HOST", "DB2_HOST", "127.0.0.1"))
      .setPort(MultiSqlTestEnv.port("DB2_PORT", "DB2_PORT", 50500))
      .setDatabase(MultiSqlTestEnv.value("DB2_DB", "DB2_DATABASE", "QTEST"))
      .setUser(MultiSqlTestEnv.value("DB2_USER", "DB2_USER", "db2inst1"))
      .setPassword(MultiSqlTestEnv.value("DB2_PASSWORD", "DB2_PASSWORD", "Quill_Test_only1!")),
    new PoolOptions().setMaxSize(maxSize)
  )
  override protected val createRows =
    "CREATE TABLE quill_m07_native_rows (id BIGINT NOT NULL PRIMARY KEY, token VARCHAR(36) NOT NULL, amount DECIMAL(18,4) NOT NULL, occurred TIMESTAMP NOT NULL, label VARCHAR(80) NOT NULL, note VARCHAR(200))"
  override protected val dropRows        = "DROP TABLE quill_m07_native_rows"
  override protected val clearRows       = "DELETE FROM quill_m07_native_rows"
  override protected val createGenerated = "CREATE TABLE quill_m07_generated_rows (id BIGINT NOT NULL GENERATED ALWAYS AS IDENTITY PRIMARY KEY, label VARCHAR(80) NOT NULL)"
  override protected val dropGenerated   = "DROP TABLE quill_m07_generated_rows"
  override protected val clearGenerated  = "DELETE FROM quill_m07_generated_rows"
  private lazy val rows                  = quote(querySchema[NativeRow]("quill_m07_native_rows"))
  private lazy val generated             = quote(querySchema[GeneratedRow]("quill_m07_generated_rows"))
  private lazy val identityOnly          = quote(querySchema[IdentityOnly]("quill_m07_identity_only"))

  "DB2 FINAL TABLE returns a full generated record" in {
    val returned = unsafeRun(context.run(generated.insert(_.label -> lift("whole-row-returning")).returning(row => row)))
    returned.id should be > 0L
    returned.label shouldBe "whole-row-returning"
    unsafeRun(context.run(generated.filter(_.id == lift(returned.id)))) shouldBe List(returned)
  }

  "DB2 inserts an identity-only row with DEFAULT and returns its generated value" in {
    dropTable("DROP TABLE quill_m07_identity_only")
    executeSql("CREATE TABLE quill_m07_identity_only (id BIGINT NOT NULL GENERATED ALWAYS AS IDENTITY PRIMARY KEY)")
    try {
      val id = unsafeRun(context.run(identityOnly.insertValue(lift(IdentityOnly(0L))).returningGenerated(_.id)))
      id should be > 0L
      unsafeRun(context.run(identityOnly)) shouldBe List(IdentityOnly(id))
    } finally dropTable("DROP TABLE quill_m07_identity_only")
  }

  override protected def verifyCrud(): Unit = {
    val row = NativeRow(1L, UUID.fromString("681f7468-8cf5-4197-ad84-1430226aace1"), BigDecimal("19.8750"), LocalDateTime.of(2024, 2, 3, 4, 5, 6, 123000000), Label("typed"), None)
    unsafeRun(context.run(rows.insertValue(lift(row)))) shouldBe 1L
    unsafeRun(context.run(rows.filter(_.id == lift(1L)))) shouldBe List(row)
    unsafeRun(context.run(rows.filter(_.id == lift(1L)).update(_.note -> lift(Some("updated"): Option[String])))) shouldBe 1L
    unsafeRun(context.run(rows.filter(_.id == lift(1L)).map(_.note))) shouldBe List(Some("updated"))
    unsafeRun(context.run(rows.filter(_.id == lift(1L)).delete)) shouldBe 1L
    unsafeRun(context.run(rows)) shouldBe empty
    unsafeRun(context.run(quote(lift("42").toInt))) shouldBe 42
  }
  override protected def verifyGeneratedBatch(): Unit = {
    val first  = unsafeRun(context.run(generated.insert(_.label -> lift("first")).returningGenerated(_.id)))
    val labels = List("second", "third", "fourth")
    val ids    = unsafeRun(context.run(liftQuery(labels).foreach(label => generated.insert(_.label -> label).returningGenerated(_.id))))
    ids should have size labels.size
    ids shouldBe ids.sorted
    ids.forall(_ > first) shouldBe true
    unsafeRun(context.run(generated.sortBy(_.id).map(_.label))) shouldBe ("first" :: labels)
    unsafeRun(context.run(generated.sortBy(_.id).drop(1).take(2).map(_.label))) shouldBe List("second", "third")
    val batchCounts = unsafeRun(context.run(liftQuery(List(RenameRow("first", "first-x"), RenameRow("second", "second-x"))).foreach(change => generated.filter(_.label == change.from).update(_.label -> change.to))))
    batchCounts shouldBe List(1L, 1L)
    unsafeRun(context.run(generated.sortBy(_.id).map(_.label))).take(2) shouldBe List("first-x", "second-x")
    unsafeRun(context.run(generated.filter(_.label != lift("missing")).update(_.label -> lift("batch-updated")))) shouldBe 4L
    unsafeRun(context.run(generated.sortBy(_.id).map(_.label))) shouldBe List.fill(4)("batch-updated")
  }
  override protected def verifyTransactions(): Unit = {
    def row(id: Long, label: String) = NativeRow(id, UUID.randomUUID(), BigDecimal("1.25"), LocalDateTime.of(2024, 1, 1, 0, 0), Label(label), None)
    unsafeRun(context.transaction(context.run(rows.insertValue(lift(row(10, "commit")))))) shouldBe 1L
    unsafeRun(context.transaction(context.run(rows.insertValue(lift(row(11, "typed")))) *> ZIO.fail(new IllegalStateException("rollback typed"))).either).isLeft shouldBe true
    val defect = context.transaction(context.run(rows.insertValue(lift(row(12, "defect")))) *> ZIO.die(new IllegalArgumentException("rollback defect")))
    unsafeRun(defect.exit).causeOption.flatMap(_.defects.headOption).map(_.getMessage) shouldBe Some("rollback defect")
    unsafeRun(context.transaction { context.run(rows.insertValue(lift(row(13, "outer")))) *> context.transaction(context.run(rows.insertValue(lift(row(14, "nested"))))) }) shouldBe 1L
    val interrupted = for {
      inserted <- Promise.make[Nothing, Unit]; fiber <- context.transaction(context.run(rows.insertValue(lift(row(15, "interrupted")))) *> inserted.succeed(()) *> ZIO.sleep(Duration.fromSeconds(30))).fork; _ <- inserted.await;
      _        <- fiber.interrupt
    } yield ()
    unsafeRun(interrupted)
    unsafeRun(context.run(rows.sortBy(_.id).map(_.id))) shouldBe List(10L, 13L, 14L)
  }
  override protected def verifyStreaming(): Unit = {
    val values = (20L to 28L).map(id => NativeRow(id, UUID.randomUUID(), BigDecimal(id), LocalDateTime.of(2024, 3, 1, 0, 0), Label(s"row-$id"), None)).toList
    unsafeRun(context.run(liftQuery(values).foreach(value => rows.insertValue(value)))) shouldBe List.fill(values.size)(1L)
    collect(context.stream(rows.sortBy(_.id), 2).take(2)).map(_.id) shouldBe List(20L, 21L)
    collect(context.stream(rows.sortBy(_.id), 2)).map(_.id) shouldBe (20L to 28L).toList
    collect(context.stream(rows.filter(_.id < lift(0L)), 2)) shouldBe empty
    val bad = quote(infix"SELECT * FROM quill_m07_missing_table".as[Query[NativeRow]])
    collect(context.stream(bad, 1).take(1).either).exists(_.isLeft) shouldBe true
    unsafeRun(context.run(rows.filter(_.id == lift(28L)))).map(_.id) shouldBe List(28L)
  }
  override protected def verifyPrepared(): Unit = {
    val result = for {
      action <- context.prepare(generated.insert(_.label -> lift("prepared"))); before <- context.run(generated); _                       <- ZIO.attempt(before shouldBe empty); _ <- action.execute;
      query  <- context.prepare(generated.filter(_.label == lift("prepared"))); first  <- query.execute.map(_.iterator().hasNext); second <- query.execute.map(_.iterator().hasNext)
    } yield (first, second)
    unsafeRun(result) shouldBe ((true, true))
    context.probe("SELECT 1").failed.get shouldBe a[UnsupportedOperationException]
  }
  override protected def verifyNativeError(): Unit = {
    val row = NativeRow(30L, UUID.randomUUID(), BigDecimal("2.50"), LocalDateTime.of(2024, 4, 1, 0, 0), Label("unique"), None)
    unsafeRun(context.run(rows.insertValue(lift(row)))) shouldBe 1L
    val error = unsafeRun(context.run(rows.insertValue(lift(row))).either).swap.toOption.get
    error.getMessage should not be empty
    MultiSqlNativeTestOps.sqlState(error) shouldBe defined
    unsafeRun(context.run(rows.filter(_.id == lift(30L)))).map(_.id) shouldBe List(30L)
  }
  override protected def verifyTransportAbort(): Unit = {
    val identity = quote(infix"VALUES MON_GET_APPLICATION_HANDLE()".as[Query[Long]])
    val before   = unsafeRun(context.run(identity)).head
    // Db2's currently-committed reads can skip uncommitted inserts. Lock an
    // already committed row so the competing update must wait for its owner.
    val row = NativeRow(90L, UUID.randomUUID(), BigDecimal("1.00"), LocalDateTime.of(2024, 5, 1, 0, 0), Label("lock-holder"), None)
    unsafeRun(context.run(rows.insertValue(lift(row)))) shouldBe 1L
    val acquireAndWait = ZIO.scoped {
      for {
        observer    <- ZIO.service[Pool].flatMap(VertxRuntime.acquireConnection)
        transaction <- VertxRuntime.fromFutureScoped(observer.begin())(tx => VertxRuntime.fromFuture(tx.rollback()).ignore)
        _           <- VertxRuntime.fromFuture(observer.preparedQuery("UPDATE quill_m07_native_rows SET note = ? WHERE id = ?")
               .execute(Tuple.tuple().addString("held").addLong(90L)))
        fiber <- context.transaction(context.run(rows.filter(_.id == lift(90L)).update(_.note -> lift(Some("blocked"): Option[String])))).provideEnvironment(zio.ZEnvironment(pool)).fork
        _     <- awaitPending(
               VertxRuntime.fromFuture(observerPool.query("SELECT COUNT(*) FROM TABLE(MON_GET_APPL_LOCKWAIT(NULL, -2)) AS W").execute()).map(_.iterator().next().getLong(0) > 0L),
               100
             )
        start <- zio.Clock.nanoTime
        _     <- fiber.interrupt
        end   <- zio.Clock.nanoTime
        _     <- ZIO.fail(new RuntimeException("DB2 lock-wait transport abort took five seconds or more")).when(end - start >= 5000000000L)
        _     <- VertxRuntime.fromFuture(transaction.rollback())
        after <- context.run(identity).provideEnvironment(zio.ZEnvironment(pool)).map(_.head)
        _     <- ZIO.fail(new RuntimeException(s"pool returned aborted DB2 session $before instead of replacing it")).when(after == before)
      } yield ()
    }
    unsafeRunObserver(acquireAndWait)
  }
}

private[vertx] object MultiSqlNativeTestOps {
  def awaitPending(check: zio.Task[Boolean], attempts: Int): zio.Task[Unit] =
    check.flatMap { ready =>
      if (ready) ZIO.unit
      else if (attempts <= 0) ZIO.fail(new RuntimeException("native statement never appeared as pending"))
      else ZIO.sleep(Duration.fromMillis(50)) *> awaitPending(check, attempts - 1)
    }

  def sqlState(error: Throwable): Option[String] =
    Iterator.iterate(Option(error))(_.flatMap(current => Option(current.getCause))).takeWhile(_.nonEmpty).flatten
      .flatMap(current =>
        Seq("getSqlState", "getSQLState").iterator.flatMap(name =>
          try Option(current.getClass.getMethod(name).invoke(current)).map(_.toString)
          catch { case _: ReflectiveOperationException => None }
        )
      ).take(1).toList.headOption
}
