package millMqtt

import play.api.libs.json._

import java.net.URL
import scala.annotation.unused

case class DiscoveryPayload(
    device: DiscoveryPayload.Device,
    origin: DiscoveryPayload.Origin,
    components: Map[String, DiscoveryPayload.Component],
    stateTopic: String,
    qos: Int
)

object DiscoveryPayload {
  private val jsonConfiguration = Json.configured(JsonConfiguration(naming = JsonNaming.SnakeCase))
  @unused private implicit val urlWrites: Writes[URL] = url => JsString(url.toString)

  case class Device(
      identifiers: String,
      name: String,
      manufacturer: String,
      model: String,
      swVersion: String,
      hwVersion: String
  )
  @unused private implicit val deviceWrites: OWrites[Device] = jsonConfiguration.writes

  case class Origin(
      name: String = BuildInfo.name,
      swVersion: String = BuildInfo.version,
      supportUrl: Option[URL] = BuildInfo.homepage
  )
  @unused private implicit val originWrites: OWrites[Origin] = jsonConfiguration.writes

  case class Component(
      platform: String,
      name: String,
      valueTemplate: String,
      uniqueId: String,
      deviceClass: Option[String] = None,
      unitOfMeasurement: Option[String] = None,
      icon: Option[String] = None,
      suggestedDisplayPrecision: Option[Int] = None,
      commandTopic: Option[String] = None
  )
  @unused private implicit val componentWrites: OWrites[Component] = jsonConfiguration.writes

  implicit val discoveryPayloadWrites: OWrites[DiscoveryPayload] = jsonConfiguration.writes
}
