package zio.quill.vertx

import io.vertx.db2client.DB2ConnectOptions
import io.vertx.mysqlclient.MySQLConnectOptions
import io.vertx.sqlclient.PoolOptions
import org.scalatest.matchers.should.Matchers._
import zio.quill.{DB2ZioVertxContext, Literal, MysqlZioVertxContext, Query}
import zio.quill.context.sql.{EncodingSpec, TestDecoders, TestEncoders}
import zio.quill.context.vertx.{VertxBaseDecoders, VertxBaseEncoders, VertxPool}

/** Owns the table used by the unchanged shared EncodingSpec fixtures. */
trait MultiSqlSharedEncodingTables extends MultiSqlZioVertxSpecSupport {
  protected def createEncodingTable: String
  protected def dropEncodingTable: String

  private def executeSql(statement: String): Unit =
    unsafeRun(zio.quill.context.vertx.VertxRuntime.fromFuture(pool.query(statement).execute()).unit)

  protected def dropEncodingTableIfPresent(): Unit = executeSql(dropEncodingTable)

  override def beforeAll(): Unit = {
    super.beforeAll()
    dropEncodingTableIfPresent()
    executeSql(createEncodingTable)
  }

  override def afterAll(): Unit =
    try {
      if (poolIsReady) dropEncodingTableIfPresent()
    } finally super.afterAll()
}

/** The original shared assertions are intentionally kept unchanged. */
trait MysqlSharedEncodingSupport extends EncodingSpec with MultiSqlSharedEncodingTables {
  override val context: MysqlZioVertxContext[Literal] with TestEncoders with TestDecoders =
    new MysqlZioVertxContext(Literal) with TestEncoders with TestDecoders
  import context._

  private val maria  = getClass.getSimpleName.startsWith("MariaDB")
  private val prefix = if (maria) "MARIADB" else "MYSQL"

  override protected def poolLayer(maxSize: Int) = VertxPool.mysql(
    new MySQLConnectOptions()
      .setHost(MultiSqlTestEnv.value(s"${prefix}_HOST", "MYSQL_HOST", "127.0.0.1"))
      .setPort(MultiSqlTestEnv.port(s"${prefix}_PORT", "MYSQL_PORT", if (maria) 53307 else 53306))
      .setDatabase(MultiSqlTestEnv.first("quill_vertx_test", s"${prefix}_DB", s"${prefix}_DATABASE", "MYSQL_DB", "MYSQL_DATABASE"))
      .setUser(MultiSqlTestEnv.value(s"${prefix}_USER", "MYSQL_USER", "root"))
      .setPassword(MultiSqlTestEnv.value(s"${prefix}_PASSWORD", "MYSQL_PASSWORD", "quill_test_only")),
    new PoolOptions().setMaxSize(maxSize)
  )

  override protected def createEncodingTable =
    """CREATE TABLE EncodingTestEntity (
      |v1 VARCHAR(255), v2 DECIMAL(5,2), v3 BOOLEAN, v4 SMALLINT, v5 SMALLINT, v6 INTEGER,
      |v7 BIGINT, v8 FLOAT, v9 DOUBLE, v10 VARBINARY(255), v11 DATETIME, v12 VARCHAR(255),
      |v13 DATE, v14 VARCHAR(36), o1 VARCHAR(255), o2 DECIMAL(5,2), o3 BOOLEAN,
      |o4 SMALLINT, o5 SMALLINT, o6 INTEGER, o7 BIGINT, o8 FLOAT, o9 DOUBLE,
      |o10 VARBINARY(255), o11 DATETIME, o12 VARCHAR(255), o13 DATE, o14 VARCHAR(36), o15 VARCHAR(255)
      |)""".stripMargin
  override protected def dropEncodingTable = "DROP TABLE IF EXISTS EncodingTestEntity"

  "encodes and decodes types" in {
    unsafeRun(context.run(delete))
    unsafeRun(context.run(liftQuery(insertValues).foreach(e => insert(e))))
    verify(unsafeRun(context.run(query[EncodingTestEntity])))
  }

