package zio.quill.context.vertx

import io.vertx.core.Future
import io.vertx.sqlclient.{Pool, Row, RowSet, SqlConnection, Tuple}
import zio.{Exit, FiberRef, Runtime, Scope, Unsafe, ZIO}
import zio.stream.ZStream
import zio.quill.{NamingStrategy, ReturnAction}
import zio.quill.context._
import zio.quill.context.qzio.ZioContext
import zio.quill.context.sql.SqlContext
import zio.quill.context.sql.idiom.SqlIdiom
import zio.quill.util.ContextLogger

import scala.util.{Failure, Try}
import scala.collection.JavaConverters._

/** Shared ZIO execution core for native Vert.x SQL clients. */
abstract class ZioVertxContext[D <: SqlIdiom, +Naming <: NamingStrategy](val naming: Naming)
    extends ZioContext[D, Naming]
    with SqlContext[D, Naming]
    with ProtoContext[D, Naming]
    with ProtoStreamContext[D, Naming]
    with ContextVerbStream[D, Naming]
    with ContextVerbPrepare
    with VertxTranslateContext
    with VertxRowContext {

  override type Error       = Throwable
  override type Environment = Pool
  override type PrepareRow  = Tuple
  override type ResultRow   = Row
  override type Runner      = Unit
  override type Session     = SqlConnection

  override type StreamResult[T]                  = ZStream[Pool, Throwable, T]
  override type Result[T]                        = ZIO[Pool, Throwable, T]
  override type RunQueryResult[T]                = List[T]
  override type RunQuerySingleResult[T]          = T
  override type RunActionResult                  = Long
  override type RunActionReturningResult[T]      = T
  override type RunBatchActionResult             = List[Long]
  override type RunBatchActionReturningResult[T] = List[T]
  override type PrepareQueryResult               = ZIO[Pool, Throwable, VertxPreparedQuery]
  override type PrepareActionResult              = ZIO[Pool, Throwable, VertxPreparedQuery]
  override type PrepareBatchActionResult         = ZIO[Pool, Throwable, List[VertxPreparedQuery]]

  override val idiom: D
  private[quill] val logger = ContextLogger(getClass)

  private val currentConnection: FiberRef[Option[SqlConnection]] =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(Scope.global.extend(FiberRef.make(Option.empty[SqlConnection]))).getOrThrow()
    }

  override def close(): Unit = ()

  // A synchronous probe cannot safely run a native asynchronous query, so make this limitation explicit.
  override def probe(statement: String): Try[_] =
    Failure(new UnsupportedOperationException("Synchronous SQL probing is unavailable for the native Vert.x context"))

  /**
   * Allow a backend to adapt SQL and bind metadata before the driver prepares a
   * statement.
   */
  protected def prepareBindings(sql: String, tuple: Tuple): (String, Tuple) =
    (sql, tuple)

  /**
   * Asynchronously validate a statement using the native SQL prepare protocol.
   */
  def probeAsync(statement: String): ZIO[Pool, Throwable, Unit] =
    onConnection { connection =>
      ZIO.acquireReleaseWith(VertxRuntime.fromFuture(connection.prepare(statement)))(stmt =>
        VertxRuntime.fromFuture(stmt.close()).ignore
      )(_ => ZIO.unit)
    }

  override def executeQuery[T](sql: String, prepare: Prepare, extractor: Extractor[T])(
    info: ExecutionInfo,
    dc: Runner
  ): ZIO[Pool, Throwable, List[T]] =
    onConnection { connection =>
      executePrepared(sql, prepare, connection).flatMap(rows => extractFirstRowSet(rows, connection, extractor))
    }

  override def executeQuerySingle[T](sql: String, prepare: Prepare, extractor: Extractor[T])(
    info: ExecutionInfo,
    dc: Runner
  ): ZIO[Pool, Throwable, T] =
    executeQuery(sql, prepare, extractor)(info, dc).flatMap(values => ZIO.attempt(handleSingleResult(sql, values)))

  override def executeAction(sql: String, prepare: Prepare)(
    info: ExecutionInfo,
    dc: Runner
  ): ZIO[Pool, Throwable, Long] =
    onConnection { connection =>
      executePrepared(sql, prepare, connection).map(_.rowCount().toLong)
    }

  override def executeActionReturning[T](sql: String, prepare: Prepare, extractor: Extractor[T], returningBehavior: ReturnAction)(
    info: ExecutionInfo,
    dc: Runner
  ): ZIO[Pool, Throwable, T] =
    executeActionReturningMany(sql, prepare, extractor, returningBehavior)(info, dc)
      .flatMap(values => ZIO.attempt(handleSingleResult(sql, values)))

  def executeActionReturningMany[T](sql: String, prepare: Prepare, extractor: Extractor[T], returningBehavior: ReturnAction)(
    info: ExecutionInfo,
    dc: Runner
  ): ZIO[Pool, Throwable, List[T]] =
    onConnection { connection =>
      executeReturning(sql, prepare, extractor, returningBehavior, connection)
    }

  def prepareQuery(sql: String, prepare: Prepare = identityPrepare)(
    info: ExecutionInfo,
    dc: Runner
  ): PrepareQueryResult =
    prepareSingle(sql, prepare)

  def prepareAction(sql: String, prepare: Prepare = identityPrepare)(
    info: ExecutionInfo,
    dc: Runner
  ): PrepareActionResult =
    prepareSingle(sql, prepare)

  def prepareBatchAction(groups: List[BatchGroup])(
    info: ExecutionInfo,
    dc: Runner
  ): PrepareBatchActionResult =
    onConnection { connection =>
      ZIO.foreach(groups.flatMap { case BatchGroup(sql, prepares, _) => prepares.map(sql -> _) }) {
        case (sql, prepare) =>
          ZIO.attempt(prepare(Tuple.tuple(), connection)).map { case (params, tuple) =>
            val (boundSql, boundTuple) = prepareBindings(sql, tuple)
            logger.logBatchItem(boundSql, params)
            preparedQuery(boundSql, boundTuple)
          }
      }
    }

  override def executeBatchAction(groups: List[BatchGroup])(
    info: ExecutionInfo,
    dc: Runner
  ): ZIO[Pool, Throwable, List[Long]] =
    onConnection { connection =>
      ZIO.foreach(groups) { case BatchGroup(sql, prepares, _) =>
        executeBatch(sql, prepares, connection).map(resultCounts)
      }.map(_.flatten)
    }

  override def executeBatchActionReturning[T](groups: List[BatchGroupReturning], extractor: Extractor[T])(
    info: ExecutionInfo,
    dc: Runner
  ): ZIO[Pool, Throwable, List[T]] =
    onConnection { connection =>
      ZIO.foreach(groups) { case BatchGroupReturning(sql, returningBehavior, prepares, _) =>
        executeBatchReturning(sql, prepares, extractor, returningBehavior, connection)
      }.map(_.flatten)
    }

  override def streamQuery[T](fetchSize: Option[Int], sql: String, prepare: Prepare, extractor: Extractor[T])(
    info: ExecutionInfo,
    dc: Runner
  ): ZStream[Pool, Throwable, T] =
    ZStream.unwrapScoped {
      for {
        maybeConnection <- currentConnection.get
        connection      <- maybeConnection match {
                        case Some(current) => ZIO.succeed(current)
                        case None          => ZIO.service[Pool].flatMap(VertxRuntime.acquireConnection)
                      }
        _ <- maybeConnection match {
               case Some(_) => ZIO.unit
               case None    =>
                 ZIO.acquireReleaseExit(
                   VertxRuntime.fromFutureScoped(connection.begin())(tx => VertxRuntime.fromFuture(tx.rollback()).ignore)
                 ) { (streamTransaction, exit) =>
                   exit match {
                     case Exit.Success(_) => VertxRuntime.fromFuture(streamTransaction.commit()).orDie
                     case Exit.Failure(_) => VertxRuntime.fromFuture(streamTransaction.rollback()).ignore
                   }
                 }.unit
             }
        (params, tuple)       <- ZIO.attempt(prepare(Tuple.tuple(), connection))
        (boundSql, boundTuple) = prepareBindings(sql, tuple)
        _                     <- ZIO.succeed(logger.logQuery(boundSql, params))
        statement             <- ZIO.acquireRelease(
                       VertxRuntime.fromFutureScoped(connection.prepare(boundSql))(stmt => VertxRuntime.fromFuture(stmt.close()).ignore)
                     )(stmt => VertxRuntime.fromFuture(stmt.close()).ignore)
        cursor <- ZIO.acquireRelease(ZIO.attempt(statement.cursor(boundTuple)))(current =>
                    VertxRuntime.fromFuture(current.close()).ignore
                  )
      } yield {
        val size = fetchSize.filter(_ > 0).getOrElse(128)
        ZStream.unfoldZIO((cursor, true)) { case (current, firstRead) =>
          if (!firstRead && !current.hasMore) ZIO.succeed(None)
          else
            cancelOnInterrupt(connection)(current.read(size)).flatMap { rows =>
              ZIO.attempt(resultRows(rows).map(row => extractor(row, connection))).map { values =>
                if (values.isEmpty) None else Some((values, (current, false)))
              }
            }
        }.flatMap(ZStream.fromIterable(_))
      }
    }

  def transaction[R <: Pool, A](operation: ZIO[R, Throwable, A]): ZIO[R, Throwable, A] =
    ZIO.uninterruptibleMask { restore =>
      currentConnection.get.flatMap {
        case Some(_) => restore(operation)
        case None    =>
          ZIO.environment[R].flatMap { env =>
            ZIO.scoped[R] {
              for {
                connection  <- restore(ZIO.service[Pool].provideEnvironment(env).flatMap(VertxRuntime.acquireConnection))
                transaction <- ZIO.acquireReleaseExit(
                                 VertxRuntime.fromFutureScoped(connection.begin())(tx => VertxRuntime.fromFuture(tx.rollback()).ignore)
                               ) { (tx, exit) =>
                                 exit match {
                                   case Exit.Success(_) => ZIO.unit
                                   case Exit.Failure(_) => VertxRuntime.fromFuture(tx.rollback()).ignore
                                 }
                               }
                result <- currentConnection.locally(Some(connection)) {
                            restore(operation.provideEnvironment(env)).flatMap { value =>
                              VertxRuntime.fromFuture(transaction.commit()).as(value)
                            }
                          }
              } yield result
            }
          }
      }
    }

  protected final def onConnection[A](f: SqlConnection => ZIO[Any, Throwable, A]): ZIO[Pool, Throwable, A] =
    currentConnection.get.flatMap {
      case Some(connection) => f(connection)
      case None             =>
        ZIO.scoped(ZIO.service[Pool].flatMap(VertxRuntime.acquireConnection).flatMap(f))
    }

  private def prepareSingle(sql: String, prepare: Prepare): PrepareQueryResult =
    onConnection { connection =>
      ZIO.attempt(prepare(Tuple.tuple(), connection)).map { case (params, tuple) =>
        val (boundSql, boundTuple) = prepareBindings(sql, tuple)
        logger.logQuery(boundSql, params)
        preparedQuery(boundSql, boundTuple)
      }
    }

  protected final def executePrepared(
    sql: String,
    prepare: Prepare,
    connection: SqlConnection
  ): ZIO[Any, Throwable, RowSet[Row]] =
    for {
      (params, tuple)       <- ZIO.attempt(prepare(Tuple.tuple(), connection))
      (boundSql, boundTuple) = prepareBindings(sql, tuple)
      _                     <- ZIO.succeed(logger.logQuery(boundSql, params))
      rows                  <- cancelOnInterrupt(connection)(connection.preparedQuery(boundSql).execute(boundTuple))
    } yield rows

  protected final def extractFirstRowSet[T](
    rows: RowSet[Row],
    connection: SqlConnection,
    extractor: Extractor[T]
  ): ZIO[Any, Throwable, List[T]] =
    ZIO.attempt(rows.iterator().asScala.map(row => extractor(row, connection)).toList)

  protected final def executeBatch(
    sql: String,
    prepares: List[Prepare],
    connection: SqlConnection
  ): ZIO[Any, Throwable, RowSet[Row]] =
    for {
      prepared <- ZIO.foreach(prepares)(prepare => ZIO.attempt(prepare(Tuple.tuple(), connection)))
      bound     = prepared.map { case (params, tuple) => (params, prepareBindings(sql, tuple)) }
      boundSql  = bound.headOption.map(_._2._1).getOrElse(sql)
      _        <- ZIO.foreachDiscard(bound) { case (_, (statement, _)) =>
             ZIO.attempt(require(statement == boundSql, "Batch parameter encoding produced inconsistent SQL"))
           }
      tuples  = bound.map(_._2._2)
      _      <- ZIO.succeed(logger.underlying.debug("Batch: {}", boundSql))
      _      <- ZIO.foreachDiscard(bound)(p => ZIO.succeed(logger.logBatchItem(boundSql, p._1)))
      result <- cancelOnInterrupt(connection)(connection.preparedQuery(boundSql).executeBatch(tuples.asJava))
    } yield result

  private def preparedQuery(sql: String, tuple: Tuple): VertxPreparedQuery =
    VertxPreparedQuery(
      sql,
      onConnection(connection => cancelOnInterrupt(connection)(connection.preparedQuery(sql).execute(tuple)))
    )

  protected final def cancelOnInterrupt[A](connection: SqlConnection)(future: => Future[A]): ZIO[Any, Throwable, A] =
    VertxRuntime.fromFuture(future).onInterrupt(cancel(connection).ignore)

  protected def cancel(connection: SqlConnection): ZIO[Any, Throwable, Unit]

  protected def executeReturning[T](
    sql: String,
    prepare: Prepare,
    extractor: Extractor[T],
    returningBehavior: ReturnAction,
    connection: SqlConnection
  ): ZIO[Any, Throwable, List[T]] =
    executePrepared(sql, prepare, connection).flatMap(rows => extractFirstRowSet(rows, connection, extractor))

  protected def executeBatchReturning[T](
    sql: String,
    prepares: List[Prepare],
    extractor: Extractor[T],
    returningBehavior: ReturnAction,
    connection: SqlConnection
  ): ZIO[Any, Throwable, List[T]] =
    executeBatch(sql, prepares, connection).flatMap(rows =>
      ZIO.attempt(resultRows(rows).map(row => extractor(row, connection)))
    )

  private def resultRows(rows: RowSet[Row]): List[Row] = {
    val builder = List.newBuilder[Row]
    var current = rows
    while (current != null) {
      current.iterator().asScala.foreach(builder += _)
      current = current.next()
    }
    builder.result()
  }

  private def resultCounts(rows: RowSet[Row]): List[Long] = {
    val builder = List.newBuilder[Long]
    var current = rows
    while (current != null) {
      builder += current.rowCount().toLong
      current = current.next()
    }
    builder.result()
  }
}
