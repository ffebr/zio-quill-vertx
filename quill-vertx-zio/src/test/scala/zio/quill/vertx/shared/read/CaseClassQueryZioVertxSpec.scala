package zio.quill.vertx.shared.read

import zio.quill.vertx.VertxPostgresSpecSupport

import zio.quill.context.sql.base.CaseClassQuerySpec

import org.scalatest.matchers.should.Matchers._

class CaseClassQueryZioVertxSpec extends CaseClassQuerySpec with VertxPostgresSpecSupport {

  import context._

  override def beforeAll(): Unit = {
    super.beforeAll()
    unsafeRun(context.transaction {
      for {
        _ <- context.run(query[Contact].delete)
        _ <- context.run(query[Address].delete)
        _ <- context.run(liftQuery(peopleEntries).foreach(p => peopleInsert(p)))
        _ <- context.run(liftQuery(addressEntries).foreach(p => addressInsert(p)))
      } yield ()
    })
  }

  "Example 1 - Single Case Class Mapping" in {
    unsafeRun(context.run(
      `Ex 1 CaseClass Record Output`
    )) should contain theSameElementsAs `Ex 1 CaseClass Record Output expected result`
  }
  "Example 1A - Single Case Class Mapping" in {
    unsafeRun(context.run(
      `Ex 1A CaseClass Record Output`
    )) should contain theSameElementsAs `Ex 1 CaseClass Record Output expected result`
  }
  "Example 1B - Single Case Class Mapping" in {
    unsafeRun(context.run(
      `Ex 1B CaseClass Record Output`
    )) should contain theSameElementsAs `Ex 1 CaseClass Record Output expected result`
  }

  "Example 2 - Single Record Mapped Join" in {
    unsafeRun(context.run(
      `Ex 2 Single-Record Join`
    )) should contain theSameElementsAs `Ex 2 Single-Record Join expected result`
  }

  "Example 3 - Inline Record as Filter" in {
    unsafeRun(context.run(
      `Ex 3 Inline Record Usage`
    )) should contain theSameElementsAs `Ex 3 Inline Record Usage expected result`
  }

  "Example 4 - Ex 4 Mapped Union of Nicknames" in {
    unsafeRun(context.run(
      `Ex 4 Mapped Union of Nicknames`
    )) should contain theSameElementsAs `Ex 4 Mapped Union of Nicknames expected result`
  }

  "Example 4 - Ex 4 Mapped Union All of Nicknames" in {
    unsafeRun(context.run(
      `Ex 4 Mapped Union All of Nicknames`
    )) should contain theSameElementsAs `Ex 4 Mapped Union All of Nicknames expected result`
  }

  "Example 4 - Ex 4 Mapped Union All of Nicknames Filtered" in {
    unsafeRun(context.run(
      `Ex 4 Mapped Union All of Nicknames Filtered`
    )) should contain theSameElementsAs `Ex 4 Mapped Union All of Nicknames Filtered expected result`
  }

  "Example 4 - Ex 4 Mapped Union All of Nicknames Same Field" in {
    unsafeRun(context.run(
      `Ex 4 Mapped Union All of Nicknames Same Field`
    )) should contain theSameElementsAs `Ex 4 Mapped Union All of Nicknames Same Field expected result`
  }

  "Example 4 - Ex 4 Mapped Union All of Nicknames Same Field Filtered" in {
    unsafeRun(context.run(
      `Ex 4 Mapped Union All of Nicknames Same Field Filtered`
    )) should contain theSameElementsAs `Ex 4 Mapped Union All of Nicknames Same Field Filtered expected result`
  }
}