  "encodes sets" in {
    unsafeRun(context.run(query[EncodingTestEntity].delete))
    unsafeRun(context.run(liftQuery(insertValues).foreach(e => query[EncodingTestEntity].insertValue(e))))
    val q      = quote { (set: Query[Int]) => query[EncodingTestEntity].filter(t => set.contains(t.v6)) }
    val actual = unsafeRun(context.run(q(liftQuery(insertValues.map(_.v6)))))
    verify(actual)
  }

}

class MysqlZioVertxSharedEncodingSpec   extends MysqlSharedEncodingSupport
class MariaDBZioVertxSharedEncodingSpec extends MysqlSharedEncodingSupport

class DB2ZioVertxSharedEncodingSpec extends EncodingSpec with MultiSqlSharedEncodingTables {
  override val context: DB2ZioVertxContext[Literal] with TestEncoders with TestDecoders =
    new DB2ZioVertxContext(Literal) with TestEncoders with TestDecoders
  import context._

  override protected def poolLayer(maxSize: Int) = VertxPool.db2(
    new DB2ConnectOptions()
      .setHost(MultiSqlTestEnv.value("DB2_HOST", "DB2_HOST", "127.0.0.1"))
      .setPort(MultiSqlTestEnv.port("DB2_PORT", "DB2_PORT", 50500))
      .setDatabase(MultiSqlTestEnv.value("DB2_DB", "DB2_DATABASE", "QTEST"))
      .setUser(MultiSqlTestEnv.value("DB2_USER", "DB2_USER", "db2inst1"))
      .setPassword(MultiSqlTestEnv.value("DB2_PASSWORD", "DB2_PASSWORD", "Quill_Test_only1!")),
    new PoolOptions().setMaxSize(maxSize)
  )

  override protected def createEncodingTable =
    """CREATE TABLE EncodingTestEntity (
      |v1 VARCHAR(255), v2 DECIMAL(5,2), v3 BOOLEAN, v4 SMALLINT, v5 SMALLINT, v6 INTEGER,
      |v7 BIGINT, v8 REAL, v9 DOUBLE, v10 VARBINARY(255), v11 TIMESTAMP, v12 VARCHAR(255),
      |v13 DATE, v14 VARCHAR(36), o1 VARCHAR(255), o2 DECIMAL(5,2), o3 BOOLEAN,
      |o4 SMALLINT, o5 SMALLINT, o6 INTEGER, o7 BIGINT, o8 REAL, o9 DOUBLE,
      |o10 VARBINARY(255), o11 TIMESTAMP, o12 VARCHAR(255), o13 DATE, o14 VARCHAR(36), o15 VARCHAR(255)
      |)""".stripMargin
  override protected def dropEncodingTable = "DROP TABLE EncodingTestEntity"

  "encodes and decodes types" in {
    unsafeRun(context.run(delete))
    unsafeRun(context.run(liftQuery(insertValues).foreach(e => insert(e))))
    verify(unsafeRun(context.run(query[EncodingTestEntity])))
  }

  "encodes sets" in {
    unsafeRun(context.run(query[EncodingTestEntity].delete))
    unsafeRun(context.run(liftQuery(insertValues).foreach(e => query[EncodingTestEntity].insertValue(e))))
    val q      = quote { (set: Query[Int]) => query[EncodingTestEntity].filter(t => set.contains(t.v6)) }
    val actual = unsafeRun(context.run(q(liftQuery(insertValues.map(_.v6)))))
    verify(actual)
  }

  override protected def dropEncodingTableIfPresent(): Unit =
    unsafeRun(zio.quill.context.vertx.VertxRuntime.fromFuture(pool.query(dropEncodingTable).execute()).either).fold(
      error => if (!MultiSqlNativeTestOps.sqlState(error).contains("42704")) throw error,
      _ => ()
    )

}
