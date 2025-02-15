package millMqtt

import millMqtt.MillApiBehavior.SessionInitDevice
import org.apache.pekko.actor.typed.{ActorSystem, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import play.api.libs.json.{JsObject, Json}

import scala.concurrent.Await
import scala.concurrent.duration.Duration

object ApiDemoApp extends scala.App {
  private sealed trait Message
  private final case class ReceivedPayload(device: SessionInitDevice, payload: JsObject) extends Message

  private object ApiDemoAppBehavior {
    def apply(): Behavior[Message] = Behaviors.setup { context =>
      val messageAdapter = context.messageAdapter[(SessionInitDevice, JsObject)] { case (device, payload) =>
        ReceivedPayload(device, payload)
      }
      val millApi = context.spawn(MillApiBehavior(messageAdapter, Config.MillEmail, Config.MillPassword), "mill-api")
      context.watch(millApi)

      Behaviors.receiveMessage { case ReceivedPayload(device, payload) =>
        println(s"${device.nickname.getOrElse(device.device_id)}:")
        println(Json.prettyPrint(payload))
        Behaviors.stopped
      }
    }
  }

  private val actorSystem = ActorSystem(ApiDemoAppBehavior(), "demo-app")
  Await.result(actorSystem.whenTerminated, Duration.Inf)
  sys.exit()
}
