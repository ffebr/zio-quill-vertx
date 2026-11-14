package zio.quill.vertx

import java.time.{LocalDate, LocalDateTime}
import java.util.UUID
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers._
import scala.collection.JavaConverters._
import zio.Chunk
import zio.json.ast.Json
import zio.quill.{JsonValue, JsonbValue}
import zio.quill.context.vertx.VertxRuntime
import io.vertx.sqlclient.data.NullValue

object VertxBindParameterRegressionSpec {
  final case class Code(value: Int) extends AnyVal
  final case class Label(value: String)
  final case class RawLabel(value: String)
}

/**
 * Live PostgreSQL coverage for typed null and lifted parameter bindings on the
 * native driver.
 */
class VertxBindParameterRegressionSpec extends VertxPostgresSpecSupport with BeforeAndAfterEach {
  import context._
  import VertxBindParameterRegressionSpec._
  implicit val codeToInt: MappedEncoding[Code, Int]         = MappedEncoding(_.value)
  implicit val intToCode: MappedEncoding[Int, Code]         = MappedEncoding(Code.apply)
  implicit val labelToString: MappedEncoding[Label, String] = MappedEncoding(_.value)
  implicit val stringToLabel: MappedEncoding[String, Label] = MappedEncoding(Label.apply)
  implicit val rawLabelEncoder: Encoder[RawLabel]           =
    VertxEncoder[RawLabel] { (index, value, tuple, _) =>
      require(index == tuple.size(), s"Vert.x tuple bind index $index does not match next position ${tuple.size()}")
      tuple.addValue(value.value)
    }.withNullValue(NullValue.String)
  implicit val rawLabelDecoder: Decoder[RawLabel] =
    VertxDecoder((index, row, _) => RawLabel(row.getString(index)))

  case class BindingRow(
    id: Int,
    label: Option[String],
    intValue: Int,
    boolValue: Boolean,
    amount: BigDecimal,
    token: UUID,
    occurred: LocalDateTime,
    code: Code,
    status: Label,
    numbers: Option[List[Int]],
    json: Option[JsonbValue[Json]]
  )
  case class EnumRow(id: Int, status: String)
  private val rows     = quote(querySchema[BindingRow]("quill_vertx_binding_regression"))
  private val enumRows = quote(querySchema[EnumRow]("quill_vertx_binding_enum_rows"))

  private def sql(statement: String): Unit =
    unsafeRun(VertxRuntime.fromFuture(pool.query(statement).execute()).unit)

  private val first = BindingRow(
    1,
    Some("alpha"),
    42,
    boolValue = true,
    BigDecimal("19.875"),
    UUID.fromString("20e4c76a-7958-4fc0-a521-57811a34993d"),
    LocalDateTime.of(2024, 2, 3, 4, 5, 6, 123000000),
    Code(7),
    Label("ready"),
    Some(List(2, 5, 8)),
    Some(JsonbValue[Json](Json.Obj(Chunk("kind" -> Json.Str("first")))))
  )
  private val second = first.copy(
    id = 2,
    label = None,
    intValue = 43,
    boolValue = false,
    amount = BigDecimal("20.125"),
    token = UUID.fromString("4f7bc3b5-2e8f-4eac-931d-ed25a2adff80"),
    occurred = LocalDateTime.of(2024, 2, 4, 5, 6, 7, 456000000),
    code = Code(8),
    status = Label("queued"),
    numbers = None,
    json = None
  )

  override def beforeAll(): Unit = {
    super.beforeAll()
    sql("DROP TABLE IF EXISTS quill_vertx_binding_enum_rows")
    sql("DROP TYPE IF EXISTS quill_vertx_binding_status")
    sql("CREATE TYPE quill_vertx_binding_status AS ENUM ('queued', 'done')")
    sql("CREATE TABLE quill_vertx_binding_enum_rows (id INT PRIMARY KEY, status quill_vertx_binding_status NOT NULL)")
    sql("DROP TABLE IF EXISTS quill_vertx_binding_regression")
    sql(
      "CREATE TABLE quill_vertx_binding_regression (id INT PRIMARY KEY, label TEXT NULL, intValue INT NOT NULL, boolValue BOOLEAN NOT NULL, amount NUMERIC NOT NULL, token UUID NOT NULL, occurred TIMESTAMP NOT NULL, code INT NOT NULL, status TEXT NOT NULL, numbers INT[] NULL, json JSONB NULL)"
    )
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    sql("TRUNCATE TABLE quill_vertx_binding_regression")
    sql("TRUNCATE TABLE quill_vertx_binding_enum_rows")
    sql("INSERT INTO quill_vertx_binding_enum_rows (id, status) VALUES (1, 'queued'), (2, 'done')")
    unsafeRun(context.run(liftQuery(List(first, second)).foreach(row => rows.insertValue(row))))
  }

