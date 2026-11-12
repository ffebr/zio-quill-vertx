package zio.quill.vertx.shared.write

import zio.quill.{Literal, PostgresZioVertxContext, TestEntities}
import zio.quill.context.sql.base.OnConflictSpec
import zio.quill.vertx.VertxPostgresSpecSupport

class OnConflictZioVertxSpec extends OnConflictSpec with VertxPostgresSpecSupport {
  override val ctx = new PostgresZioVertxContext(Literal) with TestEntities {}
  import ctx._

  override def beforeAll(): Unit = {
    super.beforeAll()
    unsafeRun(ctx.run(qr1.delete))
  }

  "ON CONFLICT DO NOTHING" in {
    import `onConflictIgnore`._
    unsafeRun(ctx.run(testQuery1)) mustEqual res1
    unsafeRun(ctx.run(testQuery2)) mustEqual res2
    unsafeRun(ctx.run(testQuery3)) mustEqual res3
  }

  "ON CONFLICT (i) DO NOTHING" in {
    import `onConflictIgnore(_.i)`._
    unsafeRun(ctx.run(testQuery1)) mustEqual res1
    unsafeRun(ctx.run(testQuery2)) mustEqual res2
    unsafeRun(ctx.run(testQuery3)) mustEqual res3
  }

  "ON CONFLICT (i) DO UPDATE ..." in {
    import `onConflictUpdate(_.i)((t, e) => ...)`._
    unsafeRun(ctx.run(testQuery(e1))) mustEqual res1
    unsafeRun(ctx.run(testQuery(e2))) mustEqual res2
    unsafeRun(ctx.run(testQuery(e3))) mustEqual res3
    unsafeRun(ctx.run(testQuery4)) mustEqual res4
  }

  "ON CONFLICT (i) DO UPDATE with schemaMeta ..." in {
    case class TestEntityRename(s2: String, i2: Int, l2: Long, o2: Option[Int])

    def testQuery(e: TestEntityRename) = quote {
      querySchema[TestEntityRename](
        "TestEntity",
        _.s2 -> "s",
        _.i2 -> "i",
        _.l2 -> "l",
        _.o2 -> "o"
      ).insertValue(lift(e)).onConflictUpdate(_.i2)((t, _) => t.l2 -> (t.l2 + 1))
    }

    val e1Rename = TestEntityRename("r1", 4, 0, None)

    unsafeRun(ctx.run(testQuery(e1Rename))) mustEqual 1
  }
}
