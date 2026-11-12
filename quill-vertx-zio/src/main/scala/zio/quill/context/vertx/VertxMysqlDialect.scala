package zio.quill.context.vertx

import zio.quill.{IdiomContext, MySQLDialect, NamingStrategy}
import zio.quill.ast._
import zio.quill.context._
import zio.quill.context.sql.idiom.{NoConcatSupport, QuestionMarkBindVariables, SqlIdiom}
import zio.quill.idiom.Statement
import zio.quill.idiom.StatementInterpolator._
import zio.quill.quat.Quat

/** MySQL SQL generation with precise single-row generated-key metadata. */
trait VertxMysqlDialect
    extends SqlIdiom
    with QuestionMarkBindVariables
    with NoConcatSupport
    with CanReturnField
    with CanInsertWithMultiValues
    with CanInsertReturningWithSingleValue {

  // MySQL returns only the first generated ID for a multi-row INSERT. Keep
  // ordinary multi-VALUES inserts, while letting Quill expand returning batches
  // as individual statements instead of guessing subsequent generated IDs.
  override def translate(ast: Ast, topLevelQuat: Quat, executionType: ExecutionType, idiomContext: IdiomContext)(
    implicit naming: NamingStrategy
  ): (Ast, Statement, ExecutionType) = {
    validateReturning(ast)
    VertxMysqlDialect.sqlDialect.translate(ast, topLevelQuat, executionType, idiomContext)
  }

  override def translateCached(ast: Ast, topLevelQuat: Quat, executionType: ExecutionType, idiomContext: IdiomContext)(
    implicit naming: NamingStrategy
  ): (Ast, Statement, ExecutionType) = {
    validateReturning(ast)
    VertxMysqlDialect.sqlDialect.translateCached(ast, topLevelQuat, executionType, idiomContext)
  }

  override def prepareForProbing(sql: String): String = VertxMysqlDialect.sqlDialect.prepareForProbing(sql)

  private def validateReturning(ast: Ast): Unit =
    CollectAst.byType[ReturningAction](ast).foreach {
      case ReturningAction(_: Update | _: Delete, _, _) =>
        throw new IllegalArgumentException("MySQL returning supports one generated INSERT key; UPDATE/DELETE returning is unavailable")
      case _ => ()
    }
}

object VertxMysqlDialect extends VertxMysqlDialect {
  private object sqlDialect extends MySQLDialect {
    // The alias-after-table single-table DELETE syntax requires MariaDB 11.6.
    // The explicit target-alias form works on both MySQL and older MariaDB.
    override protected def actionTokenizer(insertEntityTokenizer: Tokenizer[Entity])(implicit
      astTokenizer: Tokenizer[Ast],
      naming: NamingStrategy,
      idiomContext: IdiomContext
    ): Tokenizer[Action] =
      Tokenizer[Action] {
        case Delete(Filter(table: Entity, alias, predicate)) =>
          stmt"DELETE ${alias.token} FROM ${table.token} ${alias.token} WHERE ${predicate.token}"
        case action => super.actionTokenizer(insertEntityTokenizer).token(action)
      }

    override implicit def operationTokenizer(implicit astTokenizer: Tokenizer[Ast], naming: NamingStrategy): Tokenizer[Operation] =
      Tokenizer[Operation] {
        case UnaryOperation(StringOperator.`toInt` | StringOperator.`toLong`, value) => stmt"CAST(${value.token} AS SIGNED)"
        case operation                                                               => super.operationTokenizer.token(operation)
      }
  }
}
