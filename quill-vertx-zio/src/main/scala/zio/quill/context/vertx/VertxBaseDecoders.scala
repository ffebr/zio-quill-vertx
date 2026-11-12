package zio.quill.context.vertx

import io.vertx.sqlclient.Row
import io.vertx.sqlclient.data.Numeric
import zio.quill.context.sql.SqlContext

import java.math.{BigDecimal => JavaBigDecimal}
import java.time.{Instant, LocalDate, LocalDateTime, LocalTime, OffsetDateTime, OffsetTime, ZoneId}
import java.util.{Date, UUID}

trait VertxBaseDecoders extends VertxRowContext {
  this: SqlContext[_, _] =>

  type Decoder[T] = VertxDecoder[T]

  final case class VertxDecoder[T](decoder: BaseDecoder[T]) extends BaseDecoder[T] {
    override def apply(index: Index, row: ResultRow, session: Session): T = decoder(index, row, session)
  }

  protected def decoderOf[T](f: (Int, Row) => T): Decoder[T] =
    VertxDecoder((index, row, _) => f(index, row))

  implicit def mappedDecoder[I, O](implicit mapped: MappedEncoding[I, O], d: Decoder[I]): Decoder[O] =
    VertxDecoder(mappedBaseDecoder(mapped, d.decoder))

  implicit def optionDecoder[T](implicit d: Decoder[T]): Decoder[Option[T]] =
    VertxDecoder((index, row, session) => if (row.getValue(index) == null) None else Some(d(index, row, session)))

  implicit val stringDecoder: Decoder[String]         = decoderOf((i, r) => r.getString(i))
  implicit val bigDecimalDecoder: Decoder[BigDecimal] = decoderOf { (i, r) =>
    r.getValue(i) match {
      case null                  => null
      case value: JavaBigDecimal => BigDecimal(value)
      case value: Numeric        => Option(value.bigDecimalValue()).map(BigDecimal(_)).orNull
      case value: Number         => BigDecimal(value.toString)
      case value                 => throw new IllegalArgumentException(s"Unsupported Vert.x numeric value ${value.getClass.getName}")
    }
  }
  implicit val booleanDecoder: Decoder[Boolean]       = decoderOf((i, r) => Option(r.getBoolean(i)).fold(false)(_.booleanValue()))
  implicit val byteDecoder: Decoder[Byte]             = decoderOf((i, r) => Option(r.getShort(i)).fold(0.toByte)(_.shortValue().toByte))
  implicit val shortDecoder: Decoder[Short]           = decoderOf((i, r) => Option(r.getShort(i)).fold(0.toShort)(_.shortValue()))
  implicit val intDecoder: Decoder[Int]               = decoderOf((i, r) => Option(r.getInteger(i)).fold(0)(_.intValue()))
  implicit val longDecoder: Decoder[Long]             = decoderOf((i, r) => Option(r.getLong(i)).fold(0L)(_.longValue()))
  implicit val floatDecoder: Decoder[Float]           = decoderOf((i, r) => Option(r.getFloat(i)).fold(0.0f)(_.floatValue()))
  implicit val doubleDecoder: Decoder[Double]         = decoderOf((i, r) => Option(r.getDouble(i)).fold(0.0d)(_.doubleValue()))
  implicit val byteArrayDecoder: Decoder[Array[Byte]] = decoderOf { (i, r) =>
    val value = r.getBuffer(i)
    if (value == null) null else value.getBytes
  }

  implicit val dateDecoder: Decoder[Date] = decoderOf((i, r) =>
    r.getValue(i) match {
      case value: LocalDateTime      => Date.from(value.atZone(ZoneId.systemDefault()).toInstant)
      case value: OffsetDateTime     => Date.from(value.toInstant)
      case value: Instant            => Date.from(value)
      case value: java.sql.Timestamp => new Date(value.getTime)
      case null                      => throw new NullPointerException("Cannot decode SQL NULL as java.util.Date")
      case value                     => throw new IllegalArgumentException(s"Unsupported Vert.x timestamp value ${value.getClass.getName}")
    }
  )
  implicit val sqlDateDecoder: Decoder[java.sql.Date] = decoderOf { (i, r) =>
    val value = r.getLocalDate(i)
    if (value == null) null else java.sql.Date.valueOf(value)
  }
  implicit val sqlTimeDecoder: Decoder[java.sql.Time] = decoderOf { (i, r) =>
    val value = r.getLocalTime(i)
    if (value == null) null else java.sql.Time.valueOf(value)
  }
  implicit val sqlTimestampDecoder: Decoder[java.sql.Timestamp] = decoderOf((i, r) =>
    r.getValue(i) match {
      case value: LocalDateTime      => java.sql.Timestamp.valueOf(value)
      case value: OffsetDateTime     => java.sql.Timestamp.from(value.toInstant)
      case value: Instant            => java.sql.Timestamp.from(value)
      case value: java.sql.Timestamp => value
      case null                      => null
      case value                     => throw new IllegalArgumentException(s"Unsupported Vert.x timestamp value ${value.getClass.getName}")
    }
  )
  implicit val localDateDecoder: Decoder[LocalDate]                   = decoderOf((i, r) => r.getLocalDate(i))
  implicit val localTimeDecoder: Decoder[LocalTime]                   = decoderOf((i, r) => r.getLocalTime(i))
  implicit val localDateTimeDecoder: Decoder[LocalDateTime]           = decoderOf((i, r) => r.getLocalDateTime(i))
  implicit val instantDecoder: Decoder[Instant]                       = decoderOf((i, r) => r.getOffsetDateTime(i).toInstant)
  implicit val offsetDateTimeDecoder: Decoder[OffsetDateTime]         = decoderOf((i, r) => r.getOffsetDateTime(i))
  implicit val offsetTimeDecoder: Decoder[OffsetTime]                 = decoderOf((i, r) => r.getOffsetTime(i))
  implicit val zonedDateTimeDecoder: Decoder[java.time.ZonedDateTime] =
    decoderOf((i, r) => r.getOffsetDateTime(i).toZonedDateTime)
  implicit val uuidDecoder: Decoder[UUID] = decoderOf((i, r) => r.getUUID(i))

}
