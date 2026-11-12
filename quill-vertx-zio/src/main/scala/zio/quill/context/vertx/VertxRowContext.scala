package zio.quill.context.vertx

import io.vertx.sqlclient.{Row, SqlConnection, Tuple}
import zio.quill.context.sql.SqlContext

/** The native row/session types shared by the Vert.x codecs. */
trait VertxRowContext {
  this: SqlContext[_, _] =>

  override type PrepareRow  = Tuple
  override type ResultRow   = Row
  override type Session     = SqlConnection
  override type NullChecker = VertxNullChecker

  class VertxNullChecker extends BaseNullChecker {
    override def apply(index: Int, row: Row): Boolean = row.getValue(index) == null
  }

  implicit val nullChecker: VertxNullChecker = new VertxNullChecker
}