  override def afterAll(): Unit =
    try if (poolIsReady) {
        sql("DROP TABLE IF EXISTS quill_vertx_binding_enum_rows")
        sql("DROP TYPE IF EXISTS quill_vertx_binding_status")
        sql("DROP TABLE IF EXISTS quill_vertx_binding_regression")
      }
    finally super.afterAll()

  "native null Option lifts retain their type in isEmpty predicates" in {
    unsafeRun(context.run(rows.filter(_ => lift(None: Option[String]).isEmpty).map(_.id))).sorted shouldBe List(1, 2)
    unsafeRun(context.run(rows.filter(_ => lift(Some("present"): Option[String]).isEmpty).map(_.id))) shouldBe Nil
  }

  "native null and present Option lifts retain their type in forall predicates" in {
    unsafeRun(context.run(rows.filter(r => lift(None: Option[String]).forall(value => r.label.contains(value))).map(_.id))).sorted shouldBe List(1, 2)
    unsafeRun(context.run(rows.filter(r => lift(Some("alpha"): Option[String]).forall(value => r.label.contains(value))).map(_.id))) shouldBe List(1)
  }

  "native null Option lifts retain Int, Boolean, decimal, UUID, time, and mapped types" in {
    val probes = List(
      "Int"           -> context.run(rows.filter(_ => lift(None: Option[Int]).isEmpty).map(_.id)),
      "Boolean"       -> context.run(rows.filter(_ => lift(None: Option[Boolean]).isEmpty).map(_.id)),
      "BigDecimal"    -> context.run(rows.filter(_ => lift(None: Option[BigDecimal]).isEmpty).map(_.id)),
      "UUID"          -> context.run(rows.filter(_ => lift(None: Option[UUID]).isEmpty).map(_.id)),
      "LocalDateTime" -> context.run(rows.filter(_ => lift(None: Option[LocalDateTime]).isEmpty).map(_.id)),
      "Code"          -> context.run(rows.filter(_ => lift(None: Option[Code]).isEmpty).map(_.id)),
      "Label"         -> context.run(rows.filter(_ => lift(None: Option[Label]).isEmpty).map(_.id)),
      "RawLabel"      -> context.run(rows.filter(_ => lift(None: Option[RawLabel]).isEmpty).map(_.id))
    )
    val actual = unsafeRun(zio.ZIO.foreach(probes) { case (name, effect) => effect.map(_.sorted).either.map(name -> _) })
    actual shouldBe probes.map { case (name, _) => name -> Right(List(1, 2)) }

  }

  "present typed Option lifts remain discriminating for Int and temporal predicates" in {
    val matches = unsafeRun(for {
      intIds     <- context.run(rows.filter(r => lift(Some(42): Option[Int]).forall(value => value == r.intValue)).map(_.id))
      timeIds    <- context.run(rows.filter(r => lift(Some(first.occurred): Option[LocalDateTime]).forall(value => value == r.occurred)).map(_.id))
      decimalIds <- context.run(rows.filter(r => lift(Some(BigDecimal("19.875")): Option[BigDecimal]).forall(value => value == r.amount)).map(_.id))
    } yield (intIds, timeIds, decimalIds))
    matches shouldBe ((List(1), List(1), List(1)))
  }

  "numeric arrays round-trip as PostgreSQL Numeric values and bind optional nulls" in {
    val values    = List(BigDecimal("1.25"), BigDecimal("2.500"), BigDecimal("-9.125"))
    val roundTrip = unsafeRun(context.run(quote(rows.take(1).map(_ => lift(values)))))
    roundTrip shouldBe List(values)
    unsafeRun(context.run(rows.filter(_ => lift(None: Option[List[BigDecimal]]).isEmpty).map(_.id))).sorted shouldBe List(1, 2)
  }

