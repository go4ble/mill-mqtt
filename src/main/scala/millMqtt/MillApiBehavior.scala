package millMqtt

import com.auth0.jwt.JWT
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import play.api.libs.json.{JsObject, Json, OWrites, Reads}
import sttp.client3._
import sttp.client3.playJson._

import java.time.Instant
import scala.annotation.unused
import scala.concurrent.duration._
import scala.util.{Success, Try}

object MillApiBehavior {
  sealed trait Message
  private final case class AuthResponse(token: String) extends Message
  private implicit val authResponseReads: Reads[AuthResponse] = Json.reads
  final case class SessionInitDevice(device_id: String, nickname: Option[String])
  @unused private implicit val sessionInitDeviceReads: Reads[SessionInitDevice] = Json.reads
  private final case class SessionInitResponse(devices: Seq[SessionInitDevice], authToken: String) extends Message
  private implicit val sessionInitResponseReads: Reads[SessionInitResponse] = Json.reads
  private final case class ApiResponse(device: SessionInitDevice, payload: Try[Response[JsObject]]) extends Message
  private final case object RefreshDevices extends Message

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
        waitingForDevices(replyTo, pendingMessages)
      case (_, message) =>
        waitingForAuthResponse(replyTo, pendingMessages :+ message)
    }
  }

  private def waitingForDevices(
      replyTo: ActorRef[(SessionInitDevice, Device)],
      pendingMessages: Seq[Message]
  ): Behavior[Message] = Behaviors.withTimers { scheduler =>
    Behaviors.receive {
      case (context, SessionInitResponse(devices, authToken)) =>
        val decodedToken = JWT.decode(authToken)
        val expiresAt = decodedToken.getExpiresAtAsInstant.getEpochSecond
        val expiresIn = expiresAt - Instant.now().getEpochSecond
        scheduler.startSingleTimer(RefreshDevices, (expiresIn * 9 / 10).seconds)
        devices.foreach { device =>
          val webSocketActor =
            context.spawn(MillWebSocketBehavior(replyTo, device, authToken), s"ws-${device.device_id}")
          context.watch(webSocketActor)
        }
        pendingMessages.foreach(context.self.tell)
        ready(replyTo, authToken)

      case (_, message) =>
        waitingForDevices(replyTo, pendingMessages :+ message)
    }
  }

  private def ready(replyTo: ActorRef[(SessionInitDevice, Device)], authToken: String): Behavior[Message] =
    Behaviors.receive {
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

      case (context, RefreshDevices) =>
        context.children.foreach(context.unwatch)
        context.children.foreach(_.unsafeUpcast[MillWebSocketBehavior.Message] ! MillWebSocketBehavior.Terminate)
        context.self ! AuthResponse(authToken)
        waitingForAuthResponse(replyTo)

      case (_, unexpectedMessage) =>
        throw new Exception(s"unexpected message: $unexpectedMessage")
    }

  private case class AuthRequest(email: String, password: String)
  private implicit val authRequestWrites: OWrites[AuthRequest] = Json.writes
}
