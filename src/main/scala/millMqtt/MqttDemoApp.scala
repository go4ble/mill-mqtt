package millMqtt

import millMqtt.MqttBehavior.{MqttMessageEvent, Publish, Subscribe}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorSystem, Behavior}
import org.eclipse.paho.mqttv5.common.{MqttMessage, MqttSubscription}

import scala.concurrent.Await
import scala.concurrent.duration.Duration

object MqttDemoApp extends scala.App {

  private object MqttDemoAppBehavior {
    sealed trait Message
    private final case class MqttMessageReceived(topic: String, message: MqttMessage) extends Message

    def apply(): Behavior[Message] = Behaviors.setup { context =>
      val messageAdapter = context.messageAdapter[MqttMessageEvent](e => MqttMessageReceived(e.topic, e.message))
      val mqtt = context.spawn(MqttBehavior(messageAdapter), "mqtt")
      context.watch(mqtt)

      mqtt ! Subscribe(new MqttSubscription("#"))
      mqtt ! Publish("hello/world", new MqttMessage("My name is World!".getBytes))

      Behaviors.receiveMessage { case MqttMessageReceived(topic, message) =>
        println(s"$topic:")
        println(new String(message.getPayload))
        Behaviors.same
      }
    }
  }

  private val actorSystem = ActorSystem(MqttDemoAppBehavior(), "mqtt-demo-app")
  Await.result(actorSystem.whenTerminated, Duration.Inf)
  sys.exit()
}
