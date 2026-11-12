package zio.quill.vertx

import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers._
import zio.Promise
import zio.quill.{Query, Quoted}
import zio.quill.base.Spec
import zio.quill.context.vertx.VertxRuntime
import zio.ZIO

object QueryParitySpec {
  case class TeamCode(value: Int)
  implicit val teamCodeEncoder: zio.quill.MappedEncoding[TeamCode, Int] = zio.quill.MappedEncoding(_.value)
  implicit val teamCodeDecoder: zio.quill.MappedEncoding[Int, TeamCode] = zio.quill.MappedEncoding(TeamCode.apply)

  case class Team(code: TeamCode, title: String)
  case class Worker(id: Int, name: String, team: TeamCode, age: Int, note: Option[String])
  case class GeneratedItem(id: Long, label: String)
}

class QueryParitySpec extends VertxPostgresSpecSupport with BeforeAndAfterEach {
  import QueryParitySpec._
  import context._

  val teams          = quote(querySchema[Team]("quill_vertx_parity_teams"))
  val workers        = quote(querySchema[Worker]("quill_vertx_parity_workers"))
  val generatedItems = quote(querySchema[GeneratedItem]("quill_vertx_parity_generated"))

  private val teamFixture   = List(Team(TeamCode(10), "Engineering"), Team(TeamCode(20), "Sales"))
  private val workerFixture = List(
    Worker(1, "Ada", TeamCode(10), 25, Some("remote")),
    Worker(2, "Ben", TeamCode(10), 35, None),
    Worker(3, "Cal", TeamCode(20), 35, Some("office")),
    Worker(4, "Dee", TeamCode(30), 45, None)
  )

  private def sql(statement: String): Unit =
    unsafeRun(VertxRuntime.fromFuture(pool.query(statement).execute()).unit)

  override def beforeAll(): Unit = {
    super.beforeAll()
    sql("DROP TABLE IF EXISTS quill_vertx_parity_generated")
    sql("DROP TABLE IF EXISTS quill_vertx_parity_workers")
    sql("DROP TABLE IF EXISTS quill_vertx_parity_teams")
    sql("CREATE TABLE quill_vertx_parity_teams (code INT PRIMARY KEY, title TEXT NOT NULL)")
    sql("CREATE TABLE quill_vertx_parity_workers (id INT PRIMARY KEY, name TEXT NOT NULL, team INT NOT NULL, age INT NOT NULL, note TEXT NULL)")
    sql("CREATE TABLE quill_vertx_parity_generated (id BIGSERIAL PRIMARY KEY, label TEXT NOT NULL)")
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    sql("TRUNCATE TABLE quill_vertx_parity_generated, quill_vertx_parity_workers, quill_vertx_parity_teams RESTART IDENTITY")
    unsafeRun(context.run(liftQuery(teamFixture).foreach(t => teams.insertValue(t))))
    unsafeRun(context.run(liftQuery(workerFixture).foreach(w => workers.insertValue(w))))
  }

  override def afterAll(): Unit =
    try if (poolIsReady) {
        sql("DROP TABLE IF EXISTS quill_vertx_parity_generated")
        sql("DROP TABLE IF EXISTS quill_vertx_parity_workers")
        sql("DROP TABLE IF EXISTS quill_vertx_parity_teams")
      }
    finally super.afterAll()

  "inner and left joins preserve null-side option values" in {
    val joined = unsafeRun(context.run(
      workers.join(teams).on((worker, team) => worker.team == team.code)
        .map { case (worker, team) => (worker.name, team.title) }
    ))
    joined.toSet shouldBe Set(("Ada", "Engineering"), ("Ben", "Engineering"), ("Cal", "Sales"))

    val leftJoined = unsafeRun(context.run(
      workers.leftJoin(teams).on((worker, team) => worker.team == team.code)
        .map { case (worker, team) => (worker.name, team.map(_.title)) }
    ))
    leftJoined.toSet shouldBe Set(
      ("Ada", Some("Engineering")),
      ("Ben", Some("Engineering")),
      ("Cal", Some("Sales")),
      ("Dee", None)
    )
  }

  "aggregates, grouping, distinct and optional projections match the SQL corpus semantics" in {
    unsafeRun(context.run(workers.map(w => (max(w.age), min(w.age))))) shouldBe List((45, 25))
    val grouped = unsafeRun(context.run(workers.groupByMap(_.team)(worker => (worker.team, max(worker.age)))))
    grouped.toSet shouldBe Set((TeamCode(10), 35), (TeamCode(20), 35), (TeamCode(30), 45))
    unsafeRun(context.run(workers.map(_.age).distinct)).toSet shouldBe Set(25, 35, 45)
    val notes = unsafeRun(context.run(workers.sortBy(_.id).map(w => (w.name, w.note.getOrElse("missing")))))
    notes shouldBe List(("Ada", "remote"), ("Ben", "missing"), ("Cal", "office"), ("Dee", "missing"))
  }

