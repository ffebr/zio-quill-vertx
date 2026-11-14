package zio.quill.context.vertx

import io.vertx.core.buffer.Buffer
import io.vertx.core.json.{JsonArray, JsonObject}
import io.vertx.sqlclient.Tuple
import io.vertx.sqlclient.data.{NullValue, Numeric}

import java.math.BigDecimal
import java.time.{LocalDate, LocalDateTime, LocalTime, OffsetDateTime, OffsetTime}
import java.util.UUID
import scala.collection.JavaConverters._

/** PostgreSQL-specific SQL and tuple adaptation for native Vert.x bindings. */
private[quill] object VertxPostgresBindings {
  final case class JsonParameter(value: AnyRef, jsonType: String)

  private val JsonTypes     = Set("json", "jsonb")
  private val NullPredicate = "(?is)^\\s*(?:\\)\\s*)?IS\\s+(?:NOT\\s+)?NULL\\b".r

  def prepare(
    sql: String,
    tuple: Tuple
  )(rewriteBindMarkers: (String, (Int, String, Int) => String) => String): (String, Tuple) = {
    val parameterTypes = tuple.types().asScala.toVector
    val jsonCasts      = scala.collection.mutable.Map.empty[Int, String]
    val parameterCasts = scala.collection.mutable.Map.empty[Int, String]
    val rewrittenTuple = Tuple.tuple()

    def recordParameterCast(index: Int, parameterType: Class[_]): Unit =
      sqlType(parameterType).foreach(valueType => parameterCasts.update(index, valueType))

    var index = 0
    while (index < tuple.size()) {
      val value = tuple.getValue(index)
      value match {
        case JsonParameter(jsonValue, jsonType) =>
          require(JsonTypes.contains(jsonType), s"Unsupported PostgreSQL JSON type: $jsonType")
          jsonCasts.update(index + 1, jsonType)
          rewrittenTuple.addValue(jsonValue)
        case null =>
          val parameterType = parameterTypes(index)
          if (parameterType != classOf[Object]) rewrittenTuple.addValue(NullValue.of(parameterType))
          else rewrittenTuple.addValue(null)
          recordParameterCast(index + 1, parameterType)
        case other =>
          rewrittenTuple.addValue(other)
          recordParameterCast(index + 1, other.getClass)
      }
      index += 1
    }

    val boundSql = rewriteBindMarkers(
      sql,
      (number, marker, offset) =>
        jsonCasts.get(number) match {
          case Some(jsonType) => s"CAST(CAST($marker AS text) AS $jsonType)"
          case None           =>
            parameterCasts.get(number).filter { valueType =>
              // Null predicates cannot infer a parameter type. Preserve target-column
              // inference for other scalar uses, including PostgreSQL enums.
              valueType == "numeric" || valueType.endsWith("[]") ||
              NullPredicate.findPrefixOf(sql.substring(offset + marker.length)).nonEmpty
            }.fold(marker)(valueType => s"($marker)::$valueType")
        }
    )
    (boundSql, rewrittenTuple)
  }

  private def sqlType(parameterType: Class[_]): Option[String] =
    if (parameterType == classOf[String]) Some("text")
    else if (parameterType == classOf[java.lang.Boolean]) Some("boolean")
    else if (parameterType == classOf[java.lang.Short]) Some("smallint")
    else if (parameterType == classOf[java.lang.Integer]) Some("integer")
    else if (parameterType == classOf[java.lang.Long]) Some("bigint")
    else if (parameterType == classOf[java.lang.Float]) Some("real")
    else if (parameterType == classOf[java.lang.Double]) Some("double precision")
    else if (classOf[BigDecimal].isAssignableFrom(parameterType) || classOf[Numeric].isAssignableFrom(parameterType)) Some("numeric")
    else if (parameterType == classOf[UUID]) Some("uuid")
    else if (parameterType == classOf[LocalDate]) Some("date")
    else if (parameterType == classOf[LocalTime]) Some("time")
    else if (parameterType == classOf[LocalDateTime]) Some("timestamp")
    else if (parameterType == classOf[OffsetTime]) Some("time with time zone")
    else if (parameterType == classOf[OffsetDateTime]) Some("timestamp with time zone")
    else if (classOf[Buffer].isAssignableFrom(parameterType)) Some("bytea")
    else if (parameterType == classOf[Array[String]]) Some("text[]")
    else if (parameterType == classOf[Array[java.lang.Boolean]]) Some("boolean[]")
    else if (parameterType == classOf[Array[java.lang.Short]]) Some("smallint[]")
    else if (parameterType == classOf[Array[java.lang.Integer]]) Some("integer[]")
    else if (parameterType == classOf[Array[java.lang.Long]]) Some("bigint[]")
    else if (parameterType == classOf[Array[java.lang.Float]]) Some("real[]")
    else if (parameterType == classOf[Array[java.lang.Double]]) Some("double precision[]")
    else if (
      parameterType.isArray &&
      (classOf[BigDecimal].isAssignableFrom(parameterType.getComponentType) ||
        classOf[Numeric].isAssignableFrom(parameterType.getComponentType))
    ) Some("numeric[]")
    else if (parameterType == classOf[Array[UUID]]) Some("uuid[]")
    else if (parameterType == classOf[Array[LocalDate]]) Some("date[]")
    else if (parameterType == classOf[Array[LocalTime]]) Some("time[]")
    else if (parameterType == classOf[Array[LocalDateTime]]) Some("timestamp[]")
    else if (parameterType == classOf[Array[OffsetTime]]) Some("time with time zone[]")
    else if (parameterType == classOf[Array[OffsetDateTime]]) Some("timestamp with time zone[]")
    else if (parameterType == classOf[Array[Buffer]]) Some("bytea[]")
    else if (parameterType == classOf[Array[JsonObject]] || parameterType == classOf[Array[JsonArray]]) Some("json[]")
    else None
}
