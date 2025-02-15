package millMqtt

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import play.api.libs.json.{JsObject, Json, OWrites, Reads}
import sttp.client3._
import sttp.client3.playJson._
import sttp.model.HeaderNames
import sttp.ws.WebSocket

import scala.annotation.unused
import scala.concurrent.Future
import scala.util.{Success, Try}

object MillApiBehavior {
  sealed trait Message
  private final case class AuthResponse(token: String) extends Message
  private implicit val authResponseReads: Reads[AuthResponse] = Json.reads
  final case class SessionInitDevice(device_id: String, nickname: Option[String])
  @unused private implicit val sessionInitDeviceReads: Reads[SessionInitDevice] = Json.reads
  private final case class SessionInitResponse(devices: Seq[SessionInitDevice]) extends Message
  private implicit val sessionInitResponseReads: Reads[SessionInitResponse] = Json.reads
  private final case class WebSocketPayload(
      device: SessionInitDevice,
      webSocket: WebSocket[Future],
      payload: Option[String] = None
  ) extends Message
  private final case class ApiResponse(device: SessionInitDevice, payload: Try[Response[JsObject]]) extends Message

  final case class SetCycle(device: SessionInitDevice, cycle: String) extends Message

  private val sttpBackend = pekkohttp.PekkoHttpBackend()

  private type Device = JsObject

  def apply(
      replyTo: ActorRef[(SessionInitDevice, Device)],
      millEmail: String,
      millPassword: String
  ): Behavior[Message] = Behaviors.setup { context =>
    context.log.info("authenticating")
    val authResponse = basicRequest
      .post(uri"https://api.mill.com/app/v1/tokens")
      .body(AuthRequest(millEmail, millPassword))
      .response(asJson[AuthResponse].getRight)
      .send(sttpBackend)
    context.pipeToSelf(authResponse)(_.get.body)
    waitingForAuthResponse(replyTo)
  }

  private def waitingForAuthResponse(
      replyTo: ActorRef[(SessionInitDevice, Device)],
      pendingMessages: Seq[Message] = Nil
  ): Behavior[Message] = {
    Behaviors.receive {
      case (context, AuthResponse(authToken)) =>
        context.log.info("session init")
        val sessionInitResponse = basicRequest
          .get(uri"https://cloud.api.mill.com/v1/session_init?refresh_token=true")
          .auth
          .bearer(authToken)
          .response(playJson.asJson[SessionInitResponse].getRight)
          .send(sttpBackend)
        context.pipeToSelf(sessionInitResponse)(_.get.body)
        waitingForDevices(replyTo, authToken, pendingMessages)
      case (_, message) =>
        waitingForAuthResponse(replyTo, pendingMessages :+ message)
    }
  }

  private def waitingForDevices(
      replyTo: ActorRef[(SessionInitDevice, Device)],
      authToken: String,
      pendingMessages: Seq[Message]
  ): Behavior[Message] = Behaviors.receive {
    case (context, SessionInitResponse(devices)) =>
      devices.foreach { device =>
        context.log.info(s"opening websocket connection for device: ${device.device_id}")
        val ws = basicRequest
          .get(uri"wss://websocket.cloud.api.mill.com/")
          .headers(
            Map(
              HeaderNames.Authorization -> authToken,
              "deviceId" -> device.device_id
            )
          )
          .response(asWebSocketAlwaysUnsafe[Future])
          .send(sttpBackend)
        context.pipeToSelf(ws)(result => WebSocketPayload(device, result.get.body))
      }
      pendingMessages.foreach(context.self.tell)
      ready(replyTo, authToken)

    case (_, message) =>
      waitingForDevices(replyTo, authToken, pendingMessages :+ message)
  }

  // TODO automatically refresh auth token
  private def ready(replyTo: ActorRef[(SessionInitDevice, Device)], authToken: String): Behavior[Message] =
    Behaviors.receive {
      case (context, WebSocketPayload(device, ws, payloadOpt)) =>
        payloadOpt.foreach { payload =>
          val devicePayload = Json.parse(payload).as[Device]
          replyTo ! (device, devicePayload)
        }
        context.pipeToSelf(ws.receiveText())(p => WebSocketPayload(device, ws, p.toOption))
        Behaviors.same

      case (context, SetCycle(device, cycle)) =>
        val setCycleResponse = basicRequest
          .post(uri"https://cloud.api.mill.com/v1/device_settings/${device.device_id}")
          .auth
          .bearer(authToken)
          .body(Json.obj("settings" -> Json.obj("dgoCycle" -> cycle)))
          .response(playJson.asJson[JsObject].getRight)
          .send(sttpBackend)
        context.pipeToSelf(setCycleResponse)(ApiResponse(device, _))
        Behaviors.same

      case (_, ApiResponse(device, Success(response))) =>
        replyTo ! (device, response.body)
        Behaviors.same

      case (_, unexpectedMessage) =>
        throw new Exception(s"unexpected message: $unexpectedMessage")
    }

  private case class AuthRequest(email: String, password: String)
  private implicit val authRequestWrites: OWrites[AuthRequest] = Json.writes

}
