package zio.quill.vertx.shared.postgres

import org.scalatest.BeforeAndAfterEach
import zio.quill.{Literal, Ord, PostgresZioVertxContext, TestEntities}
import zio.quill.context.TranslateOptions
import zio.quill.vertx.VertxPostgresSpecSupport

/**
 * PostgreSQL-specific quotations and expected results from the original JDBC
 * runners.
 */
class PostgresSpecificZioVertxSpec extends VertxPostgresSpecSupport with BeforeAndAfterEach {
  override val context: PostgresZioVertxContext[Literal] with TestEntities =
    new PostgresZioVertxContext(Literal) with TestEntities
  import context._

  override def beforeEach(): Unit = {
    super.beforeEach()
    unsafeRun(context.run(query[TestEntity].delete))
    unsafeRun(context.run(query[TestEntity2].delete))
  }

  "complex queries" - {
    implicit val testEntity2InsertMeta = insertMeta[TestEntity2](_.o)
    val testEntityInsert               = quote((p: TestEntity) => query[TestEntity].insertValue(p))
    val testEntity2Insert              = quote((p: TestEntity2) => query[TestEntity2].insertValue(p))

    "join + nesting + infixes" in {
      val testEntities = List(
        TestEntity("aaa", 11, 1L, None, true),
        TestEntity("aaa", 12, 1L, None, true),
        TestEntity("aaa", 13, 3L, None, true),
        TestEntity("aaa", 14, 3L, None, true),
        TestEntity("bbb", 15, 4L, None, true),
        TestEntity("bbb", 16, 4L, None, true)
      )
      val testEntities2 = List(
        TestEntity2("aaf", 1, 1L, None),
        TestEntity2("aaf", 2, 1L, None),
        TestEntity2("aaf", 3, 3L, None),
        TestEntity2("aaf", 4, 3L, None),
        TestEntity2("baf", 5, 3L, None),
        TestEntity2("baf", 6, 2L, None)
      )
      unsafeRun(context.run(liftQuery(testEntities).foreach(p => testEntityInsert(p))))
      unsafeRun(context.run(liftQuery(testEntities2).foreach(p => testEntity2Insert(p))))
      val q = quote {
        query[TestEntity]
          .join(query[TestEntity2])
          .on { case (one, two) => one.l == two.l }
          .filter(_._1.s == "aaa")
          .map(_._2)
          .sortBy(t => (t.s, t.i))(Ord.desc)
          .map(e => (sql"DISTINCT ON (${e.s}) ${e.s}".as[String], e.i))
          .filter(r => r._2 == 3 || r._2 == 4 || r._2 == 5)
      }
      unsafeRun(context.run(q)) mustEqual List(("baf", 5), ("aaf", 4))
    }
  }

  case class Person(name: String, age: Int)

  "pretty prints query when enabled" in {
    val q = quote(query[Person])
    context.translate(q, TranslateOptions(prettyPrint = true)) mustEqual
      """SELECT
        |  x.name,
        |  x.age
        |FROM
        |  Person x""".stripMargin
  }

  "regular print when not enabled" in {
    val q = quote(query[Person])
    context.translate(q) mustEqual "SELECT x.name, x.age FROM Person x"
  }
}
