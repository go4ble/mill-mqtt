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
              new MqttMessage(Json.toBytes(Json.toJson(discoveryPayload(device, payload))), 2, true, null)
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

    private def discoveryPayload(device: SessionInitDevice, payload: JsObject): DiscoveryPayload = {
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
        components = Map(
          "lid_locked" -> DiscoveryPayload.Component(
            platform = "binary_sensor",
            name = "Lid Locked",
            valueTemplate = "{{ value_json.lidLockState and 'OFF' or 'ON' }}",
            uniqueId = s"${BuildInfo.name}_${device.device_id}_lid_locked",
            deviceClass = Some("lock")
          ),
          "lid_open" -> DiscoveryPayload.Component(
            platform = "binary_sensor",
            name = "Lid Open",
            valueTemplate = "{{ value_json.lidOpenState and 'ON' or 'OFF' }}",
            uniqueId = s"${BuildInfo.name}_${device.device_id}_lid_open",
            deviceClass = Some("door")
          ),
          "bucket_missing" -> DiscoveryPayload.Component(
            platform = "binary_sensor",
            name = "Bucket Missing",
            valueTemplate = "{{ value_json.bucketMissing and 'ON' or 'OFF' }}",
            uniqueId = s"${BuildInfo.name}_${device.device_id}_bucket_missing",
            deviceClass = Some("problem")
          ),
          "child_lock" -> DiscoveryPayload.Component(
            platform = "binary_sensor",
            name = "Child Lock",
            valueTemplate = "{{ value_json.childLockEnabled and 'OFF' or 'ON' }}",
            uniqueId = s"${BuildInfo.name}_${device.device_id}_child_lock",
            deviceClass = Some("lock")
          ),
          "online" -> DiscoveryPayload.Component(
            platform = "binary_sensor",
            name = "Online",
            valueTemplate = "{{ value_json.online and 'ON' or 'OFF' }}",
            uniqueId = s"${BuildInfo.name}_${device.device_id}_online",
            deviceClass = Some("connectivity")
          ),
          "mass_in_bucket" -> DiscoveryPayload.Component(
            platform = "sensor",
            name = "Mass in Bucket",
            valueTemplate = "{{ value_json.massInBucket }}",
            uniqueId = s"${BuildInfo.name}_${device.device_id}_mass_in_bucket",
            deviceClass = Some("weight"),
            unitOfMeasurement = Some("lb"),
            suggestedDisplayPrecision = Some(2)
          ),
          "mass_added_since_bucket_empty" -> DiscoveryPayload.Component(
            platform = "sensor",
            name = "Mass Added Since Bucket Empty",
            valueTemplate = "{{ value_json.massAddedSinceBucketEmpty }}",
            uniqueId = s"${BuildInfo.name}_${device.device_id}_mass_added_since_bucket_empty",
            deviceClass = Some("weight"),
            unitOfMeasurement = Some("lb"),
            icon = Some("mdi:pail-plus"),
            suggestedDisplayPrecision = Some(2)
          ),
          "unprocessed_mass" -> DiscoveryPayload.Component(
            platform = "sensor",
            name = "Unprocessed Mass",
            valueTemplate = "{{ value_json.unprocessedMass }}",
            uniqueId = s"${BuildInfo.name}_${device.device_id}_unprocessed_mass",
            deviceClass = Some("weight"),
            unitOfMeasurement = Some("lb"),
            suggestedDisplayPrecision = Some(2)
          ),
          "bucket_fullness" -> DiscoveryPayload.Component(
            platform = "sensor",
            name = "Bucket Fullness",
            valueTemplate = "{{ value_json.bucketFullness }}",
            uniqueId = s"${BuildInfo.name}_${device.device_id}_bucket_fullness",
            icon = Some("mdi:delete-variant")
          ),
          "next_cycle_start_time" -> DiscoveryPayload.Component(
            platform = "sensor",
            name = "Next Cycle Start Time",
            valueTemplate = "{{ value_json.nextDgoCycleStartTime }}",
            uniqueId = s"${BuildInfo.name}_${device.device_id}_next_cycle_start_time",
            deviceClass = Some("timestamp")
          ),
          "next_cycle_duration" -> DiscoveryPayload.Component(
            platform = "sensor",
            name = "Next Cycle Duration",
            valueTemplate = "{{ value_json.nextDgoCycleDuration }}",
            uniqueId = s"${BuildInfo.name}_${device.device_id}_next_cycle_duration",
            deviceClass = Some("duration"),
            unitOfMeasurement = Some("s")
          ),
          "dry_and_grind" -> DiscoveryPayload.Component(
            platform = "switch",
            name = "Dry and Grind",
            valueTemplate =
              "{{ ((value_json.dgoCycle.desired or value_json.dgoCycle.reported) == 'DryGrind') and 'ON' or 'OFF' }}",
            uniqueId = s"${BuildInfo.name}_${device.device_id}_dry_and_grind",
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
