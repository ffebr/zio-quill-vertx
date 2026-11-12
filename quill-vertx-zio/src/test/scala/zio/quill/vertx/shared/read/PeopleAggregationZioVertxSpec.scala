package zio.quill.vertx.shared.read

import zio.quill.vertx.VertxPostgresSpecSupport

import zio.quill.context.sql.base.PeopleAggregationSpec

class PeopleAggregationZioVertxSpec extends PeopleAggregationSpec with VertxPostgresSpecSupport {

  import context._

  override def beforeAll(): Unit = {
    super.beforeAll()
    unsafeRun(context.transaction {
      for {
        _ <- context.run(query[Contact].delete)
        _ <- context.run(query[Address].delete)
        _ <- context.run(liftQuery(people).foreach(p => query[Contact].insertValue(p)))
        _ <- context.run(liftQuery(addresses).foreach(a => query[Address].insertValue(a)))
      } yield ()
    })
  }

  "Ex 1 map(agg(c),agg(c))" in {
    import `Ex 1 map(agg(c),agg(c))`._
    unsafeRun(context.run(get)) mustEqual expect
  }

  "Ex 2 map(agg(c),agg(c)).filter(col)" in {
    import `Ex 2 map(agg(c),agg(c)).filter(col)`._
    unsafeRun(context.run(get)) mustEqual expect
  }

  "Ex 3 groupByMap(col)(col,agg(c))" in {
    import `Ex 3 groupByMap(col)(col,agg(c))`._
    unsafeRun(context.run(get)).toSet mustEqual expect.toSet
  }

  "Ex 4 groupByMap(col)(agg(c)).filter(agg)" in {
    import `Ex 4 groupByMap(col)(agg(c)).filter(agg)`._
    unsafeRun(context.run(get)).toSet mustEqual expect.toSet
  }

  "Ex 5 map.groupByMap(col)(col,agg(c)).filter(agg)" in {
    import `Ex 5 map.groupByMap(col)(col,agg(c)).filter(agg)`._
    unsafeRun(context.run(get)).toSet mustEqual expect.toSet
  }

  "Ex 6 flatMap.groupByMap.map" in {
    import `Ex 6 flatMap.groupByMap.map`._
    unsafeRun(context.run(get)).toSet mustEqual expect.toSet
  }
}
