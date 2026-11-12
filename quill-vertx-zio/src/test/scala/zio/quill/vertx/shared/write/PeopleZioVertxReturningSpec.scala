package zio.quill.vertx.shared.write

import org.scalatest.BeforeAndAfterEach
import zio.quill.context.sql.base.PeopleReturningSpec
import zio.quill.vertx.VertxPostgresSpecSupport

class PeopleZioVertxReturningSpec extends PeopleReturningSpec with VertxPostgresSpecSupport with BeforeAndAfterEach {
  import context._

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

  "Ex 0 insert.returning(_.generatedColumn) mod" in {
    import `Ex 0 insert.returning(_.generatedColumn) mod`._
    val id = unsafeRun(context.run(op))
    unsafeRun(context.run(get)).toSet mustEqual result(id).toSet
  }

  "Ex 0.5 insert.returning(wholeRecord) mod" in {
    import `Ex 0.5 insert.returning(wholeRecord) mod`._
    val newProduct = unsafeRun(context.run(op))
    unsafeRun(context.run(get)) mustEqual result(newProduct)
  }

  "Ex 1 insert.returningMany(_.generatedColumn) mod" in {
    import `Ex 1 insert.returningMany(_.generatedColumn) mod`._
    val id = unsafeRun(context.run(op))
    unsafeRun(context.run(get)) mustEqual result(id.head)
  }

  "Ex 2 update.returningMany(_.singleColumn) mod" in {
    import `Ex 2 update.returningMany(_.singleColumn) mod`._
    unsafeRun(context.run(op)) must contain theSameElementsAs expect
    unsafeRun(context.run(get)) must contain theSameElementsAs result
  }

  "Ex 3 delete.returningMany(wholeRecord)" in {
    import `Ex 3 delete.returningMany(wholeRecord)`._
    unsafeRun(context.run(op)) must contain theSameElementsAs expect
    unsafeRun(context.run(get)) must contain theSameElementsAs result
  }

  "Ex 4 update.returningMany(query)" in {
    import `Ex 4 update.returningMany(query)`._
    unsafeRun(context.run(op)) must contain theSameElementsAs expect
    unsafeRun(context.run(get)) must contain theSameElementsAs result
  }
}