  "lifted String, Int, Boolean, and LocalDate arrays project and round-trip" in {
    val strings  = List("north", "south")
    val ints     = List(1, 3, 5)
    val booleans = List(true, false, true)
    val dates    = List(LocalDate.of(2024, 2, 3), LocalDate.of(2024, 2, 4))
    val actual   = unsafeRun(for {
      stringValues  <- context.run(quote(rows.take(1).map(_ => lift(strings))))
      intValues     <- context.run(quote(rows.take(1).map(_ => lift(ints))))
      booleanValues <- context.run(quote(rows.take(1).map(_ => lift(booleans))))
      dateValues    <- context.run(quote(rows.take(1).map(_ => lift(dates))))
    } yield (stringValues, intValues, booleanValues, dateValues))
    actual shouldBe ((List(strings), List(ints), List(booleans), List(dates)))
  }

  "None optional String, Int, Boolean, and LocalDate arrays keep typed NULL descriptors" in {
    val results = unsafeRun(for {
      strings  <- context.run(rows.filter(_ => lift(None: Option[List[String]]).isEmpty).map(_.id))
      ints     <- context.run(rows.filter(_ => lift(None: Option[List[Int]]).isEmpty).map(_.id))
      booleans <- context.run(rows.filter(_ => lift(None: Option[List[Boolean]]).isEmpty).map(_.id))
      dates    <- context.run(rows.filter(_ => lift(None: Option[List[LocalDate]]).isEmpty).map(_.id))
    } yield List(strings, ints, booleans, dates))
    results.map(_.sorted) shouldBe List.fill(4)(List(1, 2))
  }

  "stream queries bind optional None predicates with cursor fetching" in {
    val empty = collect(context.stream(rows.filter(_ => lift(None: Option[String]).isEmpty).map(_.id), 1))
    val all   = collect(context.stream(
      rows.filter(r => lift(None: Option[String]).forall(value => r.label.contains(value))).map(_.id),
      1
    ))
    empty.sorted shouldBe List(1, 2)
    all.sorted shouldBe List(1, 2)
  }

  "decimal None and Some parameters bind through cursor-fetched streams" in {
    val missing = collect(context.stream(rows.filter(_ => lift(None: Option[BigDecimal]).isEmpty).map(_.id), 1))
    val present = collect(context.stream(
      rows.filter(r => lift(Some(BigDecimal("19.875")): Option[BigDecimal]).forall(value => value == r.amount)).map(_.id),
      1
    ))
    missing.sorted shouldBe List(1, 2)
    present shouldBe List(1)
  }

  "PostgreSQL enum values compare through run and streaming with a typed NULL sibling" in {
    val ordinary          = unsafeRun(context.run(enumRows.filter(_.status == lift("queued")).map(_.id)))
    val withNullPredicate = unsafeRun(context.run(enumRows.filter(r =>
      r.status == lift("queued") && lift(None: Option[String]).isEmpty
    ).map(_.id)))
    val streamed = collect(context.stream(
      enumRows.filter(r =>
        r.status == lift("queued") && lift(None: Option[String]).isEmpty
      ).map(_.id),
      1
    ))
    ordinary shouldBe List(1)
    withNullPredicate shouldBe List(1)
    streamed shouldBe List(1)
  }

  "standard typed scalar lifts bind String, Int, Boolean, BigDecimal, UUID, and time values" in {
    val decimal   = BigDecimal("19.875")
    val token     = first.token
    val timestamp = first.occurred
    val found     = unsafeRun(context.run(rows.filter(r =>
      r.label == lift(Some("alpha"): Option[String]) && r.intValue == lift(42) && r.boolValue == lift(true) &&
        r.amount == lift(decimal) && r.token == lift(token) && r.occurred == lift(timestamp)
    ).map(_.id)))
    found shouldBe List(1)
  }

  "mapped value-class and case-class values round-trip and bind in predicates" in {
    unsafeRun(context.run(rows.filter(r => r.code == lift(Code(7)) && r.status == lift(Label("ready"))).map(_.id))) shouldBe List(1)
    unsafeRun(context.run(rows.filter(r => r.code == lift(Code(8))).map(_.status))) shouldBe List(Label("queued"))
  }

  "typed null lifts work for optional PostgreSQL array and JSONB parameters" in {
    val noArray = unsafeRun(context.run(rows.filter(_ => lift(None: Option[List[Int]]).isEmpty).map(_.id)))
    val noJson  = unsafeRun(context.run(rows.filter(_ => lift(None: Option[JsonbValue[Json]]).isEmpty).map(_.id)))
    noArray.sorted shouldBe List(1, 2)
    noJson.sorted shouldBe List(1, 2)
  }

