package zio.quill.vertx

import org.scalatest.matchers.should.Matchers._
import zio.quill.Query
import zio.quill.context.vertx.VertxRuntime

class VertxCodecNullSpec extends VertxPostgresSpecSupport {
  import context._

  case class NullPrimitives(booleanValue: Boolean, byteValue: Byte, shortValue: Short, intValue: Int, longValue: Long, floatValue: Float, doubleValue: Double)
  case class NullReferences(
    textValue: String,
    decimalValue: BigDecimal,
    bytesValue: Array[Byte],
    dateValue: java.sql.Date,
    timeValue: java.sql.Time,
    timestampValue: java.sql.Timestamp
  )
  case class NullBindChecks(
    bytesAreNull: Boolean,
    dateIsNull: Boolean,
    timeIsNull: Boolean,
    timestampIsNull: Boolean,
    bytesArePresent: Boolean,
    dateIsPresent: Boolean,
    timeIsPresent: Boolean,
    timestampIsPresent: Boolean
  )
  case class UtilDateSubtypeChecks(sqlDateMatchesMillis: Boolean, sqlTimeMatchesMillis: Boolean, timestampMatchesMillis: Boolean)

  val primitiveNulls = quote(
    infix"SELECT NULL::boolean, NULL::smallint, NULL::smallint, NULL::integer, NULL::bigint, NULL::real, NULL::double precision".as[Query[NullPrimitives]]
  )
  val referenceNulls = quote(
    infix"SELECT NULL::text, NULL::numeric, NULL::bytea, NULL::date, NULL::time, NULL::timestamp".as[Query[NullReferences]]
  )
  val arrayNull = quote(infix"SELECT NULL::integer[]".as[Query[List[Int]]])

  val nullByteArray: Array[Byte]           = null
  val nullSqlDate: java.sql.Date           = null
  val nullSqlTime: java.sql.Time           = null
  val nullSqlTimestamp: java.sql.Timestamp = null
  val sqlDateAsUtilDate: java.util.Date    = java.sql.Date.valueOf("2024-02-03")
  val sqlTimeAsUtilDate: java.util.Date    = java.sql.Time.valueOf("04:05:06")
  val timestampAsUtilDate: java.util.Date  = java.sql.Timestamp.valueOf("2024-02-03 04:05:06.123456789")
  val sqlDateMillisLocal                   = new java.sql.Timestamp(sqlDateAsUtilDate.getTime).toLocalDateTime
  val sqlTimeMillisLocal                   = new java.sql.Timestamp(sqlTimeAsUtilDate.getTime).toLocalDateTime
  val timestampMillisLocal                 = new java.sql.Timestamp(timestampAsUtilDate.getTime).toLocalDateTime
  val bindNulls                            = quote(
    infix"SELECT (${lift(nullByteArray)}::bytea IS NULL), (${lift(nullSqlDate)}::date IS NULL), (${lift(nullSqlTime)}::time IS NULL), (${lift(nullSqlTimestamp)}::timestamp IS NULL), (${lift(Array[Byte](1, 2))}::bytea IS NOT NULL), (${lift(java.sql.Date.valueOf("2024-02-03"))}::date IS NOT NULL), (${lift(java.sql.Time.valueOf("04:05:06"))}::time IS NOT NULL), (${lift(java.sql.Timestamp.valueOf("2024-02-03 04:05:06"))}::timestamp IS NOT NULL)".as[
      Query[NullBindChecks]
    ]
  )
  val bindUtilDateSubtypes = quote(
    infix"SELECT (${lift(sqlDateAsUtilDate)}::timestamp = ${lift(sqlDateMillisLocal)}::timestamp), (${lift(sqlTimeAsUtilDate)}::timestamp = ${lift(sqlTimeMillisLocal)}::timestamp), (${lift(timestampAsUtilDate)}::timestamp = ${lift(timestampMillisLocal)}::timestamp)".as[
      Query[UtilDateSubtypeChecks]
    ]
  )

  "native scalar decoders preserve JDBC null behavior" in {
    unsafeRun(context.run(primitiveNulls)) shouldBe List(NullPrimitives(false, 0, 0, 0, 0L, 0.0f, 0.0d))

    val references = unsafeRun(context.run(referenceNulls)).head
    references.textValue shouldBe null
    references.decimalValue shouldBe null
    references.bytesValue shouldBe null
    references.dateValue shouldBe null
    references.timeValue shouldBe null
    references.timestampValue shouldBe null

    unsafeRun(context.run(arrayNull)) shouldBe List(Nil)
  }

  "native scalar encoders bind typed nulls as SQL NULL and keep the connection reusable" in {
    unsafeRun(context.run(bindNulls)) shouldBe List(
      NullBindChecks(true, true, true, true, true, true, true, true)
    )

    unsafeRun(context.run(quote(infix"SELECT 1".as[Query[Int]]))) shouldBe List(1)
  }

  "native util Date encoding matches JDBC millisecond semantics for SQL date subtypes" in {
    unsafeRun(context.run(bindUtilDateSubtypes)) shouldBe List(UtilDateSubtypeChecks(true, true, true))
  }
}
