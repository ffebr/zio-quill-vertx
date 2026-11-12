package zio.quill.vertx.shared.write

import org.scalatest.BeforeAndAfterEach
import zio.quill._
import zio.quill.context.sql.base.BatchValuesSpec
import zio.quill.vertx.VertxPostgresSpecSupport

class BatchValuesZioVertxSpec extends BatchValuesSpec with VertxPostgresSpecSupport with BeforeAndAfterEach {
  import context._

  override def beforeEach(): Unit = {
    super.beforeEach()
    unsafeRun(context.run(sql"TRUNCATE TABLE Product RESTART IDENTITY CASCADE".as[Delete[Product]]))
  }

  "Ex 1 - Batch Insert Normal" in {
    import `Ex 1 - Batch Insert Normal`._
    unsafeRun(context.run(op, batchSize))
    unsafeRun(context.run(get)) mustEqual result
  }

  "Ex 2 - Batch Insert Returning" in {
    import `Ex 2 - Batch Insert Returning`._
    val ids = unsafeRun(context.run(op, batchSize))
    ids mustEqual expectedIds
    unsafeRun(context.run(get)) mustEqual result
  }

  "Ex 3 - Batch Insert Mixed" in {
    import `Ex 3 - Batch Insert Mixed`._
    unsafeRun(context.run(op, batchSize))
    unsafeRun(context.run(get)) mustEqual result
  }
}
