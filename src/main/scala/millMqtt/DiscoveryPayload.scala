package millMqtt

import millMqtt.MillApiBehavior.SessionInitDevice
import play.api.libs.json._

import java.net.URL

case class DiscoveryPayload(
    device: DiscoveryPayload.Device,
    origin: DiscoveryPayload.Origin,
    components: Seq[DiscoveryPayload.Component],
    stateTopic: String,
    qos: Int
)

object DiscoveryPayload {
  private val jsonConfiguration = Json.configured(JsonConfiguration(naming = JsonNaming.SnakeCase))
  private[DiscoveryPayload] implicit val urlWrites: Writes[URL] = url => JsString(url.toString)

  case class Device(
      identifiers: String,
      name: String,
      manufacturer: String,
      model: String,
      swVersion: String,
      hwVersion: String
  )
  private[DiscoveryPayload] implicit val deviceWrites: OWrites[Device] = jsonConfiguration.writes

  case class Origin(
      name: String = BuildInfo.name,
      swVersion: String = BuildInfo.version,
      supportUrl: Option[URL] = BuildInfo.homepage
  )
  private[DiscoveryPayload] implicit val originWrites: OWrites[Origin] = jsonConfiguration.writes

  case class Component(
      field: String,
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
  object Component {
    private def deriveNameFromField(field: String): String =
      field.flatMap {
        case '-' | '_'      => " "
        case c if c.isUpper => s" $c"
        case c              => c.toString
      }.capitalize

    def deriveUniqueIdFromField(field: String)(implicit device: SessionInitDevice): String =
      s"${BuildInfo.name}_${device.device_id}_$field"

    def binarySensor(
        field: String,
        invertSensor: Boolean = false,
        deviceClass: Option[String] = None,
        name: Option[String] = None,
        valueTemplate: Option[String] = None,
        icon: Option[String] = None
    )(implicit device: SessionInitDevice): Component = {
      lazy val derivedValueTemplate =
        if (invertSensor)
          s"{{ value_json.$field and 'OFF' or 'ON' }}"
        else
          s"{{ value_json.$field and 'ON' or 'OFF' }}"
      Component(
        field,
        platform = "binary_sensor",
        name = name.getOrElse(deriveNameFromField(field)),
        valueTemplate = valueTemplate.getOrElse(derivedValueTemplate),
        uniqueId = s"${BuildInfo.name}_${device.device_id}_$field",
        deviceClass = deviceClass,
        icon = icon
      )
    }

    def sensor(
        field: String,
        deviceClass: Option[String] = None,
        name: Option[String] = None,
        valueTemplate: Option[String] = None,
        icon: Option[String] = None,
        unitOfMeasurement: Option[String] = None,
        suggestedDisplayPrecision: Option[Int] = None
    )(implicit device: SessionInitDevice): Component = Component(
      field,
      platform = "sensor",
      name = name.getOrElse(deriveNameFromField(field)),
      valueTemplate = valueTemplate.getOrElse(s"{{ value_json.$field }}"),
      uniqueId = deriveUniqueIdFromField(field),
      deviceClass = deviceClass,
      unitOfMeasurement = unitOfMeasurement,
      icon = icon,
      suggestedDisplayPrecision = suggestedDisplayPrecision
    )

    private val componentWrites: OWrites[Component] = jsonConfiguration.writes
    private[DiscoveryPayload] implicit val componentSeqWrites: OWrites[Seq[Component]] = components =>
      JsObject(components.map { component => (component.field, componentWrites.writes(component)) })
  }

  implicit def discoveryPayloadWrites: OWrites[DiscoveryPayload] = jsonConfiguration.writes
}
