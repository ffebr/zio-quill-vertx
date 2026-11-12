package zio.quill.context.vertx

import io.vertx.core.Vertx
import io.vertx.pgclient.{PgConnectOptions, PgPool}
import io.vertx.mysqlclient.{MySQLConnectOptions, MySQLPool}
import io.vertx.db2client.{DB2ConnectOptions, DB2Pool}
import io.vertx.sqlclient.{Pool, PoolOptions}
import zio.{UIO, ZIO, ZLayer}

/** Scoped constructors for native Vert.x SQL pools. */
object VertxPool {

  /** Create and own both the Vert.x instance and its PostgreSQL pool. */
  def live(connectOptions: PgConnectOptions, poolOptions: PoolOptions): ZLayer[Any, Throwable, Pool] =
    ownedVertx(vertx => PgPool.pool(vertx, connectOptions, poolOptions))

  /** Create a scoped pool using a caller-owned Vert.x instance. */
  def fromVertx(
    vertx: Vertx,
    connectOptions: PgConnectOptions,
    poolOptions: PoolOptions
  ): ZLayer[Any, Throwable, Pool] =
    ZLayer.scoped(ownedPool(PgPool.pool(vertx, connectOptions, poolOptions)))

  def mysql(connectOptions: MySQLConnectOptions, poolOptions: PoolOptions): ZLayer[Any, Throwable, Pool] =
    ownedVertx(vertx => MySQLPool.pool(vertx, connectOptions, poolOptions))

  def mysqlFromVertx(vertx: Vertx, connectOptions: MySQLConnectOptions, poolOptions: PoolOptions): ZLayer[Any, Throwable, Pool] =
    ZLayer.scoped(ownedPool(MySQLPool.pool(vertx, connectOptions, poolOptions)))

  def db2(connectOptions: DB2ConnectOptions, poolOptions: PoolOptions): ZLayer[Any, Throwable, Pool] =
    ownedVertx(vertx => DB2Pool.pool(vertx, connectOptions, poolOptions))

  def db2FromVertx(vertx: Vertx, connectOptions: DB2ConnectOptions, poolOptions: PoolOptions): ZLayer[Any, Throwable, Pool] =
    ZLayer.scoped(ownedPool(DB2Pool.pool(vertx, connectOptions, poolOptions)))

  private def ownedVertx(makePool: Vertx => Pool): ZLayer[Any, Throwable, Pool] =
    ZLayer.scoped {
      ZIO.acquireRelease(ZIO.attempt(Vertx.vertx()))(closeVertx).flatMap(vertx => ownedPool(makePool(vertx)))
    }

  private def ownedPool(makePool: => Pool): ZIO[zio.Scope, Throwable, Pool] =
    ZIO.acquireRelease(ZIO.attempt(makePool))(closePool)

  private def closePool(pool: Pool): UIO[Unit] =
    VertxRuntime.fromFuture(pool.close()).ignore

  private def closeVertx(vertx: Vertx): UIO[Unit] =
    VertxRuntime.fromFuture(vertx.close()).ignore
}
