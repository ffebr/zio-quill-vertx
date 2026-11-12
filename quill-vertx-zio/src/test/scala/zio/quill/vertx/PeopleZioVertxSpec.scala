package zio.quill.vertx

import org.scalatest.matchers.should.Matchers._
import zio.quill.context.sql.base.PeopleSpec

class PeopleZioVertxSpec extends PeopleSpec with VertxPostgresSpecSupport {
  import context._

  val `Ex 11 query`    = quote(query[Person])
  val `Ex 11 expected` = peopleEntries

  override def beforeAll(): Unit = {
    super.beforeAll()
    unsafeRun(context.transaction {
      for {
        _ <- context.run(query[Couple].delete)
        _ <- context.run(query[Person].delete)
        _ <- context.run(liftQuery(peopleEntries).foreach(p => peopleInsert(p)))
        _ <- context.run(liftQuery(couplesEntries).foreach(p => couplesInsert(p)))
      } yield ()
    })
  }

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

  "Example 11 - streaming" in {
    collect(context.stream(`Ex 11 query`, 4)) should contain theSameElementsAs `Ex 11 expected`
  }

  "Example 12 - probe" in {
    context.probe("select 1").failed.get shouldBe a[UnsupportedOperationException]
  }
}
