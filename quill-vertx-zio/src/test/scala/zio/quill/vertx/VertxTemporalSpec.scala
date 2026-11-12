package zio.quill.vertx

import java.time._
import java.util.{Date, UUID}
import org.scalatest.matchers.should.Matchers._
import zio.quill.Query
import zio.quill.context.vertx.VertxRuntime

class VertxTemporalSpec extends VertxPostgresSpecSupport {
  import context._

  case class TemporalValues(
    id: Int,
    token: UUID,
    amount: BigDecimal,
    dateValue: LocalDate,
    timeValue: LocalTime,
    localDateTimeValue: LocalDateTime,
    timestampValue: java.sql.Timestamp,
    instantValue: Instant,
    offsetDateTimeValue: OffsetDateTime,
    offsetTimeValue: OffsetTime,
    zonedDateTimeValue: ZonedDateTime
  )
  case class TimestampTzValues(dateValue: Date, timestampValue: java.sql.Timestamp)

  val values      = quote(querySchema[TemporalValues]("quill_vertx_temporal_values"))
  val timestampTz = quote(
    infix"SELECT '2024-02-03 04:05:06.123456+05:30'::timestamptz, '2024-02-03 04:05:06.123456+05:30'::timestamptz".as[Query[TimestampTzValues]]
  )

  private def sql(statement: String): Unit =
    unsafeRun(VertxRuntime.fromFuture(pool.query(statement).execute()).unit)

  override def beforeAll(): Unit = {
    super.beforeAll()
    sql("DROP TABLE IF EXISTS quill_vertx_temporal_values")
    sql(
      "CREATE TABLE quill_vertx_temporal_values (id INT PRIMARY KEY, token UUID NOT NULL, amount NUMERIC NOT NULL, dateValue DATE NOT NULL, timeValue TIME NOT NULL, localDateTimeValue TIMESTAMP NOT NULL, timestampValue TIMESTAMP NOT NULL, instantValue TIMESTAMPTZ NOT NULL, offsetDateTimeValue TIMESTAMPTZ NOT NULL, offsetTimeValue TIMETZ NOT NULL, zonedDateTimeValue TIMESTAMPTZ NOT NULL)"
    )
  }

  override def afterAll(): Unit =
    try if (poolIsReady) sql("DROP TABLE IF EXISTS quill_vertx_temporal_values")
    finally super.afterAll()

  "native PostgreSQL temporal and numeric codecs" - {
    "round-trip values using matching PostgreSQL column types" in {
      val instant        = Instant.parse("2024-01-02T03:04:05.123456Z")
      val offsetDateTime = OffsetDateTime.ofInstant(instant, ZoneOffset.ofHoursMinutes(5, 30))
      val offsetTime     = OffsetTime.of(12, 34, 56, 123456000, ZoneOffset.ofHoursMinutes(-4, 0))
      val zonedDateTime  = ZonedDateTime.of(2024, 3, 10, 1, 30, 0, 123456000, ZoneId.of("America/New_York"))
      val expected       = TemporalValues(
        id = 1,
        token = UUID.fromString("e0e82ded-7cc0-4ae3-b733-0cc6f9c85b51"),
        amount = BigDecimal("1234567890.123456"),
        dateValue = LocalDate.of(2024, 2, 3),
        timeValue = LocalTime.of(4, 5, 6, 123456000),
        localDateTimeValue = LocalDateTime.of(2024, 2, 3, 4, 5, 6, 123456000),
        timestampValue = java.sql.Timestamp.valueOf("2024-02-03 04:05:06.123456"),
        instantValue = instant,
        offsetDateTimeValue = offsetDateTime,
        offsetTimeValue = offsetTime,
        zonedDateTimeValue = zonedDateTime
      )

      unsafeRun(context.run(values.insertValue(lift(expected)))) shouldBe 1L
      val actual = unsafeRun(context.run(values)).head
      actual.id shouldBe expected.id
      actual.token shouldBe expected.token
      actual.amount shouldBe expected.amount
      actual.dateValue shouldBe expected.dateValue
      actual.timeValue shouldBe expected.timeValue
      actual.localDateTimeValue shouldBe expected.localDateTimeValue
      actual.timestampValue shouldBe expected.timestampValue
      actual.instantValue shouldBe expected.instantValue
      actual.offsetDateTimeValue.toInstant shouldBe expected.offsetDateTimeValue.toInstant
      actual.offsetTimeValue shouldBe expected.offsetTimeValue
      actual.zonedDateTimeValue.toInstant shouldBe expected.zonedDateTimeValue.toInstant
    }

    "decode timestamptz into util Date and sql Timestamp at the exact epoch" in {
      val expected = Instant.parse("2024-02-02T22:35:06.123456Z")
      val actual   = unsafeRun(context.run(timestampTz)).head
      actual.dateValue shouldBe Date.from(expected)
      actual.timestampValue shouldBe java.sql.Timestamp.from(expected)
    }
  }
}
