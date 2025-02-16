package millMqtt

import millMqtt.MillApiBehavior.SessionInitDevice
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.eclipse.paho.mqttv5.common.{MqttMessage, MqttSubscription}
import play.api.libs.json.{JsObject, Json}

import scala.concurrent.Await
import scala.concurrent.duration.Duration

object App extends scala.App {
  private object AppBehavior {
    sealed trait Message
    private final case class DeviceUpdate(device: SessionInitDevice, payload: JsObject) extends Message
    private final case class MessageReceived(topic: String, message: MqttMessage) extends Message

    def apply(): Behavior[Message] = Behaviors.setup { context =>
      val mqttAdapter = context.messageAdapter[MqttBehavior.MqttMessageEvent] {
        case MqttBehavior.MqttMessageEvent(topic, message) => MessageReceived(topic, message)
      }
      val mqtt = context.spawn(MqttBehavior(mqttAdapter), "mqtt")
      context.watch(mqtt)
      val apiAdapter = context.messageAdapter[(SessionInitDevice, JsObject)] { case (device, payload) =>
        DeviceUpdate(device, payload)
      }
      val millApi = context.spawn(MillApiBehavior(apiAdapter, Config.MillEmail, Config.MillPassword), "mill-api")
      context.watch(millApi)
      readyForMessages(millApi, mqtt)
    }

    private def readyForMessages(
        millApi: ActorRef[MillApiBehavior.Message],
        mqtt: ActorRef[MqttBehavior.Message],
        knownDevices: Set[SessionInitDevice] = Set.empty
    ): Behavior[Message] = {
      object knownDevice {
        def unapply(deviceId: String): Option[SessionInitDevice] = knownDevices.find(_.device_id == deviceId)
      }

      Behaviors.receive {
        case (_, DeviceUpdate(device, payload)) =>
          mqtt ! MqttBehavior.Publish(stateTopic(device), new MqttMessage(Json.toBytes(payload), 2, true, null))
          if (knownDevices contains device) {
            Behaviors.same
          } else {
            // unrecognized device
            // add to list of known devices, publish discovery payload, and subscribe to commands
            val discoveryPayloadMessage =
              new MqttMessage(Json.toBytes(Json.toJson(discoveryPayload(payload)(device))), 2, true, null)
            mqtt ! MqttBehavior.Publish(discoveryTopic(device), discoveryPayloadMessage)
            mqtt ! MqttBehavior.Subscribe(new MqttSubscription(commandTopic(device, "+"), 2))
            readyForMessages(millApi, mqtt, knownDevices + device)
          }

        case (_, MessageReceived(CommandTopicRegex(knownDevice(device), "dgoCycle"), message)) =>
          val cycle = if (new String(message.getPayload) == "ON") "DryGrind" else "Idle"
          millApi ! MillApiBehavior.SetCycle(device, cycle)
          Behaviors.same

        case unexpectedMessage =>
          throw new Exception(s"unexpected message: $unexpectedMessage")
      }
    }

    private def discoveryTopic(device: SessionInitDevice): String =
      s"${Config.MqttDeviceDiscoveryTopicPrefix}/device/${device.device_id}/config"
    private def stateTopic(device: SessionInitDevice): String = s"${BuildInfo.name}/${device.device_id}/state"
    private def commandTopic(device: SessionInitDevice, entity: String): String =
      s"${BuildInfo.name}/${device.device_id}/$entity/set"
    private val CommandTopicRegex = commandTopic(SessionInitDevice("(?<deviceId>[^/]+)", None), "(?<entity>[^/]+)").r

    private def discoveryPayload(payload: JsObject)(implicit device: SessionInitDevice): DiscoveryPayload = {
      DiscoveryPayload(
        device = DiscoveryPayload.Device(
          identifiers = device.device_id,
          name = s"Mill - ${device.nickname.getOrElse(device.device_id)}",
          manufacturer = "Mill",
          model = "Food Recycler",
          swVersion = (payload \ "firmwareVersion").as[String],
          hwVersion = (payload \ "oscarVersion").as[BigDecimal].toString()
        ),
        origin = DiscoveryPayload.Origin(),
        components = Seq(
          DiscoveryPayload.Component.binarySensor(
            field = "lidLockState",
            invertSensor = true,
            deviceClass = Some("lock"),
            name = Some("Lid Locked")
          ),
          DiscoveryPayload.Component.binarySensor(
            field = "lidOpenState",
            deviceClass = Some("door"),
            name = Some("Lid Open")
          ),
          DiscoveryPayload.Component.binarySensor(
            field = "bucketMissing",
            deviceClass = Some("problem")
          ),
          DiscoveryPayload.Component.binarySensor(
            field = "childLockEnabled",
            invertSensor = true,
            deviceClass = Some("lock"),
            name = Some("Child Lock")
          ),
          DiscoveryPayload.Component.binarySensor(
            field = "online",
            deviceClass = Some("connectivity")
          ),
          DiscoveryPayload.Component.sensor(
            field = "massInBucket",
            deviceClass = Some("weight"),
            unitOfMeasurement = Some("kg"),
            suggestedDisplayPrecision = Some(2)
          ),
          DiscoveryPayload.Component.sensor(
            field = "massAddedSinceBucketEmpty",
            deviceClass = Some("weight"),
            icon = Some("mdi:pail-plus"),
            unitOfMeasurement = Some("kg"),
            suggestedDisplayPrecision = Some(2)
          ),
          DiscoveryPayload.Component.sensor(
            field = "unprocessedMass",
            deviceClass = Some("weight"),
            unitOfMeasurement = Some("kg"),
            suggestedDisplayPrecision = Some(2)
          ),
          DiscoveryPayload.Component.sensor(
            field = "bucketFullness",
            icon = Some("mdi:delete-variant")
          ),
          DiscoveryPayload.Component.sensor(
            field = "nextDgoCycleStartTime",
            deviceClass = Some("timestamp"),
            name = Some("Next Cycle Start Time")
          ),
          DiscoveryPayload.Component.sensor(
            field = "nextDgoCycleDuration",
            deviceClass = Some("duration"),
            name = Some("Next Cycle Duration"),
            unitOfMeasurement = Some("s")
          ),
          DiscoveryPayload.Component(
            field = "dgoCycle",
            platform = "switch",
            name = "Dry and Grind",
            valueTemplate =
              "{{ ((value_json.dgoCycle.desired or value_json.dgoCycle.reported) == 'DryGrind') and 'ON' or 'OFF' }}",
            uniqueId = DiscoveryPayload.Component.deriveUniqueIdFromField("dgoCycle"),
            deviceClass = Some("switch"),
            commandTopic = Some(commandTopic(device, "dgoCycle"))
          )
        ),
        stateTopic = stateTopic(device),
        qos = 2
      )
    }
  }

  private val actorSystem = ActorSystem(AppBehavior(), "app")
  Await.result(actorSystem.whenTerminated, Duration.Inf)
  sys.exit()
}