  "JSON wrapper parameters distinguish SQL NULL from JSON values" in {
    val objectValue                                            = Json.Obj(Chunk("kind" -> Json.Str("object")))
    val cases: List[(Option[JsonValue[Json]], Option[String])] = List(
      None                                    -> None,
      Some(JsonValue[Json](objectValue))      -> Some("object"),
      Some(JsonValue[Json](Json.Str("text"))) -> Some("string"),
      Some(JsonValue[Json](Json.Null))        -> Some("null")
    )
    cases.foreach { case (value, expected) =>
      val query = quote(rows.take(1).map(_ => infix"json_typeof(${lift(value)})".as[Option[String]]))
      unsafeRun(context.run(query)) shouldBe List(expected)
    }
  }

  "JSONB wrapper parameters distinguish SQL NULL from JSONB values" in {
    val objectValue                                             = Json.Obj(Chunk("kind" -> Json.Str("object")))
    val cases: List[(Option[JsonbValue[Json]], Option[String])] = List(
      None                                     -> None,
      Some(JsonbValue[Json](objectValue))      -> Some("object"),
      Some(JsonbValue[Json](Json.Str("text"))) -> Some("string"),
      Some(JsonbValue[Json](Json.Null))        -> Some("null")
    )
    cases.foreach { case (value, expected) =>
      val query = quote(rows.take(1).map(_ => infix"jsonb_typeof(${lift(value)})".as[Option[String]]))
      unsafeRun(context.run(query)) shouldBe List(expected)
    }
  }

  "JSONB column comparison binds None beside a typed String None parameter" in {
    val noneMatches = unsafeRun(context.run(rows.filter(r =>
      lift(None: Option[JsonbValue[Json]]).forall(value => r.json.contains(value)) &&
        lift(None: Option[String]).isEmpty
    ).map(_.id)))
    noneMatches.sorted shouldBe List(1, 2)

    val objectValue    = first.json.get
    val presentMatches = unsafeRun(context.run(rows.filter(r =>
      lift(Some(objectValue): Option[JsonbValue[Json]]).forall(value => r.json.contains(value)) &&
        lift(None: Option[String]).isEmpty
    ).map(_.id)))
    presentMatches shouldBe List(1)
  }

  "batch inserts preserve typed optional bindings across rows" in {
    unsafeRun(context.run(rows.delete))
    unsafeRun(context.run(liftQuery(List(first, second)).foreach(row => rows.insertValue(row)))) shouldBe List(1L, 1L)
    unsafeRun(context.run(rows.sortBy(_.id))).map(r => (r.id, r.label, r.numbers, r.json)) shouldBe
      List((1, first.label, first.numbers, first.json), (2, second.label, second.numbers, second.json))
  }

  "prepared queries execute typed null and ordinary scalar parameters repeatedly" in {
    val effect = for {
      empty     <- context.prepare(rows.filter(_ => lift(None: Option[String]).isEmpty).map(_.id))
      all       <- empty.execute.map(_.iterator().asScala.map(_.getInteger(0)).toList.sorted)
      byValue   <- context.prepare(rows.filter(r => r.amount == lift(BigDecimal("19.875")) && r.token == lift(first.token)).map(_.id))
      firstRun  <- byValue.execute.map(_.iterator().asScala.map(_.getInteger(0)).toList)
      secondRun <- byValue.execute.map(_.iterator().asScala.map(_.getInteger(0)).toList)
    } yield (all, firstRun, secondRun)
    unsafeRun(effect) shouldBe ((List(1, 2), List(1), List(1)))
  }

  "prepared batch actions preserve typed null and mapped values" in {
    unsafeRun(context.run(rows.delete))
    val values = List(first.copy(id = 10, label = None), second.copy(id = 11, status = Label("again")))
    val effect = context.transaction {
      for {
        handles <- context.prepare(liftQuery(values).foreach(value => rows.insertValue(value)))
        counts  <- zio.ZIO.foreach(handles)(_.execute.map(_.rowCount()))
        actual  <- context.run(rows.sortBy(_.id).map(r => (r.id, r.label, r.status)))
      } yield (counts, actual)
    }
    unsafeRun(effect) shouldBe ((List(1, 1), List((10, None, Label("ready")), (11, None, Label("again")))))
  }
}
