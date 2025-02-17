package millMqtt

import millMqtt.MillApiBehavior.SessionInitDevice
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import play.api.libs.json.{JsObject, Json}
import sttp.client3._
import sttp.model.HeaderNames
import sttp.ws.WebSocket

import scala.concurrent.Future

object MillWebSocketBehavior {
  sealed trait Message
  case object Terminate extends Message
  private case class WebSocketConnected(webSocket: WebSocket[Future]) extends Message
  private case class WebSocketPayload(payload: String) extends Message

  def apply(
      replyTo: ActorRef[(SessionInitDevice, JsObject)],
      device: SessionInitDevice,
      authToken: String
  ): Behavior[Message] = Behaviors.setup { context =>
    context.log.info("opening websocket connection for device: {}", device.device_id)
    val ws = basicRequest
      .get(uri"wss://websocket.cloud.api.mill.com/")
      .headers(
        Map(
          HeaderNames.Authorization -> authToken,
          "deviceId" -> device.device_id
        )
      )
      .response(asWebSocketAlwaysUnsafe[Future])
      .send(pekkohttp.PekkoHttpBackend())
    context.pipeToSelf(ws)(result => WebSocketConnected(result.get.body))
    waitingForWebSocket(replyTo, device)
  }

  private def waitingForWebSocket(
      replyTo: ActorRef[(SessionInitDevice, JsObject)],
      device: SessionInitDevice
  ): Behavior[Message] = Behaviors.receive {
    case (context, WebSocketConnected(webSocket)) =>
      context.log.info("websocket connected for device: {}", device.device_id)
      context.pipeToSelf(webSocket.receiveText())(_.map(WebSocketPayload(_)).get)
      ready(replyTo, device, webSocket)
    case (_, unexpectedMessage) =>
      throw new Exception(s"unexpected message: $unexpectedMessage")
  }

  private def ready(
      replyTo: ActorRef[(SessionInitDevice, JsObject)],
      device: SessionInitDevice,
      webSocket: WebSocket[Future]
  ): Behavior[Message] = Behaviors.receive {
    case (context, WebSocketPayload(payload)) =>
      val devicePayload = Json.parse(payload).as[JsObject]
      replyTo ! (device, devicePayload)
      context.pipeToSelf(webSocket.receiveText())(_.map(WebSocketPayload(_)).get)
      Behaviors.same

    case (_, Terminate) =>
      webSocket.close()
      Behaviors.stopped

    case (_, unexpectedMessage) =>
      throw new Exception(s"unexpected message: $unexpectedMessage")
  }
}
