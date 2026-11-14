package zio.quill.context.vertx

import zio.quill.context.sql.SqlContext
import io.vertx.sqlclient.data.NullValue

import java.time.{Instant, LocalDateTime, LocalTime, OffsetDateTime, OffsetTime, ZoneOffset, ZonedDateTime}
import java.util.UUID

/**
 * MySQL's native protocol represents timestamp and datetime values as
 * LocalDateTime.
 */
trait VertxMysqlCodecs extends VertxBaseEncoders with VertxBaseDecoders {
  this: SqlContext[_, _] =>

  private def utcLocalDateTime(value: Instant): LocalDateTime     = LocalDateTime.ofInstant(value, ZoneOffset.UTC)
  private def utcLocalTime(value: OffsetTime): LocalTime          = value.withOffsetSameInstant(ZoneOffset.UTC).toLocalTime
  private def asUtcDateTime(value: LocalDateTime): OffsetDateTime = value.atOffset(ZoneOffset.UTC)

  override implicit val instantEncoder: Encoder[Instant] =
    VertxEncoder[Instant]((index, value, row, _) => append(index, utcLocalDateTime(value), row)).withNullValue(NullValue.LocalDateTime)
  override implicit val offsetDateTimeEncoder: Encoder[OffsetDateTime] =
    VertxEncoder[OffsetDateTime]((index, value, row, _) => append(index, utcLocalDateTime(value.toInstant), row)).withNullValue(NullValue.LocalDateTime)
  override implicit val zonedDateTimeEncoder: Encoder[ZonedDateTime] =
    VertxEncoder[ZonedDateTime]((index, value, row, _) => append(index, utcLocalDateTime(value.toInstant), row)).withNullValue(NullValue.LocalDateTime)
  override implicit val offsetTimeEncoder: Encoder[OffsetTime] =
    VertxEncoder[OffsetTime]((index, value, row, _) => append(index, utcLocalTime(value), row)).withNullValue(NullValue.LocalTime)
  override implicit val uuidEncoder: Encoder[UUID] =
    VertxEncoder[UUID]((index, value, row, _) => append(index, value.toString, row)).withNullValue(NullValue.String)

  override implicit val instantDecoder: Decoder[Instant]               = localTimestampDecoder(_.toInstant(ZoneOffset.UTC))
  override implicit val offsetDateTimeDecoder: Decoder[OffsetDateTime] = localTimestampDecoder(asUtcDateTime)
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

  private def localTimestampDecoder[T](convert: LocalDateTime => T): Decoder[T] =
    VertxDecoder { (index, row, _) =>
      val value = row.getLocalDateTime(index)
      if (value == null) null.asInstanceOf[T] else convert(value)
    }
}
