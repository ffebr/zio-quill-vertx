package zio.quill.vertx.shared.read

import zio.quill.vertx.VertxPostgresSpecSupport

import zio.quill.context.sql.ProductSpec

class ProductZioVertxSpec extends ProductSpec with VertxPostgresSpecSupport {

  import context._

  override def beforeAll(): Unit = {
    super.beforeAll()
    unsafeRun(context.run(quote(query[Product].delete)))
  }

  "Product" - {
    "Insert multiple products" in {
      val inserted = unsafeRun(context.run(liftQuery(productEntries).foreach(e => productInsert(e))))
      val product  = unsafeRun(context.run(productById(lift(inserted(2))))).head
      product.description mustEqual productEntries(2).description
      product.id mustEqual inserted(2)
    }

    "Single insert product" in {
      val inserted = unsafeRun(context.run(productSingleInsert))
      val product  = unsafeRun(context.run(productById(lift(inserted)))).head
      product.description mustEqual "Window"
      product.id mustEqual inserted
    }

    case class Foo(id: Long, description: String, sku: Long)

    "Single insert with inlined free variable" in {
      val prd      = Product(0L, "test1", 1L)
      val inserted = unsafeRun(context.run {
        product.insert(_.sku -> lift(prd.sku), _.description -> lift(prd.description)).returning(r => r)
      })
      val returnedProduct = unsafeRun(context.run(productById(lift(inserted.id)))).head
      returnedProduct.description mustEqual "test1"
      returnedProduct.sku mustEqual 1L
      returnedProduct mustEqual inserted
    }

    "Single insert with free variable and explicit quotation" in {
      val prd = Product(0L, "test2", 2L)
      val q1  = quote {
        product.insert(_.sku -> lift(prd.sku), _.description -> lift(prd.description)).returning(_.id)
      }
      val inserted        = unsafeRun(context.run(q1))
      val returnedProduct = unsafeRun(context.run(productById(lift(inserted)))).head
      returnedProduct.description mustEqual "test2"
      returnedProduct.sku mustEqual 2L
      returnedProduct.id mustEqual inserted
    }

    "Single product insert with a method quotation" in {
      val prd             = Product(0L, "test3", 3L)
      val inserted        = unsafeRun(context.run(productInsert(lift(prd))))
      val returnedProduct = unsafeRun(context.run(productById(lift(inserted)))).head
      returnedProduct.description mustEqual "test3"
      returnedProduct.sku mustEqual 3L
      returnedProduct.id mustEqual inserted
    }

    "supports casts from string to number" - {
      "toInt" in {
        case class Product(id: Long, description: String, sku: Int)
        val queried = unsafeRun(context.run {
          query[Product].filter(_.sku == lift("1004").toInt)
        }).head
        queried.sku mustEqual 1004L
      }
      "toLong" in {
        val queried = unsafeRun(context.run {
          query[Product].filter(_.sku == lift("1004").toLong)
        }).head
        queried.sku mustEqual 1004L
      }
    }
  }
}
