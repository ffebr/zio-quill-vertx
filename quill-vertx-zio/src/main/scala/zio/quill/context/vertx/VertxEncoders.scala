package zio.quill.context.vertx

import zio.quill.context.sql.SqlContext
import zio.quill.context.sql.encoding.ArrayEncoding

import java.time.{LocalDate, LocalDateTime, ZoneId}
import java.util.Date
import io.vertx.sqlclient.data.NullValue
import scala.reflect.{ClassTag, classTag}

trait VertxEncoders extends VertxBaseEncoders with ArrayEncoding {
  this: SqlContext[_, _] =>

  implicit def arrayStringEncoder[Col <: Seq[String]]: Encoder[Col]                = arrayEncoder(_.toArray)
  implicit def arrayBigDecimalEncoder[Col <: Seq[BigDecimal]]: Encoder[Col]        = arrayEncoder(_.map(_.bigDecimal).toArray)
  implicit def arrayBooleanEncoder[Col <: Seq[Boolean]]: Encoder[Col]              = arrayEncoder(_.map(Boolean.box).toArray)
  implicit def arrayByteEncoder[Col <: Seq[Byte]]: Encoder[Col]                    = arrayEncoder(_.map(v => Short.box(v.toShort)).toArray)
  implicit def arrayShortEncoder[Col <: Seq[Short]]: Encoder[Col]                  = arrayEncoder(_.map(Short.box).toArray)
  implicit def arrayIntEncoder[Col <: Seq[Int]]: Encoder[Col]                      = arrayEncoder(_.map(Int.box).toArray)
  implicit def arrayLongEncoder[Col <: Seq[Long]]: Encoder[Col]                    = arrayEncoder(_.map(Long.box).toArray)
  implicit def arrayFloatEncoder[Col <: Seq[Float]]: Encoder[Col]                  = arrayEncoder(_.map(Float.box).toArray)
  implicit def arrayDoubleEncoder[Col <: Seq[Double]]: Encoder[Col]                = arrayEncoder(_.map(Double.box).toArray)
  implicit def arrayDateEncoder[Col <: Seq[Date]]: Encoder[Col]                    = arrayEncoder(_.map(v => LocalDateTime.ofInstant(v.toInstant, ZoneId.systemDefault())).toArray)
  implicit def arrayTimestampEncoder[Col <: Seq[java.sql.Timestamp]]: Encoder[Col] = arrayEncoder(_.map(_.toLocalDateTime).toArray)
  implicit def arrayLocalDateEncoder[Col <: Seq[LocalDate]]: Encoder[Col]          = arrayEncoder(_.toArray)

  private def arrayEncoder[Col <: Seq[_], Elem <: AnyRef: ClassTag](f: Col => Array[Elem]): Encoder[Col] =
    encoderOf[Col]((i, v, r) => append(i, f(v), r))
      .withNullValue(NullValue.of(classTag[Elem].newArray(0).getClass))
}
