package zio.quill.vertx

import org.scalatest.BeforeAndAfterEach
import zio.ZIO
import zio.quill.Query
import zio.quill.context.vertx.VertxRuntime

class VertxPrepareSpec extends VertxPostgresSpecSupport with BeforeAndAfterEach {
  import context._

  case class PreparedItem(id: Int, label: String)
  val items = quote(querySchema[PreparedItem]("quill_vertx_prepared_items"))

  private def sql(statement: String): Unit =
    unsafeRun(VertxRuntime.fromFuture(pool.query(statement).execute()).unit)

  override def beforeAll(): Unit = {
    super.beforeAll()
    sql("DROP TABLE IF EXISTS quill_vertx_prepared_items")
    sql("CREATE TABLE quill_vertx_prepared_items (id INTEGER PRIMARY KEY, label TEXT NOT NULL)")
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    sql("TRUNCATE quill_vertx_prepared_items")
  }

  override def afterAll(): Unit =
    try if (poolIsReady) sql("DROP TABLE IF EXISTS quill_vertx_prepared_items")
    finally super.afterAll()

  "prepared query and action keep QDSL and release their binding connection" in {
    val effect = for {
      insertion <- context.prepare(items.insertValue(lift(PreparedItem(1, "bound"))))
      // Preparation must not execute an action or retain the only pool connection.
      before    <- context.run(items)
      _         <- ZIO.attempt(before mustEqual Nil)
      count     <- insertion.execute.map(_.rowCount())
      selection <- context.prepare(items.filter(_.id == lift(1)).map(_.label))
      first     <- selection.execute.map(_.iterator().next().getString(0))
      second    <- selection.execute.map(_.iterator().next().getString(0))
    } yield (count, first, second)
    unsafeRun(effect) mustEqual ((1, "bound", "bound"))
  }

  "prepared batch handles execute in the transaction owning their connection" in {
    val records = List(PreparedItem(2, "two"), PreparedItem(3, "three"))
    val effect  = context.transaction {
      for {
        handles <- context.prepare(liftQuery(records).foreach(item => items.insertValue(item)))
        counts  <- ZIO.foreach(handles)(_.execute.map(_.rowCount()))
        found   <- context.run(items.filter(_.id > lift(1)).sortBy(_.id))
      } yield (counts, found)
    }
    unsafeRun(effect) mustEqual ((List(1, 1), records))
  }

  "positional bindings leave PostgreSQL question-mark operators and literals intact" in {
    val value      = 7
    val expression = quote {
      infix"""SELECT ('{"key":1}'::jsonb ? 'key'), '?'::text, ${lift(value)}::integer /* ? */"""
        .as[Query[(Boolean, String, Int)]]
    }
    unsafeRun(context.run(expression)) mustEqual List((true, "?", 7))
  }
}
