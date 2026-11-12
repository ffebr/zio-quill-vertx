package zio.quill.vertx.shared.encoding

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import org.scalatest.matchers.should.Matchers._
import zio.quill.{Literal, PostgresZioVertxContext, Query}
import zio.quill.context.sql.{EncodingSpec, TestDecoders, TestEncoders}
import zio.quill.vertx.VertxPostgresSpecSupport

/**
 * Reuses the shared encoding fixtures and assertions with the native Vert.x
 * row/session types.
 */
class NativeEncodingSpec extends VertxPostgresSpecSupport with EncodingSpec {
  override val context: PostgresZioVertxContext[Literal] with TestEncoders with TestDecoders =
    new PostgresZioVertxContext(Literal) with TestEncoders with TestDecoders
  import context._

  "encodes and decodes types" in {
    unsafeRun(context.run(delete))
    unsafeRun(context.run(liftQuery(insertValues).foreach(e => insert(e))))
    verify(unsafeRun(context.run(query[EncodingTestEntity])))
  }

  "encodes sets" in {
    unsafeRun(context.run(query[EncodingTestEntity].delete))
    unsafeRun(context.run(liftQuery(insertValues).foreach(e => query[EncodingTestEntity].insertValue(e))))
    val q      = quote { (set: Query[Int]) => query[EncodingTestEntity].filter(t => set.contains(t.v6)) }
    val actual = unsafeRun(context.run(q(liftQuery(insertValues.map(_.v6)))))
    verify(actual)
  }

  "returning custom type" in {
    val uuid             = unsafeRun(context.run(insertBarCode(lift(barCodeEntry)))).get
    val (barCode :: Nil) = unsafeRun(context.run(findBarCodeByUuid(uuid)))
    verifyBarcode(barCode)
  }

  "LocalDateTime" in {
    case class EncodingTestEntity(v11: Option[LocalDateTime])
    val now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS)
    val e1  = EncodingTestEntity(Some(now))
    val e2  = EncodingTestEntity(None)
    val res = unsafeRun(for {
      _           <- context.run(query[EncodingTestEntity].delete)
      _           <- context.run(query[EncodingTestEntity].insertValue(lift(e1)))
      withoutNull <- context.run(query[EncodingTestEntity])
      _           <- context.run(query[EncodingTestEntity].delete)
      _           <- context.run(query[EncodingTestEntity].insertValue(lift(e2)))
      withNull    <- context.run(query[EncodingTestEntity])
    } yield (withoutNull, withNull))
    res._1 must contain theSameElementsAs List(EncodingTestEntity(Some(now)))
    res._2 must contain theSameElementsAs List(EncodingTestEntity(None))
  }

  "Encode/Decode Other Time Types" in {
    unsafeRun(context.run(query[TimeEntity].delete))
    val timeEntity = TimeEntity.make(java.time.ZoneId.systemDefault())
    unsafeRun(context.run(query[TimeEntity].insertValue(lift(timeEntity))))
    unsafeRun(context.run(query[TimeEntity])).head mustEqual timeEntity
  }

  "Encode/Decode Other Time Types ordering" in {
    unsafeRun(context.run(query[TimeEntity].delete))
    val zid         = java.time.ZoneId.systemDefault()
    val timeEntityA = TimeEntity.make(zid, TimeEntity.TimeEntityInput(2022, 1, 1, 1, 1, 1, 0))
    val timeEntityB = TimeEntity.make(zid, TimeEntity.TimeEntityInput(2022, 2, 2, 2, 2, 2, 0))
    import context.extras._
    unsafeRun(context.run(quote(query[TimeEntity].insertValue(lift(timeEntityA)))))
    unsafeRun(context.run(quote(query[TimeEntity].insertValue(lift(timeEntityB)))))

    assert(timeEntityB.sqlDate > timeEntityA.sqlDate)
    assert(timeEntityB.sqlTime > timeEntityA.sqlTime)
    assert(timeEntityB.sqlTimestamp > timeEntityA.sqlTimestamp)
    assert(timeEntityB.timeLocalDate > timeEntityA.timeLocalDate)
    assert(timeEntityB.timeLocalTime > timeEntityA.timeLocalTime)
    assert(timeEntityB.timeLocalDateTime > timeEntityA.timeLocalDateTime)
    assert(timeEntityB.timeZonedDateTime > timeEntityA.timeZonedDateTime)
    assert(timeEntityB.timeInstant > timeEntityA.timeInstant)
    assert(timeEntityB.timeOffsetTime > timeEntityA.timeOffsetTime)
    assert(timeEntityB.timeOffsetDateTime > timeEntityA.timeOffsetDateTime)
    val actual = unsafeRun(context.run(query[TimeEntity].filter(t =>
      t.sqlDate > lift(timeEntityA.sqlDate) && t.sqlTime > lift(timeEntityA.sqlTime) &&
        t.sqlTimestamp > lift(timeEntityA.sqlTimestamp) && t.timeLocalDate > lift(timeEntityA.timeLocalDate) &&
        t.timeLocalTime > lift(timeEntityA.timeLocalTime) && t.timeLocalDateTime > lift(timeEntityA.timeLocalDateTime) &&
        t.timeZonedDateTime > lift(timeEntityA.timeZonedDateTime) && t.timeInstant > lift(timeEntityA.timeInstant) &&
        t.timeOffsetTime > lift(timeEntityA.timeOffsetTime) && t.timeOffsetDateTime > lift(timeEntityA.timeOffsetDateTime)
    ))).head
    assert(actual == timeEntityB)
  }
}
