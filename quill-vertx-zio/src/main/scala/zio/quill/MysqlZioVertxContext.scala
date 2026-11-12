package zio.quill

import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.{SqlConnection, Tuple}
import zio.ZIO
import zio.quill.context.vertx.{VertxConnectionAbort, VertxMysqlCodecs, VertxMysqlDialect, ZioVertxContext}

/** Native MySQL/MariaDB execution with server-provided generated keys. */
class MysqlZioVertxContext[+Naming <: NamingStrategy](naming: Naming)
    extends ZioVertxContext[VertxMysqlDialect, Naming](naming)
    with VertxMysqlCodecs {

  override val idiom: VertxMysqlDialect = VertxMysqlDialect

  override protected def cancel(connection: SqlConnection): ZIO[Any, Throwable, Unit] =
    VertxConnectionAbort.abort(connection)

  override protected def executeReturning[T](
    sql: String,
    prepare: Prepare,
    extractor: Extractor[T],
    returningBehavior: ReturnAction,
    connection: SqlConnection
  ): ZIO[Any, Throwable, List[T]] =
    returningBehavior match {
      case ReturnAction.ReturnColumns(List(_)) | ReturnAction.ReturnRecord =>
        executePrepared(sql, prepare, connection).flatMap { rows =>
          val generatedId = rows.property(MySQLClient.LAST_INSERTED_ID)
          if (generatedId == null || generatedId.longValue() == 0L) ZIO.succeed(Nil)
          else
            cancelOnInterrupt(connection)(connection.preparedQuery("SELECT ? AS generated_key").execute(Tuple.tuple().addLong(generatedId)))
              .flatMap(result => extractFirstRowSet(result, connection, extractor))
        }
      case _ =>
        ZIO.fail(new UnsupportedOperationException("MySQL returning supports exactly one generated INSERT key"))
    }

  override protected def executeBatchReturning[T](
    sql: String,
    prepares: List[Prepare],
    extractor: Extractor[T],
    returningBehavior: ReturnAction,
    connection: SqlConnection
  ): ZIO[Any, Throwable, List[T]] =
    ZIO.foreach(prepares)(prepare => executeReturning(sql, prepare, extractor, returningBehavior, connection)).map(_.flatten)
}
