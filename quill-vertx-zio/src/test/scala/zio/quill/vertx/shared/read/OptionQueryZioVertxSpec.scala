package zio.quill.vertx.shared.read

import zio.quill.vertx.VertxPostgresSpecSupport

import zio.quill.context.sql.OptionQuerySpec
import org.scalatest.matchers.should.Matchers._

class OptionQueryZioVertxSpec extends OptionQuerySpec with VertxPostgresSpecSupport {

  import context._

  override def beforeAll(): Unit = {
    super.beforeAll()
    unsafeRun(context.transaction {
      for {
        _ <- context.run(query[Contact].delete)
        _ <- context.run(query[Address].delete)
        _ <- context.run(query[Task].delete)
        _ <- context.run(liftQuery(peopleEntries).foreach(p => peopleInsert(p)))
        _ <- context.run(liftQuery(addressEntries).foreach(p => addressInsert(p)))
        _ <- context.run(liftQuery(taskEntries).foreach(p => taskInsert(p)))
      } yield ()
    })
  }

  "Example 1 - Simple Map with Condition" in {
    unsafeRun(context.run(`Simple Map with Condition`)) should contain theSameElementsAs `Simple Map with Condition Result`
  }

  "Example 1.1 - Simple Map with GetOrElse" in {
    unsafeRun(context.run(`Simple Map with GetOrElse`)) should contain theSameElementsAs `Simple Map with GetOrElse Result`
  }

  "Example 1.2 - Simple Map with Condition and GetOrElse" in {
    unsafeRun(context.run(
      `Simple Map with Condition and GetOrElse`
    )) should contain theSameElementsAs `Simple Map with Condition and GetOrElse Result`
  }

  "Example 1.3 - Simple Map with OrElse" in {
    unsafeRun(context.run(`Simple Map with OrElse`)) should contain theSameElementsAs `Simple Map with OrElse Result`
  }

  "Example 1.4 - Simple Map with Condition and OrElse" in {
    unsafeRun(context.run(
      `Simple Map with Condition and OrElse`
    )) should contain theSameElementsAs `Simple Map with Condition and OrElse Result`
  }

  "Example 2 - Simple GetOrElse" in {
    unsafeRun(context.run(`Simple GetOrElse`)) should contain theSameElementsAs `Simple GetOrElse Result`
  }

  "Example 2.1 - Simple OrElse" in {
    unsafeRun(context.run(`Simple OrElse`)) should contain theSameElementsAs `Simple OrElse Result`
  }

  "Example 3 - LeftJoin with FlatMap" in {
    unsafeRun(context.run(`LeftJoin with FlatMap`)) should contain theSameElementsAs `LeftJoin with FlatMap Result`
  }

  "Example 4 - LeftJoin with Flatten" in {
    unsafeRun(context.run(`LeftJoin with Flatten`)) should contain theSameElementsAs `LeftJoin with Flatten Result`
  }

  "Example 5 - Map+getOrElse Join" in {
    unsafeRun(context.run(`Map+getOrElse LeftJoin`)) should contain theSameElementsAs `Map+getOrElse LeftJoin Result`
  }

  "Example 6 - Map+Option+Flatten+getOrElse Join" in {
    unsafeRun(context.run(`Option+Some+None Normalize`)) should contain theSameElementsAs `Option+Some+None Normalize Result`
  }

  "Example 7 - Filter with OrElse and Forall" in {
    unsafeRun(context.run(
      `Filter with OrElse and Forall`
    )) should contain theSameElementsAs `Filter with OrElse and Forall Result`
  }

  "Example 7.1 - Filter with OrElse and Exists" in {
    unsafeRun(context.run(
      `Filter with OrElse and Exists`
    )) should contain theSameElementsAs `Filter with OrElse and Exists Result`
  }
}
