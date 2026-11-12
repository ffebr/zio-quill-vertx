package zio.quill.context.vertx

import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import zio.quill.{Literal, PostgresZioVertxContext}
import zio.quill.ast.{External, ScalarValueLift}
import zio.quill.context.{ExecutionInfo, TranslateOptions}
import zio.quill.quat.Quat

class VertxTranslateSpec extends AnyWordSpec with Matchers {
  case class Entry(id: Long, label: String)

  private val context = new PostgresZioVertxContext(Literal)
  import context._

  private def scalarLift(value: Any, name: String): ScalarValueLift =
    ScalarValueLift(name, External.Source.Parser, value, null, Quat.Value)

  private def diagnostic(sql: String, values: List[Any], options: TranslateOptions = TranslateOptions()): String =
    context.translateQuery[Unit](sql, values.zipWithIndex.map { case (value, i) => scalarLift(value, s"v$i") }, options)(
      ExecutionInfo.unknown,
      ()
    )

  "VertxTranslateContext" should {
    "translate query and action quotations synchronously with native placeholders" in {
      val querySql  = context.translate(query[Entry].filter(_.id == lift(12L)))
      val actionSql = context.translate(query[Entry].filter(_.id == lift(12L)).delete)

      querySql must include("lift(12)")
      actionSql must include("lift(12)")
    }

    "honor pretty printing and lift diagnostic options" in {
      val quoted = context.translate(query[Entry].filter(_.id == lift(12L)), TranslateOptions(prettyPrint = true))
      quoted must include("lift(12)")
      quoted must include("\n")
      context.translate(query[Entry].filter(_.id == lift(12L)), TranslateOptions(plugLifts = false)) must include("$1")

      diagnostic("select $1, $2", List("O'Reilly", 8)) mustBe "select lift('O''Reilly'), lift(8)"
      diagnostic("select $1", List("a\\b"), TranslateOptions(demarcatePluggedLifts = false)) mustBe "select E'a\\\\b'"
      diagnostic("select $1", List(8), TranslateOptions(plugLifts = false)) mustBe "select $1\n$1 = 8"
    }

    "substitute only real numbered placeholders and preserve placeholder-like SQL text" in {
      val sql        = """select $1, '$2', E'it\'s $2', "$3" -- $3
, /* $4 /* nested $5 */ */ $2, $$ $3 $$, $tag$ $4 $tag$, $3"""
      val translated = diagnostic(sql, List(10, "two", 30))

      translated mustBe """select lift(10), '$2', E'it\'s $2', "$3" -- $3
, /* $4 /* nested $5 */ */ lift('two'), $$ $3 $$, $tag$ $4 $tag$, lift(30)"""
    }
  }
}
