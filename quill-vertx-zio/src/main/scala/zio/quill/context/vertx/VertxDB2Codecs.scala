package zio.quill.context.vertx

import io.vertx.sqlclient.data.Numeric
import zio.quill.context.sql.SqlContext

import java.time.{Instant, LocalDateTime, LocalTime, OffsetDateTime, OffsetTime, ZoneOffset, ZonedDateTime}
import java.util.UUID

/** DB2 has no offset timestamp/time representation in this Vert.x client. */
trait VertxDB2Codecs extends VertxBaseEncoders with VertxBaseDecoders {
  this: SqlContext[_, _] =>

  private def utcLocalDateTime(value: Instant): LocalDateTime = LocalDateTime.ofInstant(value, ZoneOffset.UTC)
  private def utcLocalTime(value: OffsetTime): LocalTime      = value.withOffsetSameInstant(ZoneOffset.UTC).toLocalTime

  override implicit val instantEncoder: Encoder[Instant] =
    VertxEncoder((index, value, row, _) => append(index, utcLocalDateTime(value), row))
  override implicit val offsetDateTimeEncoder: Encoder[OffsetDateTime] =
    VertxEncoder((index, value, row, _) => append(index, utcLocalDateTime(value.toInstant), row))
  override implicit val zonedDateTimeEncoder: Encoder[ZonedDateTime] =
    VertxEncoder((index, value, row, _) => append(index, utcLocalDateTime(value.toInstant), row))
  override implicit val offsetTimeEncoder: Encoder[OffsetTime] =
    VertxEncoder((index, value, row, _) => append(index, utcLocalTime(value), row))
  override implicit val uuidEncoder: Encoder[UUID] =
    VertxEncoder((index, value, row, _) => append(index, value.toString, row))

  override implicit val instantDecoder: Decoder[Instant]               = localTimestampDecoder(_.toInstant(ZoneOffset.UTC))
  override implicit val offsetDateTimeDecoder: Decoder[OffsetDateTime] = localTimestampDecoder(_.atOffset(ZoneOffset.UTC))
  override implicit val zonedDateTimeDecoder: Decoder[ZonedDateTime]   = localTimestampDecoder(_.atOffset(ZoneOffset.UTC).toZonedDateTime)
  override implicit val offsetTimeDecoder: Decoder[OffsetTime]         =
    VertxDecoder { (index, row, _) =>
      val value = row.getLocalTime(index)
      if (value == null) null else value.atOffset(ZoneOffset.UTC)
    }
  override implicit val uuidDecoder: Decoder[UUID] =
    VertxDecoder { (index, row, _) =>
      val value = row.getString(index)
      if (value == null) null else UUID.fromString(value)
    }
  override implicit val bigDecimalDecoder: Decoder[BigDecimal] =
    VertxDecoder { (index, row, _) =>
      val value: Numeric = row.getNumeric(index)
      if (value == null) null else Option(value.bigDecimalValue()).map(BigDecimal(_)).orNull
    }

  private def localTimestampDecoder[T](convert: LocalDateTime => T): Decoder[T] =
    VertxDecoder { (index, row, _) =>
      val value = row.getLocalDateTime(index)
      if (value == null) null.asInstanceOf[T] else convert(value)
    }
}
