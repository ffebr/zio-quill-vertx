package zio.quill.vertx

import io.vertx.db2client.DB2ConnectOptions
import io.vertx.mysqlclient.MySQLConnectOptions
import io.vertx.sqlclient.PoolOptions
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers._
import zio.quill.{DB2ZioVertxContext, Literal, MysqlZioVertxContext}
import zio.quill.context.sql.base.PeopleSpec
import zio.quill.context.vertx.{VertxBaseDecoders, VertxBaseEncoders, VertxPool, VertxRuntime}

/**
 * Runs the unchanged shared People examples against each native non-PostgreSQL
 * dialect.
 */
trait MultiSqlSharedPeopleFixture extends PeopleSpec with MultiSqlZioVertxSpecSupport with BeforeAndAfterEach {
  protected def ensurePeopleTables(): Unit
  protected def seedPeople(): Unit

  protected final def raw(statement: String): Unit =
    unsafeRun(VertxRuntime.fromFuture(pool.query(statement).execute()).unit)

  protected final def rawEither(statement: String): Either[Throwable, Unit] =
    unsafeRun(VertxRuntime.fromFuture(pool.query(statement).execute()).unit.either)

  protected final def clearPeopleTables(): Unit = {
    raw("DELETE FROM Couple")
    raw("DELETE FROM Person")
  }

  override def beforeAll(): Unit = {
    super.beforeAll()
    ensurePeopleTables()
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    clearPeopleTables()
    seedPeople()
  }
}

class MysqlZioVertxSharedPeopleSpec extends MultiSqlSharedPeopleFixture {
  override val context: MysqlZioVertxContext[Literal] with VertxBaseEncoders with VertxBaseDecoders =
    new MysqlZioVertxContext(Literal)
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

  override protected def ensurePeopleTables(): Unit = {
    raw("CREATE TABLE IF NOT EXISTS Person (name VARCHAR(255) NOT NULL, age INT NOT NULL)")
    raw("CREATE TABLE IF NOT EXISTS Couple (her VARCHAR(255) NOT NULL, him VARCHAR(255) NOT NULL)")
  }

  override protected def seedPeople(): Unit = unsafeRun(context.transaction {
    for {
      _ <- context.run(liftQuery(peopleEntries).foreach(p => peopleInsert(p)))
      _ <- context.run(liftQuery(couplesEntries).foreach(p => couplesInsert(p)))
    } yield ()
  })

  "Example 1 - differences" in {
    unsafeRun(context.run(`Ex 1 differences`)) should contain theSameElementsAs `Ex 1 expected result`
  }

  "Example 2 - range simple" in {
    unsafeRun(context.run(`Ex 2 rangeSimple`(lift(`Ex 2 param 1`), lift(`Ex 2 param 2`)))) should contain theSameElementsAs `Ex 2 expected result`
  }

  "Example 3 - satisfies" in {
    unsafeRun(context.run(`Ex 3 satisfies`)) should contain theSameElementsAs `Ex 3 expected result`
  }

  "Example 4 - satisfies" in {
    unsafeRun(context.run(`Ex 4 satisfies`)) should contain theSameElementsAs `Ex 4 expected result`
  }

  "Example 5 - compose" in {
    unsafeRun(context.run(`Ex 5 compose`(lift(`Ex 5 param 1`), lift(`Ex 5 param 2`)))) mustEqual `Ex 5 expected result`
  }

  "Example 6 - predicate 0" in {
    unsafeRun(context.run(satisfies(eval(`Ex 6 predicate`)))) mustEqual `Ex 6 expected result`
  }

  "Example 7 - predicate 1" in {
    unsafeRun(context.run(satisfies(eval(`Ex 7 predicate`)))) mustEqual `Ex 7 expected result`
  }

  "Example 8 - contains empty" in {
    unsafeRun(context.run(`Ex 8 and 9 contains`(liftQuery(`Ex 8 param`)))) mustEqual `Ex 8 expected result`
  }

  "Example 9 - contains non empty" in {
    unsafeRun(context.run(`Ex 8 and 9 contains`(liftQuery(`Ex 9 param`)))) mustEqual `Ex 9 expected result`
  }

  "Example 10 - pagination" in {
    unsafeRun(context.run(`Ex 10 page 1 query`)) mustEqual `Ex 10 page 1 expected`
    unsafeRun(context.run(`Ex 10 page 2 query`)) mustEqual `Ex 10 page 2 expected`
  }
}

class MariaDBZioVertxSharedPeopleSpec extends MysqlZioVertxSharedPeopleSpec

class DB2ZioVertxSharedPeopleSpec extends MultiSqlSharedPeopleFixture {
  override val context: DB2ZioVertxContext[Literal] with VertxBaseEncoders with VertxBaseDecoders =
    new DB2ZioVertxContext(Literal)
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

  override protected def ensurePeopleTables(): Unit = {
    def create(statement: String): Unit = rawEither(statement).fold(
      error => if (!MultiSqlNativeTestOps.sqlState(error).contains("42710")) throw error,
      _ => ()
    )
    create("CREATE TABLE Person (name VARCHAR(255) NOT NULL, age INT NOT NULL)")
    create("CREATE TABLE Couple (her VARCHAR(255) NOT NULL, him VARCHAR(255) NOT NULL)")
  }

  override protected def seedPeople(): Unit = unsafeRun(context.transaction {
    for {
      _ <- context.run(liftQuery(peopleEntries).foreach(p => peopleInsert(p)))
      _ <- context.run(liftQuery(couplesEntries).foreach(p => couplesInsert(p)))
    } yield ()
  })

  "Example 1 - differences" in {
    unsafeRun(context.run(`Ex 1 differences`)) should contain theSameElementsAs `Ex 1 expected result`
  }

  "Example 2 - range simple" in {
    unsafeRun(context.run(`Ex 2 rangeSimple`(lift(`Ex 2 param 1`), lift(`Ex 2 param 2`)))) should contain theSameElementsAs `Ex 2 expected result`
  }

  "Example 3 - satisfies" in {
    unsafeRun(context.run(`Ex 3 satisfies`)) should contain theSameElementsAs `Ex 3 expected result`
  }

  "Example 4 - satisfies" in {
    unsafeRun(context.run(`Ex 4 satisfies`)) should contain theSameElementsAs `Ex 4 expected result`
  }

  "Example 5 - compose" in {
    unsafeRun(context.run(`Ex 5 compose`(lift(`Ex 5 param 1`), lift(`Ex 5 param 2`)))) mustEqual `Ex 5 expected result`
  }

  "Example 6 - predicate 0" in {
    unsafeRun(context.run(satisfies(eval(`Ex 6 predicate`)))) mustEqual `Ex 6 expected result`
  }

  "Example 7 - predicate 1" in {
    unsafeRun(context.run(satisfies(eval(`Ex 7 predicate`)))) mustEqual `Ex 7 expected result`
  }

  "Example 8 - contains empty" in {
    unsafeRun(context.run(`Ex 8 and 9 contains`(liftQuery(`Ex 8 param`)))) mustEqual `Ex 8 expected result`
  }

  "Example 9 - contains non empty" in {
    unsafeRun(context.run(`Ex 8 and 9 contains`(liftQuery(`Ex 9 param`)))) mustEqual `Ex 9 expected result`
  }

  "Example 10 - pagination" in {
    unsafeRun(context.run(`Ex 10 page 1 query`)) mustEqual `Ex 10 page 1 expected`
    unsafeRun(context.run(`Ex 10 page 2 query`)) mustEqual `Ex 10 page 2 expected`
  }
}
