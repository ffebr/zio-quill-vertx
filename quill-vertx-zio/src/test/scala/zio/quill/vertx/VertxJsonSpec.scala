package zio.quill.vertx

import org.scalatest.matchers.should.Matchers._
import zio.Chunk
import zio.json.ast.Json
import zio.json.{DeriveJsonDecoder, DeriveJsonEncoder, JsonDecoder, JsonEncoder}
import zio.quill.{JsonValue, JsonbValue}
import zio.quill.context.vertx.VertxRuntime

class VertxJsonSpec extends VertxPostgresSpecSupport {
  import context._

  case class Person(name: String, age: Int)
  case class NativeJsonRow(
    id: Int,
    jsonValue: JsonValue[Person],
    jsonbValue: JsonbValue[Person],
    jsonAst: JsonValue[Json],
    jsonbAst: JsonbValue[Json]
  )
  case class NativeJsonNullRow(id: Int, json: Option[JsonValue[Json]], jsonb: Option[JsonbValue[Json]])

  implicit val personEncoder: JsonEncoder[Person] = DeriveJsonEncoder.gen[Person]
  implicit val personDecoder: JsonDecoder[Person] = DeriveJsonDecoder.gen[Person]

  val jsonRows     = quote(querySchema[NativeJsonRow]("quill_vertx_json_rows"))
  val jsonNullRows = quote(querySchema[NativeJsonNullRow]("quill_vertx_json_null_rows"))

  private def sql(statement: String): Unit =
    unsafeRun(VertxRuntime.fromFuture(pool.query(statement).execute()).unit)

  override def beforeAll(): Unit = {
    super.beforeAll()
    sql("DROP TABLE IF EXISTS quill_vertx_json_rows")
    sql("DROP TABLE IF EXISTS quill_vertx_json_null_rows")
    sql("CREATE TABLE quill_vertx_json_rows (id INT PRIMARY KEY, jsonValue JSON NOT NULL, jsonbValue JSONB NOT NULL, jsonAst JSON NOT NULL, jsonbAst JSONB NOT NULL)")
    sql("CREATE TABLE quill_vertx_json_null_rows (id INT PRIMARY KEY, json JSON NULL, jsonb JSONB NULL)")
  }

  override def afterAll(): Unit =
    try if (poolIsReady) {
        sql("DROP TABLE IF EXISTS quill_vertx_json_rows")
        sql("DROP TABLE IF EXISTS quill_vertx_json_null_rows")
      }
    finally super.afterAll()

  "native Vert.x JSON and JSONB codecs" - {
    "round-trip entity wrappers and zio-json AST values" in {
      val ast   = Json.Obj(Chunk("name" -> Json.Str("Ada"), "values" -> Json.Arr(Chunk(Json.Num(1), Json.Bool(true)))))
      val value = NativeJsonRow(
        1,
        JsonValue(Person("Ada", 37)),
        JsonbValue(Person("Grace", 42)),
        JsonValue(ast),
        JsonbValue(Json.Arr(Chunk(Json.Str("jsonb"), Json.Num(9))))
      )
      val primitive = value.copy(id = 2, jsonAst = JsonValue(Json.Str("primitive")), jsonbAst = JsonbValue(Json.Num(9)))
      unsafeRun(context.run(liftQuery(List(value, primitive)).foreach(row => jsonRows.insertValue(row)))) shouldBe List(1L, 1L)
      unsafeRun(context.run(jsonRows.sortBy(_.id))) shouldBe List(value, primitive)
    }

    "keep JSON null distinct from SQL NULL for optional wrappers" in {
      val jsonNull = NativeJsonNullRow(1, Some(JsonValue(Json.Null)), Some(JsonbValue(Json.Null)))
      val sqlNull  = NativeJsonNullRow(2, None, None)
      unsafeRun(context.run(liftQuery(List(jsonNull, sqlNull)).foreach(row => jsonNullRows.insertValue(row)))) shouldBe List(1L, 1L)
      unsafeRun(context.run(jsonNullRows.sortBy(_.id))) shouldBe List(jsonNull, sqlNull)
    }
  }
}
