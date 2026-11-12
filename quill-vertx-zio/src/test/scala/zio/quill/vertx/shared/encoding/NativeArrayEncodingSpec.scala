package zio.quill.vertx.shared.encoding

import java.sql.Timestamp
import java.util.UUID

import org.scalatest.matchers.should.Matchers._
import zio.quill.context.sql.encoding.ArrayEncodingBaseSpec
import zio.quill.vertx.VertxPostgresSpecSupport

import scala.collection.compat._
import scala.collection.JavaConverters._

class NativeArrayEncodingSpec extends VertxPostgresSpecSupport with ArrayEncodingBaseSpec {
  val ctx = context
  import ctx._

  private val q         = quote(query[ArraysTestEntity])
  private val corrected = e.copy(timestamps = e.timestamps.map(d => new Timestamp(d.getTime)))

  override def beforeEach(): Unit = {
    super.beforeEach()
    unsafeRun(ctx.run(q.delete))
  }

  "Support all sql base types and `Seq` implementers" in {
    unsafeRun(ctx.run(q.insertValue(lift(corrected))))
    val actual = unsafeRun(ctx.run(q)).head
    actual mustEqual corrected
    baseEntityDeepCheck(actual, corrected)
  }

  "Support Seq encoding basing on MappedEncoding" in {
    val wrapQ = quote(querySchema[WrapEntity]("ArraysTestEntity"))
    unsafeRun(ctx.run(wrapQ.insertValue(lift(wrapE))))
    unsafeRun(ctx.run(wrapQ)).head.texts mustBe wrapE.texts
  }

  "Timestamps" in {
    case class Timestamps(timestamps: List[Timestamp])
    val tE = Timestamps(List(new Timestamp(System.currentTimeMillis())))
    val tQ = quote(querySchema[Timestamps]("ArraysTestEntity"))
    unsafeRun(ctx.run(tQ.insertValue(lift(tE))))
    unsafeRun(ctx.run(tQ)).head.timestamps mustBe tE.timestamps
  }

  // The JDBC assertion depended on a driver returning java.sql.Date for a DATE[] column and
  // the identity decoder then failing its cast. Vert.x returns java.time.LocalDate natively,
  // so that driver-specific failure mode has no native equivalent to preserve.

  "Custom decoders/encoders" in {
    case class Entity(uuids: List[UUID])
    val value = Entity(List(UUID.randomUUID(), UUID.randomUUID()))
    val query = quote(querySchema[Entity]("ArraysTestEntity"))

    implicit def arrayUUIDEncoder[Col <: Seq[UUID]]: Encoder[Col] =
      VertxEncoder { (index, values, tuple, _) =>
        require(index == tuple.size(), s"Vert.x tuple bind index $index does not match next position ${tuple.size()}")
        tuple.addValue(values.toArray.asInstanceOf[AnyRef])
      }
    implicit def arrayUUIDDecoder[Col <: Seq[UUID]](implicit factory: Factory[UUID, Col]): Decoder[Col] =
      VertxDecoder { (index, row, _) =>
        val raw              = row.getValue(index)
        val values: Seq[Any] = raw match {
          case null                  => Seq.empty
          case xs: Seq[_]            => xs
          case xs: Array[_]          => xs.toSeq
          case xs: java.util.List[_] => xs.asScala.toSeq
          case other                 => throw new IllegalArgumentException(s"Expected PostgreSQL UUID array, received ${other.getClass.getName}")
        }
        values.iterator.map {
          case uuid: UUID => uuid
          case text       => UUID.fromString(text.toString)
        }.foldLeft(factory.newBuilder)(_ += _).result()
      }

    unsafeRun(ctx.run(query.insertValue(lift(value))))
    unsafeRun(ctx.run(query)).head.uuids mustBe value.uuids
  }

  "Arrays in where clause" in {
    unsafeRun(ctx.run(q.insertValue(lift(corrected))))
    val actual1 = unsafeRun(ctx.run(q.filter(_.texts == lift(List("test")))))
    val actual2 = unsafeRun(ctx.run(q.filter(_.texts == lift(List("test2")))))
    actual1 mustEqual List(corrected)
    actual2 mustEqual List.empty
  }

  "empty array on found null" in {
    case class ArraysTestEntity(texts: Option[List[String]])
    unsafeRun(ctx.run(query[ArraysTestEntity].insertValue(lift(ArraysTestEntity(None)))))
    case class E(texts: List[String])
    unsafeRun(ctx.run(querySchema[E]("ArraysTestEntity"))).headOption.map(_.texts) mustBe Some(Nil)
  }
}
