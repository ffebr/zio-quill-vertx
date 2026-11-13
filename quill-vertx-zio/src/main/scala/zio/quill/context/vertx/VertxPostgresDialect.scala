package zio.quill.context.vertx

import zio.quill.{IdiomContext, NamingStrategy, PostgresDialect}
import zio.quill.ast._
import zio.quill.context.sql.idiom._
import zio.quill.idiom.StatementInterpolator._
import zio.quill.norm.BetaReduction
import zio.quill.norm.{ConcatBehavior, EqualityBehavior}
import zio.quill.quat.Quat

/** PostgreSQL SQL generation for Vert.x prepared queries (`$1`, `$2`, ...). */
trait VertxPostgresDialect extends PostgresDialect with PositionalBindVariables {

  override def normalizeAst(
    ast: Ast,
    concatBehavior: ConcatBehavior,
    equalityBehavior: EqualityBehavior,
    idiomContext: IdiomContext
  ): Ast =
    // PostgreSQL cannot infer a bind's type when it appears only in `$n IS NOT NULL` (42P18).
    // COALESCE gives the bind a type from the fallback column while preserving getOrElse semantics.
    VertxPostgresDialect.LowerNullGetOrElse(
      super.normalizeAst(ast, concatBehavior, equalityBehavior, idiomContext)
    )

  /**
   * Lower String numeric conversions before SqlIdiom's action alias helpers
   * copy the idiom. Those helpers create a plain SqlIdiom and would otherwise
   * lose this backend's operation tokenizer while rendering filtered actions
   * and correlated returning expressions.
   */
  private def lowerStringNumericCasts(ast: Action): Action =
    VertxPostgresDialect.LowerStringNumericCasts(ast)

  /**
   * PostgreSQL action alias rendering uses the filtered action's alias inside
   * RETURNING. Keep correlated return expressions bound to that same alias
   * after the shared action normalization has chosen it.
   */
  override def querifyAction(ast: Action, batchAlias: Option[String]): Action = {
    val normalized = lowerStringNumericCasts(super.querifyAction(ast, batchAlias))

    normalized match {
      case Returning(action @ Update(Filter(_: Entity, filterAlias, _), _), returningAlias, property)
          if (filterAlias != returningAlias) =>
        Returning(action, filterAlias, BetaReduction(property, returningAlias -> filterAlias))

      case ReturningGenerated(action @ Update(Filter(_: Entity, filterAlias, _), _), returningAlias, property)
          if (filterAlias != returningAlias) =>
        ReturningGenerated(action, filterAlias, BetaReduction(property, returningAlias -> filterAlias))

      case Returning(action @ Delete(Filter(_: Entity, filterAlias, _)), returningAlias, property)
          if (filterAlias != returningAlias) =>
        Returning(action, filterAlias, BetaReduction(property, returningAlias -> filterAlias))

      case ReturningGenerated(action @ Delete(Filter(_: Entity, filterAlias, _)), returningAlias, property)
          if (filterAlias != returningAlias) =>
        ReturningGenerated(action, filterAlias, BetaReduction(property, returningAlias -> filterAlias))

      case other => other
    }
  }

  // Vert.x PostgreSQL binds untyped values as PostgreSQL's inferred parameter
  // type. Quill's String.toInt/toLong operations must first force the bind to
  // text so PostgreSQL accepts string-valued lifts exactly as the JDBC backend
  // does, then apply the requested numeric cast.
  override implicit def operationTokenizer(implicit
    astTokenizer: Tokenizer[Ast],
    strategy: NamingStrategy
  ): Tokenizer[Operation] =
    Tokenizer[Operation] {
      case UnaryOperation(StringOperator.`toLong`, value) => stmt"${scopedTokenizer(value)}::text::bigint"
      case UnaryOperation(StringOperator.`toInt`, value)  => stmt"${scopedTokenizer(value)}::text::integer"
      case operation                                      => super.operationTokenizer.token(operation)
    }
}

object VertxPostgresDialect extends VertxPostgresDialect {
  private object LowerNullGetOrElse extends StatelessTransformer {
    private def stableNullOperand(ast: Ast): Boolean =
      ast match {
        case _: ScalarValueLift  => true
        case _: Ident            => true
        case _: Constant         => true
        case NullValue           => true
        case Property(parent, _) => stableNullOperand(parent)
        case _                   => false
      }

    override def apply(ast: Ast): Ast =
      ast match {
        case If(IsNotNullCheck(value), thenValue, otherwise)
            if (value == thenValue && stableNullOperand(value)) =>
          Infix(
            List("COALESCE(", ", ", ")"),
            List(apply(value), apply(otherwise)),
            pure = true,
            transparent = false,
            quat = thenValue.quat
          )
        case other => super.apply(other)
      }
  }

  private object LowerStringNumericCasts extends StatelessTransformer {
    override def apply(ast: Ast): Ast =
      ast match {
        case UnaryOperation(StringOperator.`toInt`, value) =>
          Infix(List("(", ")::text::integer"), List(apply(value)), pure = true, transparent = false, quat = Quat.Value)
        case UnaryOperation(StringOperator.`toLong`, value) =>
          Infix(List("(", ")::text::bigint"), List(apply(value)), pure = true, transparent = false, quat = Quat.Value)
        case other => super.apply(other)
      }
  }
}
