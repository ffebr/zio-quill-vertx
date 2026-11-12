package zio.quill.vertx.shared.encoding

import org.scalatest.matchers.should.Matchers._
import zio.quill.vertx.VertxPostgresSpecSupport

class NativeArrayOpsSpec extends VertxPostgresSpecSupport with zio.quill.context.sql.base.ArrayOpsSpec {
  val ctx = context
  import ctx._

  override def beforeAll(): Unit = {
    super.beforeAll()
    unsafeRun(ctx.run(entity.delete))
    unsafeRun(ctx.run(insertEntries))
  }

  "contains" in {
    unsafeRun(ctx.run(`contains`.`Ex 1 return all`)) mustBe `contains`.`Ex 1 expected`
    unsafeRun(ctx.run(`contains`.`Ex 2 return 1`)) mustBe `contains`.`Ex 2 expected`
    unsafeRun(ctx.run(`contains`.`Ex 3 return 2,3`)) mustBe `contains`.`Ex 3 expected`
    unsafeRun(ctx.run(`contains`.`Ex 4 return empty`)) mustBe `contains`.`Ex 4 expected`
  }
}
