package zio.quill.context.vertx

import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import zio.quill._
import zio.quill.ast.{External, ScalarValueLift}
import zio.quill.context.{ExecutionInfo, InsertReturningValueSingle, TranslateOptions}
import zio.quill.quat.Quat

class VertxPortableDialectSpec extends AnyWordSpec with Matchers {
  case class PortableEntry(id: Long, label: String)

  private val mysql = new MysqlZioVertxContext(Literal)
  private val db2   = new DB2ZioVertxContext(Literal)

  private def diagnostic(
    context: ZioVertxContext[_, Literal],
    sql: String,
    values: List[Any],
    options: TranslateOptions = TranslateOptions()
  ): String =
    context.translateQuery[Unit](
      sql,
      values.zipWithIndex.map { case (value, index) => ScalarValueLift(s"v$index", External.Source.Parser, value, null, Quat.Value) },
      options
    )(ExecutionInfo.unknown, ())

  "native SQL dialects" should {
    "keep MySQL question marks and request precise individual returning inserts" in {
      val mirror = new SqlMirrorContext(VertxMysqlDialect, Literal)
      import mirror._
      val sql = mirror.run(query[PortableEntry].filter(_.id == lift(7L))).string
      sql must include("?")
      sql must not include ("$1")
      VertxMysqlDialect.idiomInsertReturningValuesCapability mustBe InsertReturningValueSingle
    }

    "generate a DELETE target alias accepted by MySQL and MariaDB" in {
      val mirror = new SqlMirrorContext(VertxMysqlDialect, Literal)
      import mirror._
      val sql = mirror.run(query[PortableEntry].filter(_.id == lift(7L)).delete).string
      sql must startWith("DELETE ")
      sql must include(" FROM PortableEntry ")
      sql must not startWith ("DELETE FROM ")
    }

    "generate DB2 final and old table returning statements" in {
      val mirror = new SqlMirrorContext(VertxDB2Dialect, Literal)
      import mirror._
      val inserted = mirror.run(query[PortableEntry].insertValue(lift(PortableEntry(7L, "seven"))).returning(_.id)).string
      val deleted  = mirror.run(query[PortableEntry].filter(_.id == lift(7L)).delete.returning(_.id)).string
      inserted must include("FINAL TABLE (INSERT INTO")
      inserted must not include ("RETURNING")
      deleted must include("OLD TABLE (DELETE FROM")
      deleted must include("?")
    }

    "generate DB2 scalar casts from its single-row system table" in {
      val mirror = new SqlMirrorContext(VertxDB2Dialect, Literal)
      import mirror._
      val sql = mirror.run(quote(lift("42").toInt)).string
      sql must include("CAST(")
      sql must include("AS INTEGER)")
      sql must include("FROM SYSIBM.SYSDUMMY1")
      sql must not include ("CASE WHEN")
      val longSql = mirror.run(quote(lift("42").toLong)).string
      longSql must include("AS BIGINT)")
      longSql must include("FROM SYSIBM.SYSDUMMY1")
      val projected = mirror.run(query[PortableEntry].map(_.label.toInt)).string
      projected must include("CAST(")
      projected must not include ("CASE WHEN")
      val updated = mirror.run(query[PortableEntry].filter(_.id == lift(1L)).update(_.id -> lift("42").toLong)).string
      updated must include("AS VARCHAR(32672)) AS BIGINT)")
      updated must not include ("CASE WHEN")
    }

  }

  "portable SQL diagnostics" should {
    "substitute MySQL binds outside literals, backticks and comments" in {
      val sql = """select ?, '?', `?`, /* ? */ ?, 'it\'s ?'"""
      diagnostic(mysql, sql, List(7, "two")) mustBe """select lift(7), '?', `?`, /* ? */ lift('two'), 'it\'s ?'"""
    }

    "recognize MySQL hash comments and arithmetic double minus" in {
      diagnostic(mysql, "select ?--?, ? # ?\n, ?", List(1, 2, 3, 4)) mustBe
        "select lift(1)--lift(2), lift(3) # ?\n, lift(4)"
      diagnostic(mysql, "select ? -- ?\n, ?", List(1, 2)) mustBe
        "select lift(1) -- ?\n, lift(2)"
      diagnostic(mysql, "select ? #\n, ?", List(1, 2)) mustBe
        "select lift(1) #\n, lift(2)"
    }

    "end MySQL block comments at the first closing delimiter" in {
      diagnostic(mysql, "select /* outer /* inner */ ?", List(7)) mustBe
        "select /* outer /* inner */ lift(7)"
    }

    "skip escaped quotes in MySQL double-quoted strings" in {
      val sql = """select "a\"?", ?"""
      diagnostic(mysql, sql, List(7)) mustBe """select "a\"?", lift(7)"""
    }

    "retain native parameter labels when lifts are not plugged" in {
      val options = TranslateOptions(plugLifts = false)
      diagnostic(mysql, "select ?", List(8), options) mustBe "select ?\n?1 = 8"
      diagnostic(db2, "select ? from SYSIBM.SYSDUMMY1", List(8), options) mustBe
        "select ? from SYSIBM.SYSDUMMY1\n?1 = 8"
    }

    "quote backslashes using the target SQL dialect" in {
      val options = TranslateOptions(demarcatePluggedLifts = false)
      diagnostic(mysql, "select ?", List("a\\b"), options) mustBe "select 'a\\\\b'"
      diagnostic(db2, "select ? from SYSIBM.SYSDUMMY1", List("a\\b"), options) mustBe
        "select 'a\\b' from SYSIBM.SYSDUMMY1"
    }
  }
}
