package zio.quill.context.vertx

import io.vertx.sqlclient.Tuple
import zio.quill.context.sql.SqlContext

import java.time.{Instant, LocalDate, LocalDateTime, LocalTime, OffsetDateTime, OffsetTime, ZoneId}
import java.util.{Date, UUID}

trait VertxBaseEncoders extends VertxRowContext {
  this: SqlContext[_, _] =>

  type Encoder[T] = VertxEncoder[T]

  final case class VertxEncoder[T](encoder: BaseEncoder[T]) extends BaseEncoder[T] {
    override def apply(index: Index, value: T, row: PrepareRow, session: Session): PrepareRow =
      encoder(index, value, row, session)
  }

  protected final def append(index: Index, value: Any, row: Tuple): Tuple = {
    require(index == row.size(), s"Vert.x tuple bind index $index does not match next position ${row.size()}")
    row.addValue(value.asInstanceOf[AnyRef])
  }

  protected def encoderOf[T](f: (Index, T, Tuple) => Tuple): Encoder[T] =
    VertxEncoder((index, value, row, _) => f(index, value, row))

  implicit def mappedEncoder[I, O](implicit mapped: MappedEncoding[I, O], e: Encoder[O]): Encoder[I] =
    VertxEncoder(mappedBaseEncoder(mapped, e.encoder))

  implicit def optionEncoder[T](implicit e: Encoder[T]): Encoder[Option[T]] =
    VertxEncoder((index, value, row, session) =>
      value match {
        case Some(v) => e(index, v, row, session)
        case None    => append(index, null, row)
      }
    )

  implicit val stringEncoder: Encoder[String]         = encoderOf(append)
  implicit val bigDecimalEncoder: Encoder[BigDecimal] = encoderOf((i, v, r) => append(i, v.bigDecimal, r))
  implicit val booleanEncoder: Encoder[Boolean]       = encoderOf(append)
  implicit val byteEncoder: Encoder[Byte]             = encoderOf((i, v, r) => append(i, Short.box(v.toShort), r))
  implicit val shortEncoder: Encoder[Short]           = encoderOf((i, v, r) => append(i, Short.box(v), r))
  implicit val intEncoder: Encoder[Int]               = encoderOf((i, v, r) => append(i, Int.box(v), r))
  implicit val longEncoder: Encoder[Long]             = encoderOf((i, v, r) => append(i, Long.box(v), r))
  implicit val floatEncoder: Encoder[Float]           = encoderOf((i, v, r) => append(i, Float.box(v), r))
  implicit val doubleEncoder: Encoder[Double]         = encoderOf((i, v, r) => append(i, Double.box(v), r))
  implicit val byteArrayEncoder: Encoder[Array[Byte]] = encoderOf((i, v, r) =>
    append(i, if (v == null) null else io.vertx.core.buffer.Buffer.buffer(v), r)
  )

  implicit val dateEncoder: Encoder[Date] = encoderOf((i, v, r) =>
    append(i, LocalDateTime.ofInstant(Instant.ofEpochMilli(v.getTime), ZoneId.systemDefault()), r)
  )
  implicit val sqlDateEncoder: Encoder[java.sql.Date]                 = encoderOf((i, v, r) => append(i, if (v == null) null else v.toLocalDate, r))
  implicit val sqlTimeEncoder: Encoder[java.sql.Time]                 = encoderOf((i, v, r) => append(i, if (v == null) null else v.toLocalTime, r))
  implicit val sqlTimestampEncoder: Encoder[java.sql.Timestamp]       = encoderOf((i, v, r) => append(i, if (v == null) null else v.toLocalDateTime, r))
  implicit val localDateEncoder: Encoder[LocalDate]                   = encoderOf(append)
  implicit val localTimeEncoder: Encoder[LocalTime]                   = encoderOf(append)
  implicit val localDateTimeEncoder: Encoder[LocalDateTime]           = encoderOf(append)
  implicit val instantEncoder: Encoder[Instant]                       = encoderOf((i, v, r) => append(i, v.atOffset(java.time.ZoneOffset.UTC), r))
  implicit val offsetDateTimeEncoder: Encoder[OffsetDateTime]         = encoderOf((i, v, r) => append(i, v, r))
  implicit val offsetTimeEncoder: Encoder[OffsetTime]                 = encoderOf((i, v, r) => append(i, v, r))
  implicit val zonedDateTimeEncoder: Encoder[java.time.ZonedDateTime] =
    encoderOf((i, v, r) => append(i, v.toOffsetDateTime, r))
  implicit val uuidEncoder: Encoder[UUID] = encoderOf(append)

}
