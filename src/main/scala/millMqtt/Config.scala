package millMqtt

object Config {
  private val env = sys.env.withDefault(name => throw new Exception(s"environment variable $name is not defined"))

  val MillEmail: String = env("MILL_EMAIL")
  val MillPassword: String = env("MILL_PASSWORD")

  val MqttBroker: String = env.getOrElse("MQTT_BROKER", "tcp://localhost:1883")
  val MqttDeviceDiscoveryTopicPrefix: String = env.getOrElse("MQTT_DEVICE_DISCOVERY_TOPIC_PREFIX", "homeassistant")
}
