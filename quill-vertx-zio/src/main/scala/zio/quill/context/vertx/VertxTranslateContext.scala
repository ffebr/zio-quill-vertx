package zio.quill.context.vertx

import zio.quill.{Action, BatchAction, NamingStrategy, Query, Quoted}
import zio.quill.ast.ScalarLift
import zio.quill.context.{ActionMacro, Context, ExecutionInfo, QueryMacro, TranslateOptions}
import zio.quill.idiom.Idiom

import scala.language.experimental.macros

/** Synchronous SQL diagnostics for native Vert.x SQL contexts. */
trait VertxTranslateContext {
  this: Context[_ <: Idiom, _ <: NamingStrategy] =>

  type TranslateResult[T] = T

  def translate[T](quoted: Quoted[T]): String = macro QueryMacro.translateQuery[T]
  def translate[T](quoted: Quoted[Query[T]]): String = macro QueryMacro.translateQuery[T]
  def translate(quoted: Quoted[Action[_]]): String = macro ActionMacro.translateQuery
  def translate(quoted: Quoted[BatchAction[Action[_]]]): List[String] = macro ActionMacro.translateBatchQuery

  def translate[T](quoted: Quoted[T], options: TranslateOptions): String =
    macro QueryMacro.translateQueryPrettyPrint[T]
  def translate[T](quoted: Quoted[Query[T]], options: TranslateOptions): String =
    macro QueryMacro.translateQueryPrettyPrint[T]
  def translate(quoted: Quoted[Action[_]], options: TranslateOptions): String =
    macro ActionMacro.translateQueryPrettyPrint
  def translate(quoted: Quoted[BatchAction[Action[_]]], options: TranslateOptions): List[String] =
    macro ActionMacro.translateBatchQueryPrettyPrint

  def translateQuery[T](
    statement: String,
    liftings: List[ScalarLift] = List(),
    options: TranslateOptions = TranslateOptions()
  )(executionInfo: ExecutionInfo, dc: Runner): String = {
    val rendered = renderPlaceholders(statement, liftings, options)
    if (options.prettyPrint) idiom.format(rendered) else rendered
  }

  def translateBatchQuery(groups: List[BatchGroup], options: TranslateOptions = TranslateOptions())(
    executionInfo: ExecutionInfo,
    dc: Runner
  ): List[String] =
    groups.flatMap(group => group.liftings.map(lifts => translateQuery(group.string, lifts, options)(executionInfo, dc)))

  private def diagnosticValue(value: Any): String = value match {
    case null      => "null"
    case None      => "null"
    case Some(v)   => diagnosticValue(v)
    case s: String => quoteString(s)
    case c: Char   => quoteString(c.toString)
    case other     => other.toString
  }

  private def quoteString(value: String): String = {
    val escaped = value.replace("'", "''")
    if (value.contains('\\') && idiom.isInstanceOf[VertxMysqlDialect]) "'" + escaped.replace("\\", "\\\\") + "'"
    else if (value.contains('\\') && idiom.liftingPlaceholder(0) == "$1") "E'" + escaped.replace("\\", "\\\\") + "'"
    else "'" + escaped + "'"
  }

  /**
   * Render actual parameter markers, skipping each dialect's SQL lexical
   * regions. Execution always uses structurally generated bound parameters.
   */
  private def renderPlaceholders(sql: String, lifts: List[ScalarLift], options: TranslateOptions): String = {
    if (lifts.isEmpty) return sql
    if (!options.plugLifts) return sql + "\n" + placeholderMappings(lifts)

    val values = lifts.toVector
    rewriteBindMarkers(sql) { (number, marker, _) =>
      if (number > 0 && number <= values.length) {
        val rendered = diagnosticValue(values(number - 1).value)
        if (options.demarcatePluggedLifts) s"lift($rendered)" else rendered
      } else marker
    }
  }

