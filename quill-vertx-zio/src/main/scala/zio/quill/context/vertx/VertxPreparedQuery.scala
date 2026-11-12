package zio.quill.context.vertx

import io.vertx.sqlclient.{Pool, Row, RowSet}
import zio.ZIO

/**
 * Reusable native prepared operation. It owns no connection or Vert.x
 * statement; each execution borrows the context's current connection or
 * acquires one from the supplied pool.
 */
final case class VertxPreparedQuery(
  sql: String,
  execute: ZIO[Pool, Throwable, RowSet[Row]]
)
