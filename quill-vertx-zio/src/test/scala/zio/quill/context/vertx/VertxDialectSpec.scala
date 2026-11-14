package zio.quill.context.vertx

import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import zio.quill.{Literal, SqlMirrorContext}
import zio.quill.vertx.VertxPostgresSpecSupport

class VertxDialectSpec extends AnyWordSpec with Matchers {
  case class Product(id: Long, description: String, sku: Int)

  private val mirror = new SqlMirrorContext(VertxPostgresDialect, Literal)
  import mirror._

  "VertxPostgresDialect" should {
    "cast string-valued lifts through text before integer and bigint conversion" in {
      val intSql  = mirror.run(query[Product].filter(_.sku == lift("1004").toInt)).string
      val longSql = mirror.run(query[Product].filter(_.sku == lift("1004").toLong)).string

      println(s"VertxPostgresDialect toInt SQL: $intSql")
      println(s"VertxPostgresDialect toLong SQL: $longSql")
      intSql must include("::text::integer")
      longSql must include("::text::bigint")
    }

    "keep CASE evaluation for nullable volatile infix values" in {
      val sql = mirror.run {
        query[Product].map(_ => infix"""random()::text""".as[Option[String]].getOrElse("fallback"))
      }.string

      sql must include("CASE WHEN")
      sql must include("random()::text IS NOT NULL")
    }
  }
}

class VertxNativeDialectSpec extends VertxPostgresSpecSupport with BeforeAndAfterEach with Matchers {
  case class Product(id: Long, description: String, sku: Int)
  case class CastRow(id: Long, i: Int, l: Long)
  case class PatchRow(id: Long, email: String, bio: Option[String], active: Boolean)
  import context._

  implicit val castRowSchema: context.SchemaMeta[CastRow]   = schemaMeta[CastRow]("quill_vertx_cast_rows")
  val castRows                                              = quote(query[CastRow])
  implicit val patchRowSchema: context.SchemaMeta[PatchRow] = schemaMeta[PatchRow]("quill_vertx_patch_rows")
  val patchRows                                             = quote(query[PatchRow])

  private def executeSql(statement: String): Unit =
    unsafeRun(VertxRuntime.fromFuture(pool.query(statement).execute()).unit)

  override def beforeAll(): Unit = {
    super.beforeAll()
    executeSql("DROP TABLE IF EXISTS quill_vertx_cast_rows")
    executeSql("DROP TABLE IF EXISTS quill_vertx_patch_rows")
    executeSql("CREATE TABLE quill_vertx_cast_rows (id BIGINT PRIMARY KEY, i INTEGER NOT NULL, l BIGINT NOT NULL)")
    executeSql(
      "CREATE TABLE quill_vertx_patch_rows (id BIGINT PRIMARY KEY, email TEXT NOT NULL, bio TEXT NULL, active BOOLEAN NOT NULL)"
    )
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    executeSql("TRUNCATE TABLE quill_vertx_cast_rows")
    executeSql("TRUNCATE TABLE quill_vertx_patch_rows")
  }

  override def afterAll(): Unit =
    try if (poolIsReady) {
        executeSql("DROP TABLE IF EXISTS quill_vertx_cast_rows")
        executeSql("DROP TABLE IF EXISTS quill_vertx_patch_rows")
      }
    finally super.afterAll()