  /**
   * Rewrite SQL bind markers without interpreting markers inside SQL lexical
   * regions.
   */
  protected final def rewriteBindMarkers(sql: String)(replace: (Int, String, Int) => String): String = {
    val out     = new StringBuilder(sql.length + 32)
    val marker  = idiom.liftingPlaceholder(0)
    val mysql   = idiom.isInstanceOf[VertxMysqlDialect]
    val prefix  = if (marker == "?") "" else marker.dropRight(1)
    var ordinal = 0
    var i       = 0

    while (i < sql.length) {
      if (sql.charAt(i) == '\'') {
        val end = quotedEnd(sql, i, '\'', mysql || isEscapeString(sql, i))
        out.append(sql.substring(i, end)); i = end
      } else if (sql.charAt(i) == '"') {
        val end = quotedEnd(sql, i, '"', escapeBackslash = mysql)
        out.append(sql.substring(i, end)); i = end
      } else if (mysql && sql.charAt(i) == '`') {
        val end = quotedEnd(sql, i, '`', escapeBackslash = true)
        out.append(sql.substring(i, end)); i = end
      } else if (
        (mysql && sql.charAt(i) == '#') ||
        (i + 1 < sql.length && sql.charAt(i) == '-' && sql.charAt(i + 1) == '-' &&
          (!mysql || i + 2 == sql.length || sql.charAt(i + 2).isWhitespace || sql.charAt(i + 2).isControl))
      ) {
        val end0 = sql.indexOf('\n', i + 1)
        val end  = if (end0 < 0) sql.length else end0 + 1
        out.append(sql.substring(i, end)); i = end
      } else if (i + 1 < sql.length && sql.charAt(i) == '/' && sql.charAt(i + 1) == '*') {
        val end = blockCommentEnd(sql, i, nested = !mysql)
        out.append(sql.substring(i, end)); i = end
      } else if (marker == "?" && sql.charAt(i) == '?') {
        ordinal += 1
        out.append(replace(ordinal, "?", i)); i += 1
      } else if (
        prefix.nonEmpty && sql.startsWith(prefix, i) && isTokenBoundary(sql, i) &&
        i + prefix.length < sql.length && sql.charAt(i + prefix.length).isDigit
      ) {
        val start = i + prefix.length
        var end   = start + 1
        while (end < sql.length && sql.charAt(end).isDigit) end += 1
        val number =
          try sql.substring(start, end).toInt
          catch { case _: NumberFormatException => 0 }
        out.append(replace(number, sql.substring(i, end), i)); i = end
      } else if (marker == "$1" && sql.charAt(i) == '$' && isTokenBoundary(sql, i)) {
        dollarQuoteEnd(sql, i) match {
          case Some((tag, bodyStart)) =>
            val close = sql.indexOf(tag, bodyStart)
            val end   = if (close < 0) sql.length else close + tag.length
            out.append(sql.substring(i, end)); i = end
          case None =>
            out.append(sql.charAt(i)); i += 1
        }
      } else {
        out.append(sql.charAt(i)); i += 1
      }
    }
    out.toString
  }

  private def placeholderMappings(lifts: List[ScalarLift]): String =
    lifts.zipWithIndex.map { case (lift, index) =>
      val marker = idiom.liftingPlaceholder(index)
      val label  = if (marker == "?") s"?${index + 1}" else marker
      s"$label = ${lift.value}"
    }.mkString("\n")

  private def quotedEnd(sql: String, start: Int, quote: Char, escapeBackslash: Boolean): Int = {
    var i = start + 1
    while (i < sql.length) {
      if (sql.charAt(i) == quote) {
        if (i + 1 < sql.length && sql.charAt(i + 1) == quote) i += 2
        else return i + 1
      } else if (escapeBackslash && sql.charAt(i) == '\\' && i + 1 < sql.length) i += 2
      else i += 1
    }
    sql.length
  }

  private def blockCommentEnd(sql: String, start: Int, nested: Boolean): Int = {
    var depth = 1
    var i     = start + 2
    while (i < sql.length && depth > 0) {
      if (nested && i + 1 < sql.length && sql.charAt(i) == '/' && sql.charAt(i + 1) == '*') { depth += 1; i += 2 }
      else if (i + 1 < sql.length && sql.charAt(i) == '*' && sql.charAt(i + 1) == '/') { depth -= 1; i += 2 }
      else i += 1
    }
    i
  }

  private def dollarQuoteEnd(sql: String, start: Int): Option[(String, Int)] = {
    var end = start + 1
    if (end < sql.length && (sql.charAt(end).isLetter || sql.charAt(end) == '_')) {
      end += 1
      while (end < sql.length && (sql.charAt(end).isLetterOrDigit || sql.charAt(end) == '_')) end += 1
    }
    if (end < sql.length && sql.charAt(end) == '$') Some((sql.substring(start, end + 1), end + 1)) else None
  }

  private def isEscapeString(sql: String, quote: Int): Boolean =
    quote > 0 && (sql.charAt(quote - 1) == 'e' || sql.charAt(quote - 1) == 'E') &&
      (quote < 2 || !(sql.charAt(quote - 2).isLetterOrDigit || sql.charAt(quote - 2) == '_'))

  private def isTokenBoundary(sql: String, index: Int): Boolean =
    index == 0 || {
      val previous = sql.charAt(index - 1)
      !(previous.isLetterOrDigit || previous == '_' || previous == '$')
    }
}
