package zio.quill

import io.vertx.sqlclient.SqlConnection
import zio.quill.context.vertx.{VertxConnectionAbort, VertxDB2Codecs, VertxDB2Dialect, ZioVertxContext}
import zio.Task

/** Native DB2 context backed by Vert.x SQL client and ZIO. */
class DB2ZioVertxContext[+Naming <: NamingStrategy](naming: Naming)
    extends ZioVertxContext[VertxDB2Dialect, Naming](naming)
    with VertxDB2Codecs {

  override val idiom: VertxDB2Dialect = VertxDB2Dialect

  override protected def cancel(connection: SqlConnection): Task[Unit] =
    VertxConnectionAbort.abort(connection)
}