  "PostgresZioVertxContext" - {
    "prepare the same string-to-number QDSL casts" in {
      val sql     = unsafeRun(context.prepare(query[Product].filter(_.sku == lift("1004").toInt))).sql
      val longSql = unsafeRun(context.prepare(query[Product].filter(_.sku == lift("1004").toLong))).sql

      println(s"Native Vert.x toInt SQL: $sql")
      println(s"Native Vert.x toLong SQL: $longSql")
      sql must include("::text::integer")
      longSql must include("::text::bigint")
    }

    "cast complete concatenated strings to integer and bigint" in {
      val intQuery  = quote((lift("10") + lift("04")).toInt)
      val longQuery = quote((lift("10") + lift("04")).toLong)
      val intSql    = context.translate(intQuery)
      val longSql   = context.translate(longQuery)
      val intValue  = unsafeRun(context.run(intQuery))
      val longValue = unsafeRun(context.run(longQuery))

      intSql must startWith("SELECT ")
      longSql must startWith("SELECT ")
      intValue mustBe 1004
      longValue mustBe 1004L
    }

    "preserve string casts in direct insert returning expressions" in {
      val operation = quote {
        castRows
          .insertValue(lift(CastRow(1L, 4, 8L)))
          .returning(row => (row.i + lift("1004").toInt, row.l + lift("1004").toLong))
      }
      val sql = unsafeRun(context.prepare(operation)).sql
      println(s"Native Vert.x insert returning SQL: $sql")
      sql must include("::text::integer")
      sql must include("::text::bigint")
      val returned = unsafeRun(context.run(operation))

      returned mustBe ((1008, 1012L))
    }

    "preserve string casts through filtered update returning translation" in {
      unsafeRun(context.run(castRows.insertValue(lift(CastRow(2L, 1004, 1004L)))))
      val operation = quote {
        castRows
          .filter(_.i == lift("1004").toInt)
          .update(
            row => row.i -> (row.i + lift("1004").toInt),
            row => row.l -> (row.l + lift("1004").toLong)
          )
          .returning(row => (row.i, row.l))
      }
      val sql = unsafeRun(context.prepare(operation)).sql
      println(s"Native Vert.x filtered update returning SQL: $sql")
      sql must include("::text::integer")
      sql must include("::text::bigint")
      val returned = unsafeRun(context.run(operation))

      returned mustBe ((2008, 2008L))
    }

    "preserve the filtered action alias in update returningMany" in {
      unsafeRun(context.run(castRows.insertValue(lift(CastRow(4L, 4, 40L)))))
      val operation = quote {
        castRows
          .filter(_.id == lift(4L))
          .update(_.i -> lift(14))
          .returningMany(_.id)
      }
      val sql = unsafeRun(context.prepare(operation)).sql
      println(s"Native Vert.x filtered update returningMany SQL: $sql")
      val returned = unsafeRun(context.run(operation))

      returned mustBe List(4L)
    }

    "preserve correlated DELETE RETURNING aliases without capturing a same-named nested binder" in {
      unsafeRun(context.run(castRows.insertValue(lift(CastRow(5L, 5, 50L)))))
      unsafeRun(context.run(castRows.insertValue(lift(CastRow(6L, 1004, 60L)))))
      val operation = quote {
        castRows
          .filter(row => row.id == lift(5L))
          .delete
          .returning(deleted =>
            (
              deleted.id,
              query[CastRow]
                .filter(candidate => candidate.id == deleted.id + lift(1L))
                .map(_.i)
                .value
                .getOrElse(-1),
              query[CastRow]
                .filter(deleted => deleted.id == lift(6L))
                .map(_.i)
                .value
                .getOrElse(-1)
            )
          )
      }
      val sql = unsafeRun(context.prepare(operation)).sql
      println(s"Native Vert.x correlated delete returning SQL: $sql")
      val returned = unsafeRun(context.run(operation))

      returned mustBe ((5L, 1004, 1004))
    }

    "preserve string casts in correlated update returning queries" in {
      unsafeRun(context.run(castRows.insertValue(lift(CastRow(3L, 1004, 8L)))))
      val operation = quote {
        castRows
          .filter(_.id == lift(3L))
          .update(_.i -> lift(1004))
          .returning(row =>
            query[CastRow]
              .filter(candidate => candidate.id == row.id && candidate.i == lift("1004").toInt)
              .map(_.i)
              .value
              .getOrElse(-1)
          )
      }
      val sql = unsafeRun(context.prepare(operation)).sql
      println(s"Native Vert.x correlated update returning SQL: $sql")
      sql must include("::text::integer")
      val returned = unsafeRun(context.run(operation))

      returned mustBe 1004
    }

    "preserve optional-value semantics in queries and updates" in {
      unsafeRun(context.run(patchRows.insertValue(lift(PatchRow(1L, "old@example.test", Some("old bio"), false)))))

      val querySome = quote {
        patchRows
          .filter(_.id == lift(1L))
          .map(row => lift(Some("query@example.test"): Option[String]).getOrElse(row.email))
      }
      val queryNone = quote {
        patchRows.filter(_.id == lift(1L)).map(row => lift(None: Option[String]).getOrElse(row.email))
      }
      unsafeRun(context.run(querySome)) mustBe List("query@example.test")
      unsafeRun(context.run(queryNone)) mustBe List("old@example.test")

      val updateSome = quote {
        patchRows.filter(_.id == lift(1L)).update(
          row => row.email -> lift(Some("some@example.test"): Option[String]).getOrElse(row.email),
          row => row.bio   -> lift(Some("some bio"): Option[String]).orElse(row.bio)
        )
      }
      val updateNone = quote {
        patchRows.filter(_.id == lift(1L)).update(
          row => row.email -> lift(None: Option[String]).getOrElse(row.email),
          row => row.bio   -> lift(None: Option[String]).orElse(row.bio)
        )
      }
      unsafeRun(context.run(updateSome))
      unsafeRun(context.run(updateNone))

      val updated = unsafeRun(context.run(patchRows.filter(_.id == lift(1L)))).head
      updated.email mustBe "some@example.test"
      updated.bio mustBe Some("some bio")

      val unrelatedIf = quote {
        patchRows.filter(_.id == lift(1L)).map(row => if (row.active) "active" else "inactive")
      }
      context.translate(unrelatedIf) must include("CASE WHEN")
      unsafeRun(context.run(unrelatedIf)) mustBe List("inactive")
    }
  }
}
