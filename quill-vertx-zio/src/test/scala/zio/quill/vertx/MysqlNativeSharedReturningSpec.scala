package zio.quill.vertx

import io.vertx.mysqlclient.MySQLConnectOptions
import io.vertx.sqlclient.PoolOptions
import org.scalatest.matchers.should.Matchers._
import zio.quill.{Literal, MysqlZioVertxContext}
import zio.quill.context.sql.base.PeopleReturningSpec
import zio.quill.context.vertx.{VertxBaseDecoders, VertxBaseEncoders, VertxPool, VertxRuntime}

/**
 * Exercises the existing shared SQL returning cases against native
 * MySQL/MariaDB pools.
 */
trait MysqlNativeSharedReturningSupport extends PeopleReturningSpec with MultiSqlZioVertxSpecSupport {
  override val context: MysqlZioVertxContext[Literal] with VertxBaseEncoders with VertxBaseDecoders =
    new MysqlZioVertxContext(Literal)
  import context._

  private val maria  = this.getClass.getSimpleName.startsWith("MariaDB")
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

  override def beforeAll(): Unit = {
    super.beforeAll()
    val version = unsafeRun(VertxRuntime.fromFuture(pool.query("SELECT VERSION()").execute())
      .map(_.iterator().next().getString(0)))
    val isMariaDb = version.toLowerCase(java.util.Locale.ROOT).contains("mariadb")
    if (isMariaDb != maria)
      throw new IllegalStateException(
        s"${this.getClass.getSimpleName} expected ${if (maria) "MariaDB" else "MySQL"}, connected to $version"
      )
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    unsafeRun(context.transaction {
      for {
        _ <- context.run(query[Contact].delete)
        _ <- context.run(query[Product].delete)
        _ <- context.run(liftQuery(people).foreach(p => peopleInsert(p)))
      } yield ()
    })
  }

  "shared MySQL returning one generated key" in {
    import `Ex 0 insert.returning(_.generatedColumn) mod`._
    val id = unsafeRun(context.run(op))
    unsafeRun(context.run(get)).toSet shouldBe result(id).toSet
  }

  "shared MySQL returningMany preserves generated keys" in {
    import `Ex 1 insert.returningMany(_.generatedColumn) mod`._
    val ids = unsafeRun(context.run(op))
    ids should not be empty
    unsafeRun(context.run(get)) shouldBe result(ids.head)
  }
}

class MysqlZioVertxSharedReturningSpec   extends MysqlNativeSharedReturningSupport
class MariaDBZioVertxSharedReturningSpec extends MysqlNativeSharedReturningSupport
