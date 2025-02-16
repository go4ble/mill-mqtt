package millMqtt

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.eclipse.paho.mqttv5.client._
import org.eclipse.paho.mqttv5.common.packet.MqttProperties
import org.eclipse.paho.mqttv5.common.{MqttException, MqttMessage, MqttSubscription}

import java.util.UUID

object MqttBehavior {
  sealed trait Message
  private final case object Connected extends Message
  final case class Publish(topic: String, message: MqttMessage) extends Message
  final case class Subscribe(subscription: MqttSubscription) extends Message

  final case class MqttMessageEvent(topic: String, message: MqttMessage)

  def apply(replyTo: ActorRef[MqttMessageEvent]): Behavior[Message] = Behaviors.setup { context =>
    val mqttClient = new MqttAsyncClient(
      Config.MqttBroker,
      s"mill-mqtt-${UUID.randomUUID()}",
      new persist.MemoryPersistence
    )
    mqttClient.connect(
      null, // userContext
      new MqttActionListener {
        override def onSuccess(asyncActionToken: IMqttToken): Unit =
          context.self ! Connected

        override def onFailure(asyncActionToken: IMqttToken, exception: Throwable): Unit = {
          context.log.error("failed to connect", exception)
          throw exception
        }
      }
    )
    waitingForConnection(replyTo, mqttClient)
  }

  private def waitingForConnection(
      replyTo: ActorRef[MqttMessageEvent],
      mqttClient: MqttAsyncClient,
      pendingMessages: Seq[Message] = Nil
  ): Behavior[Message] = {
    Behaviors.receive {
      case (context, Connected) =>
        pendingMessages.foreach(context.self.tell)
        connected(replyTo, mqttClient)
      case (_, message) =>
        waitingForConnection(replyTo, mqttClient, pendingMessages :+ message)
    }
  }

  private def connected(replyTo: ActorRef[MqttMessageEvent], mqttClient: MqttAsyncClient): Behavior[Message] =
    Behaviors.setup { context =>
      require(mqttClient.isConnected)
      context.log.info("successfully connected to mqtt broker")
      mqttClient.setCallback(new MqttCallback {
        override def disconnected(disconnectResponse: MqttDisconnectResponse): Unit = {
          context.log.error("disconnected", disconnectResponse.getException)
          throw disconnectResponse.getException
        }

        override def mqttErrorOccurred(exception: MqttException): Unit = {
          context.log.error("mqtt error", exception)
          throw exception
        }

        override def messageArrived(topic: String, message: MqttMessage): Unit =
          replyTo ! MqttMessageEvent(topic, message)

        override def deliveryComplete(token: IMqttToken): Unit = ()
        override def connectComplete(reconnect: Boolean, serverURI: String): Unit = ()
        override def authPacketArrived(reasonCode: Int, properties: MqttProperties): Unit = ()
      })

      Behaviors.receiveMessage {
        case Publish(topic, message) =>
          mqttClient.publish(topic, message)
          Behaviors.same

        case Subscribe(subscription) =>
          mqttClient.subscribe(subscription)
          Behaviors.same

        case unexpectedMessage =>
          throw new Exception(s"unexpected message: $unexpectedMessage")
      }
    }
}
