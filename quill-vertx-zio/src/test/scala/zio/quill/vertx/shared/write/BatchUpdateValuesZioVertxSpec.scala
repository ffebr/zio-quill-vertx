package zio.quill.vertx.shared.write

import org.scalatest.BeforeAndAfterEach
import zio.quill.context.sql.base.BatchUpdateValuesSpec
import zio.quill.vertx.VertxPostgresSpecSupport
import zio.quill.{PostgresZioVertxContext, SnakeCase}

class BatchUpdateValuesZioVertxSpec extends BatchUpdateValuesSpec with VertxPostgresSpecSupport with BeforeAndAfterEach {
  import context._

  val testContextSnake = new PostgresZioVertxContext(SnakeCase) {}

  override def beforeEach(): Unit = {
    super.beforeEach()
    val schema  = quote(querySchema[ContactBase]("Contact"))
    val schema2 = quote(querySchema[ContactBase]("contact_snake"))
    unsafeRun(context.transaction {
      for {
        _ <- context.run(schema.delete)
        _ <- context.run(schema2.delete)
      } yield ()
    })
  }

  "Ex 1 - Simple Contact" in {
    import `Ex 1 - Simple Contact`._
    unsafeRun(context.run(insert))
    unsafeRun(context.run(update, 2))
    unsafeRun(context.run(get)).toSet mustEqual expect.toSet
  }

  "Ex 1.1 - Simple Contact With Lift" in {
    import `Ex 1.1 - Simple Contact With Lift`._
    unsafeRun(context.run(insert))
    unsafeRun(context.run(update, 2))
    unsafeRun(context.run(get)).toSet mustEqual expect.toSet
  }

  "Ex 1.2 - Simple Contact Mixed Lifts" in {
    import `Ex 1.2 - Simple Contact Mixed Lifts`._
    unsafeRun(context.run(insert))
    unsafeRun(context.run(update, 2))
    unsafeRun(context.run(get)).toSet mustEqual expect.toSet
  }

  "Ex 1.3 - Simple Contact with Multi-Lift-Kinds" in {
    import `Ex 1.3 - Simple Contact with Multi-Lift-Kinds`._
    unsafeRun(context.run(insert))
    unsafeRun(context.run(update, 2))
    unsafeRun(context.run(get)).toSet mustEqual expect.toSet
  }

  "Ex 2 - Optional Embedded with Renames" in {
    import `Ex 2 - Optional Embedded with Renames`._
    unsafeRun(context.run(insert))
    unsafeRun(context.run(update, 2))
    unsafeRun(context.run(get)).toSet mustEqual expect.toSet
  }

  "Ex 3 - Deep Embedded Optional" in {
    import `Ex 3 - Deep Embedded Optional`._
    unsafeRun(context.run(insert))
    unsafeRun(context.run(update, 2))
    unsafeRun(context.run(get)).toSet mustEqual expect.toSet
  }

  "Ex 4 - Returning" in {
    import `Ex 4 - Returning`._
    unsafeRun(context.run(insert))
    val agesReturned = unsafeRun(context.run(update, 2))
    agesReturned mustEqual expectedReturn
    unsafeRun(context.run(get)).toSet mustEqual expect.toSet
  }

  "Ex 4 - Returning Multiple" in {
    import `Ex 4 - Returning Multiple`._
    unsafeRun(context.run(insert))
    val agesReturned = unsafeRun(context.run(update, 2))
    agesReturned mustEqual expectedReturn
    unsafeRun(context.run(get)).toSet mustEqual expect.toSet
  }

  "Ex 4 - Returning Multiple (Snake Case)" in {
    import `Ex 4 - Returning Multiple (Snake Case)`._
    import testContextSnake._
    unsafeRun(testContextSnake.run(insert))
    val agesReturned = unsafeRun(testContextSnake.run(update, 2))
    agesReturned mustEqual expectedReturn
    unsafeRun(testContextSnake.run(get)).toSet mustEqual expect.toSet
  }

  "Ex 4 - Returning Updated Values (Snake Case)" in {
    import `Ex 4 - Returning Updated Values (Snake Case)`._
    import testContextSnake._
    unsafeRun(testContextSnake.run(insert))
    unsafeRun(testContextSnake.run(update, 2)) mustEqual expectedReturn
    unsafeRun(testContextSnake.run(get)).toSet mustEqual expect.toSet
  }

  "Ex 5 - Append Data" in {
    System.setProperty("quill.binds.log", "true")
    zio.quill.util.Messages.resetCache()
    import `Ex 5 - Append Data`._
    unsafeRun(context.run(insert))
    unsafeRun(context.run(update, 2))
    unsafeRun(context.run(get)).toSet mustEqual expectSpecific.toSet
  }

  "Ex 6 - Append Data No Condition" in {
    import `Ex 6 - Append Data No Condition`._
    unsafeRun(context.run(insert))
    unsafeRun(context.run(update, 2))
    unsafeRun(context.run(get)).toSet mustEqual expectSpecific.toSet
  }
}
