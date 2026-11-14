package zio.quill.context.vertx

import io.vertx.sqlclient.Tuple
import io.vertx.sqlclient.data.NullValue
import zio.quill.context.sql.SqlContext

import java.time.{Instant, LocalDate, LocalDateTime, LocalTime, OffsetDateTime, OffsetTime, ZoneId}
import java.util.{Date, UUID}

trait VertxBaseEncoders extends VertxRowContext {
  this: SqlContext[_, _] =>

  type Encoder[T] = VertxEncoder[T]

  final case class VertxEncoder[T](encoder: BaseEncoder[T]) extends BaseEncoder[T] {
    override def apply(index: Index, value: T, row: PrepareRow, session: Session): PrepareRow =
      encoder(index, value, row, session)

    /**
     * Describe the native value produced by a custom encoder, including SQL
     * NULL.
     */
    def withNullValue(value: NullValue): VertxEncoder[T] = {
      require(value != null, "A typed SQL NULL descriptor is required")
      withNullParameter(value)
    }

    private[vertx] def withNullParameter(value: AnyRef): VertxEncoder[T] =
      VertxEncoder(new NullableBaseEncoder(encoder, value))

    private[vertx] def nullValue: AnyRef = encoder match {
      case nullable: NullableBaseEncoder[_] => nullable.nullValue
      case _                                => null
    }
  }

  private final class NullableBaseEncoder[T](underlying: BaseEncoder[T], val nullValue: AnyRef) extends BaseEncoder[T] {
    override def apply(index: Index, value: T, row: PrepareRow, session: Session): PrepareRow =
      if (value == null) append(index, nullValue, row)
      else underlying(index, value, row, session)
  }

  private def inheritNullValue[T](encoder: Encoder[T], value: AnyRef): Encoder[T] =
    if (value == null) encoder else encoder.withNullParameter(value)

  protected final def append(index: Index, value: Any, row: Tuple): Tuple = {
    require(index == row.size(), s"Vert.x tuple bind index $index does not match next position ${row.size()}")
    row.addValue(value.asInstanceOf[AnyRef])
  }

  protected def encoderOf[T](f: (Index, T, Tuple) => Tuple): Encoder[T] =
    VertxEncoder((index, value, row, _) => f(index, value, row))

  implicit def mappedEncoder[I, O](implicit mapped: MappedEncoding[I, O], e: Encoder[O]): Encoder[I] =
    inheritNullValue(VertxEncoder(mappedBaseEncoder(mapped, e.encoder)), e.nullValue)

  implicit def optionEncoder[T](implicit e: Encoder[T]): Encoder[Option[T]] =
    inheritNullValue(
      VertxEncoder((index, value, row, session) =>
        value match {
          case Some(v) => e(index, v, row, session)
          case None    => append(index, e.nullValue, row)
        }
      ),
      e.nullValue
    )

  implicit val stringEncoder: Encoder[String]         = encoderOf[String](append).withNullValue(NullValue.String)
  implicit val bigDecimalEncoder: Encoder[BigDecimal] = encoderOf[BigDecimal]((i, v, r) => append(i, v.bigDecimal, r)).withNullValue(NullValue.of(classOf[java.math.BigDecimal]))
  implicit val booleanEncoder: Encoder[Boolean]       = encoderOf[Boolean](append).withNullValue(NullValue.Boolean)
  implicit val byteEncoder: Encoder[Byte]             = encoderOf[Byte]((i, v, r) => append(i, Short.box(v.toShort), r)).withNullValue(NullValue.Short)
  implicit val shortEncoder: Encoder[Short]           = encoderOf[Short]((i, v, r) => append(i, Short.box(v), r)).withNullValue(NullValue.Short)
  implicit val intEncoder: Encoder[Int]               = encoderOf[Int]((i, v, r) => append(i, Int.box(v), r)).withNullValue(NullValue.Integer)
  implicit val longEncoder: Encoder[Long]             = encoderOf[Long]((i, v, r) => append(i, Long.box(v), r)).withNullValue(NullValue.Long)
  implicit val floatEncoder: Encoder[Float]           = encoderOf[Float]((i, v, r) => append(i, Float.box(v), r)).withNullValue(NullValue.Float)
  implicit val doubleEncoder: Encoder[Double]         = encoderOf[Double]((i, v, r) => append(i, Double.box(v), r)).withNullValue(NullValue.Double)
  implicit val byteArrayEncoder: Encoder[Array[Byte]] = encoderOf[Array[Byte]]((i, v, r) =>
    append(i, if (v == null) null else io.vertx.core.buffer.Buffer.buffer(v), r)
  ).withNullValue(NullValue.Buffer)

  implicit val dateEncoder: Encoder[Date] = encoderOf[Date]((i, v, r) =>
    append(i, LocalDateTime.ofInstant(Instant.ofEpochMilli(v.getTime), ZoneId.systemDefault()), r)
  ).withNullValue(NullValue.LocalDateTime)
  implicit val sqlDateEncoder: Encoder[java.sql.Date]                 = encoderOf[java.sql.Date]((i, v, r) => append(i, v.toLocalDate, r)).withNullValue(NullValue.LocalDate)
  implicit val sqlTimeEncoder: Encoder[java.sql.Time]                 = encoderOf[java.sql.Time]((i, v, r) => append(i, v.toLocalTime, r)).withNullValue(NullValue.LocalTime)
  implicit val sqlTimestampEncoder: Encoder[java.sql.Timestamp]       = encoderOf[java.sql.Timestamp]((i, v, r) => append(i, v.toLocalDateTime, r)).withNullValue(NullValue.LocalDateTime)
  implicit val localDateEncoder: Encoder[LocalDate]                   = encoderOf[LocalDate](append).withNullValue(NullValue.LocalDate)
  implicit val localTimeEncoder: Encoder[LocalTime]                   = encoderOf[LocalTime](append).withNullValue(NullValue.LocalTime)
  implicit val localDateTimeEncoder: Encoder[LocalDateTime]           = encoderOf[LocalDateTime](append).withNullValue(NullValue.LocalDateTime)
  implicit val instantEncoder: Encoder[Instant]                       = encoderOf[Instant]((i, v, r) => append(i, v.atOffset(java.time.ZoneOffset.UTC), r)).withNullValue(NullValue.OffsetDateTime)
  implicit val offsetDateTimeEncoder: Encoder[OffsetDateTime]         = encoderOf[OffsetDateTime]((i, v, r) => append(i, v, r)).withNullValue(NullValue.OffsetDateTime)
  implicit val offsetTimeEncoder: Encoder[OffsetTime]                 = encoderOf[OffsetTime]((i, v, r) => append(i, v, r)).withNullValue(NullValue.OffsetTime)
  implicit val zonedDateTimeEncoder: Encoder[java.time.ZonedDateTime] =
    encoderOf[java.time.ZonedDateTime]((i, v, r) => append(i, v.toOffsetDateTime, r)).withNullValue(NullValue.OffsetDateTime)
  implicit val uuidEncoder: Encoder[UUID] = encoderOf[UUID](append).withNullValue(NullValue.UUID)

}
