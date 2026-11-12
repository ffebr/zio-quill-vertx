package zio.quill

import io.vertx.pgclient.PgConnection
import io.vertx.sqlclient.SqlConnection
import zio.ZIO
import zio.quill.context.vertx.{VertxJsonExtensions, VertxPostgresDialect, VertxRuntime, ZioVertxContext}
import zio.quill.util.ContextLogger

/** Native PostgreSQL context backed by Vert.x SQL client and ZIO. */
class PostgresZioVertxContext[+Naming <: NamingStrategy](naming: Naming)
    extends ZioVertxContext[VertxPostgresDialect, Naming](naming)
    with VertxJsonExtensions {

  override val idiom: VertxPostgresDialect = VertxPostgresDialect
  override private[quill] val logger       = ContextLogger(classOf[PostgresZioVertxContext[_]])

  override protected def cancel(connection: SqlConnection): ZIO[Any, Throwable, Unit] =
    VertxRuntime.fromFuture(PgConnection.cast(connection).cancelRequest()).unit
}
