package zio.quill.vertx.shared.write

import org.scalatest.BeforeAndAfterEach
import zio.quill.context.sql.base.PeopleSpec
import zio.quill.vertx.VertxPostgresSpecSupport

class PeopleZioVertxFilteredUpdatesSpec extends PeopleSpec with VertxPostgresSpecSupport with BeforeAndAfterEach {
  import context._

  override def beforeEach(): Unit = {
    super.beforeEach()
    unsafeRun(context.transaction {
      for {
        _ <- context.run(query[Couple].delete)
        _ <- context.run(query[Person].delete)
        _ <- context.run(liftQuery(peopleEntries).foreach(p => peopleInsert(p)))
        _ <- context.run(liftQuery(couplesEntries).foreach(p => couplesInsert(p)))
      } yield ()
    })
  }

  "Example 11 - filtered update" in {
    unsafeRun(context.run(`Ex 11 filtered update`))
    unsafeRun(context.run(`Ex 11 filtered update get`)) must contain theSameElementsAs `Ex 11 filtered update expected`
  }

  "Example 12 - filtered update co-related" in {
    unsafeRun(context.run(`Ex 12 filtered update co-related`))
    unsafeRun(context.run(`Ex 12 filtered update co-related get`)) must contain theSameElementsAs `Ex 12 filtered update co-related expected`
  }
}
