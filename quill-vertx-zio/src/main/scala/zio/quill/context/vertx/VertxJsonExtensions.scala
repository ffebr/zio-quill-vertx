package zio.quill.context.vertx

import io.vertx.sqlclient.Tuple
import zio.json.ast.Json
import zio.json.{JsonDecoder, JsonEncoder}
import zio.quill.{JsonValue, JsonbValue}
import zio.quill.context.sql.SqlContext

import scala.reflect.{ClassTag, classTag}

/** Native PostgreSQL JSON and JSONB codecs for the Vert.x client. */
trait VertxJsonExtensions extends VertxEncoders with VertxDecoders {
  this: SqlContext[_, _] =>

  implicit final def jsonEntityEncoder[T](implicit jsonEncoder: JsonEncoder[T]): Encoder[JsonValue[T]] =
    entityEncoder[T, JsonValue[T]](_.value)("json", jsonEncoder)

  implicit final def jsonEntityDecoder[T: ClassTag](implicit jsonDecoder: JsonDecoder[T]): Decoder[JsonValue[T]] =
    entityDecoder[T, JsonValue[T]](JsonValue(_))("json", jsonDecoder)

  implicit final def jsonbEntityEncoder[T](implicit jsonEncoder: JsonEncoder[T]): Encoder[JsonbValue[T]] =
    entityEncoder[T, JsonbValue[T]](_.value)("jsonb", jsonEncoder)

  implicit final def jsonbEntityDecoder[T: ClassTag](implicit jsonDecoder: JsonDecoder[T]): Decoder[JsonbValue[T]] =
    entityDecoder[T, JsonbValue[T]](JsonbValue(_))("jsonb", jsonDecoder)

  implicit final val jsonAstEncoder: Encoder[JsonValue[Json]]   = astEncoder[JsonValue[Json]](_.value.toString, "json")
  implicit final val jsonAstDecoder: Decoder[JsonValue[Json]]   = astDecoder(JsonValue(_))
  implicit final val jsonbAstEncoder: Encoder[JsonbValue[Json]] = astEncoder[JsonbValue[Json]](_.value.toString, "jsonb")
  implicit final val jsonbAstDecoder: Decoder[JsonbValue[Json]] = astDecoder(JsonbValue(_))

  def astEncoder[Wrapper](valueToString: Wrapper => String, jsonType: String): Encoder[Wrapper] = {
    require(jsonType == "json" || jsonType == "jsonb", s"Unsupported PostgreSQL JSON type: $jsonType")
    encodeJsonValue(valueToString)
  }

  def astDecoder[Wrapper](valueFromJson: Json => Wrapper): Decoder[Wrapper] =
    decodeJsonValue(value =>
      Json.decoder.decodeJson(value) match {
        case Right(json) => valueFromJson(json)
        case Left(error) =>
          throw new IllegalArgumentException(s"Error decoding JSON '$value' into zio-json Json. Message: $error")
      }
    )

  def entityEncoder[JsValue, Wrapper](unwrap: Wrapper => JsValue)(
    jsonType: String,
    jsonEncoder: JsonEncoder[JsValue]
  ): Encoder[Wrapper] = {
    require(jsonType == "json" || jsonType == "jsonb", s"Unsupported PostgreSQL JSON type: $jsonType")
    encodeJsonValue(value => jsonEncoder.encodeJson(unwrap(value), None).toString)
  }

  def entityDecoder[JsValue: ClassTag, Wrapper](wrap: JsValue => Wrapper)(
    jsonType: String,
    jsonDecoder: JsonDecoder[JsValue]
  ): Decoder[Wrapper] = {
    require(jsonType == "json" || jsonType == "jsonb", s"Unsupported PostgreSQL JSON type: $jsonType")
    decodeJsonValue { value =>
      jsonDecoder.decodeJson(value) match {
        case Right(decoded) => wrap(decoded)
        case Left(error)    =>
          throw new IllegalArgumentException(
            s"Error decoding JSON '$value' into ${classTag[JsValue]}. Message: $error"
          )
      }
    }
  }

  private def encodeJsonValue[Wrapper](toJson: Wrapper => String): Encoder[Wrapper] =
    VertxEncoder((index, value, tuple, _) => append(index, fromJsonText(toJson(value)), tuple))

  private def decodeJsonValue[Wrapper](fromJson: String => Wrapper): Decoder[Wrapper] =
    VertxDecoder((index, row, _) => fromJson(toJsonText(row.getValue(index))))

  private def fromJsonText(value: String): AnyRef =
    Json.decoder.decodeJson(value) match {
      case Right(json) => toVertxJson(json)
      case Left(error) => throw new IllegalArgumentException(s"Invalid JSON emitted by zio-json: $error")
    }

  private def toVertxJson(value: Json): AnyRef = value match {
    case Json.Obj(fields) =>
      val result = new io.vertx.core.json.JsonObject()
      fields.foreach { case (key, fieldValue) => result.put(key, toVertxJson(fieldValue)) }
      result
    case Json.Arr(values) =>
      val result = new io.vertx.core.json.JsonArray()
      values.foreach(fieldValue => result.add(toVertxJson(fieldValue)))
      result
    case Json.Str(string)   => string
    case Json.Num(number)   => number
    case Json.Bool(boolean) => Boolean.box(boolean)
    case Json.Null          => Tuple.JSON_NULL
  }

  private def toJsonText(value: Any): String =
    if (value == null)
      throw new IllegalArgumentException("Cannot decode a SQL NULL as a non-optional JSON value")
    else if (value == Tuple.JSON_NULL)
      "null"
    else
      io.vertx.core.json.Json.encode(value)
}