  "dynamic quotations and liftQuery filters retain runtime values" in {
    val fromAge = quote { (age: Int) => workers.filter(_.age >= age).map(_.name) }
    unsafeRun(context.run(fromAge(lift(35)))).toSet shouldBe Set("Ben", "Cal", "Dee")
    val minAge                               = 35
    val capturedQuery: Quoted[Query[String]] = quote(workers.filter(_.age >= lift(minAge)).map(_.name))
    unsafeRun(context.run(capturedQuery)).toSet shouldBe Set("Ben", "Cal", "Dee")
    val selected = List(TeamCode(10), TeamCode(30))
    unsafeRun(context.run(workers.filter(w => liftQuery(selected).contains(w.team)).map(_.name))).toSet shouldBe Set("Ada", "Ben", "Dee")
    unsafeRun(context.run(workers.filter(_.note.isDefined).map(_.name))).toSet shouldBe Set("Ada", "Cal")
  }

  "onConflict ignore and update preserve PostgreSQL uniqueness semantics" in {
    unsafeRun(context.run(teams.insertValue(lift(Team(TeamCode(10), "Ignored"))).onConflictIgnore(_.code))) shouldBe 0L
    unsafeRun(context.run(teams.insertValue(lift(Team(TeamCode(10), "Updated"))).onConflictUpdate(_.code)((current, incoming) => current.title -> incoming.title))) shouldBe 1L
    unsafeRun(context.run(teams.filter(_.code == lift(TeamCode(10))).map(_.title))) shouldBe List("Updated")
  }

  "native async probe validates SQL, reports preparation errors and leaves the pool reusable" in {
    unsafeRun(context.probeAsync("SELECT 1")) shouldBe (())
    unsafeRun(context.probeAsync("SELECT FROM quill_vertx_invalid_syntax").either).isLeft shouldBe true
    unsafeRun(context.run(workers.filter(_.id == lift(1)).map(_.name))) shouldBe List("Ada")
  }

  "generated returning, returningMany, batch returning and empty batches are represented natively" in {
    val firstId = unsafeRun(context.run(generatedItems.insert(_.label -> lift("first")).returningGenerated(_.id)))
    firstId should be > 0L

    val batchIds = unsafeRun(context.run {
      liftQuery(List("batch-a", "batch-b")).foreach(label =>
        generatedItems.insert(_.label -> label).returningGenerated(_.id)
      )
    })
    batchIds.size shouldBe 2

    val updatedIds = unsafeRun(context.run(
      generatedItems.filter(item => item.label == lift("batch-a") || item.label == lift("batch-b"))
        .update(_.label -> "updated").returningMany(_.id)
    ))
    updatedIds.toSet shouldBe batchIds.toSet

    val emptyBatch = unsafeRun(context.run {
      liftQuery(List.empty[String]).foreach(label => generatedItems.insert(_.label -> label).returningGenerated(_.id))
    })
    emptyBatch shouldBe empty
    unsafeRun(context.run(generatedItems.filter(_.id == lift(firstId)).map(_.label))) shouldBe List("first")
  }

  "typed failures, defects and interruption roll back their transaction" in {
    val typed = context.transaction {
      context.run(workers.insertValue(lift(Worker(90, "typed", TeamCode(10), 1, None)))) *>
        ZIO.fail(new RuntimeException("typed transaction failure"))
    }
    unsafeRun(typed.either).isLeft shouldBe true
    unsafeRun(context.run(workers.filter(_.id == lift(90)))) shouldBe empty

    val defect = context.transaction {
      context.run(workers.insertValue(lift(Worker(91, "defect", TeamCode(10), 1, None)))) *>
        ZIO.die(new IllegalStateException("transaction defect"))
    }
    unsafeRun(defect.exit).isFailure shouldBe true
    unsafeRun(context.run(workers.filter(_.id == lift(91)))) shouldBe empty

    val interrupted = for {
      inserted <- Promise.make[Nothing, Unit]
      fiber    <- context.transaction {
                 context.run(workers.insertValue(lift(Worker(92, "interrupted", TeamCode(10), 1, None)))) *>
                   inserted.succeed(()) *> ZIO.never
               }.fork
      _    <- inserted.await
      _    <- fiber.interrupt
      rows <- context.run(workers.filter(_.id == lift(92)))
    } yield rows
    unsafeRun(interrupted) shouldBe empty
  }

  "streams share the transaction connection and release it after empty or failing consumption" in {
    val insideTransaction = unsafeRun(context.transaction(context.stream(workers.sortBy(_.id)).take(2).runCollect.map(_.toList)))
    insideTransaction.map(_.name) shouldBe List("Ada", "Ben")

    val noRows = collect(context.stream(workers.filter(_.id < lift(0))))
    noRows shouldBe empty

    val failedConsumer = context.transaction {
      context.stream(workers).runForeach(_ => ZIO.fail(new RuntimeException("consumer failed")))
    }
    unsafeRun(failedConsumer.either).isLeft shouldBe true
    unsafeRun(context.run(workers.filter(_.id == lift(1)))).map(_.name) shouldBe List("Ada")
  }
}
