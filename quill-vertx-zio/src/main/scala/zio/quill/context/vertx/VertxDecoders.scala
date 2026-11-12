package zio.quill.context.vertx

import zio.quill.context.sql.SqlContext
import zio.quill.context.sql.encoding.ArrayEncoding

import java.sql.Timestamp
import java.time.{LocalDate, LocalDateTime, OffsetDateTime, ZoneId}
import java.util.Date
import scala.collection.compat._
import scala.collection.JavaConverters._

trait VertxDecoders extends VertxBaseDecoders with ArrayEncoding {
  this: SqlContext[_, _] =>

  implicit def arrayStringDecoder[Col <: Seq[String]](implicit factory: Factory[String, Col]): Decoder[Col] =
    arrayDecoder(_.asInstanceOf[String])
  implicit def arrayBigDecimalDecoder[Col <: Seq[BigDecimal]](implicit factory: Factory[BigDecimal, Col]): Decoder[Col] =
    arrayDecoder {
      case v: java.math.BigDecimal => BigDecimal(v)
      case v: Number               => BigDecimal(v.toString)
      case v                       => BigDecimal(v.toString)
    }
  implicit def arrayBooleanDecoder[Col <: Seq[Boolean]](implicit factory: Factory[Boolean, Col]): Decoder[Col] =
    arrayDecoder(_.asInstanceOf[Boolean])
  implicit def arrayByteDecoder[Col <: Seq[Byte]](implicit factory: Factory[Byte, Col]): Decoder[Col] =
    arrayDecoder { case n: Number => n.byteValue() }
  implicit def arrayShortDecoder[Col <: Seq[Short]](implicit factory: Factory[Short, Col]): Decoder[Col] =
    arrayDecoder { case n: Number => n.shortValue() }
  implicit def arrayIntDecoder[Col <: Seq[Int]](implicit factory: Factory[Int, Col]): Decoder[Col] =
    arrayDecoder { case n: Number => n.intValue() }
  implicit def arrayLongDecoder[Col <: Seq[Long]](implicit factory: Factory[Long, Col]): Decoder[Col] =
    arrayDecoder { case n: Number => n.longValue() }
  implicit def arrayFloatDecoder[Col <: Seq[Float]](implicit factory: Factory[Float, Col]): Decoder[Col] =
    arrayDecoder { case n: Number => n.floatValue() }
  implicit def arrayDoubleDecoder[Col <: Seq[Double]](implicit factory: Factory[Double, Col]): Decoder[Col] =
    arrayDecoder { case n: Number => n.doubleValue() }
  implicit def arrayDateDecoder[Col <: Seq[Date]](implicit factory: Factory[Date, Col]): Decoder[Col] =
    arrayDecoder {
      case d: LocalDateTime  => Date.from(d.atZone(ZoneId.systemDefault()).toInstant)
      case d: OffsetDateTime => Date.from(d.toInstant)
      case d: Date           => d
    }
  implicit def arrayTimestampDecoder[Col <: Seq[Timestamp]](implicit factory: Factory[Timestamp, Col]): Decoder[Col] =
    arrayDecoder {
      case value: LocalDateTime => Timestamp.valueOf(value)
      case value: Timestamp     => value
    }
  implicit def arrayLocalDateDecoder[Col <: Seq[LocalDate]](implicit factory: Factory[LocalDate, Col]): Decoder[Col] =
    arrayDecoder(_.asInstanceOf[LocalDate])

  private def arrayDecoder[T, Col <: Seq[T]](convert: Any => T)(implicit factory: Factory[T, Col]): Decoder[Col] =
    decoderOf { (index, row) =>
      val raw: Any         = row.getValue(index)
      val values: Seq[Any] = raw match {
        case null                  => Seq.empty
        case xs: Seq[_]            => xs.asInstanceOf[Seq[Any]]
        case xs: Array[_]          => xs.toSeq.asInstanceOf[Seq[Any]]
        case xs: java.util.List[_] => xs.asScala.toSeq.asInstanceOf[Seq[Any]]
        case other                 => throw new IllegalArgumentException(s"Expected PostgreSQL array, received ${other.getClass.getName}")
      }
      values.iterator.map(convert).foldLeft(factory.newBuilder)(_ += _).result()
    }
}
