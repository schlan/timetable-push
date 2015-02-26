package actors

import akka.actor.Actor
import model.{MergedTimetablePeriod, UiUserBundle}
import play.api.Logger
import provider.UserProvider
import scaldi.Injector
import scaldi.akka.AkkaInjectable
import services.WebUntisService
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import utils.{TimetableUtil, JsonUtil}

import scala.concurrent.Future

class DataFetcher(implicit inj: Injector) extends Actor with AkkaInjectable{

  val timetableService = inject[WebUntisService]
  val analystActor = injectActorRef[AnalystActor]
  val userProvider = inject[UserProvider]

  override def receive: Receive = {
    case user: UiUserBundle => doSomeStuff(user)
  }

  def doSomeStuff(uiBundle: UiUserBundle) = {
    val config = uiBundle.uiTimetableConfig
    Logger.info(s"load data: ${config}")
    timetableService.doAuthentication(config.url, config.school, config.userName, config.password).map{ auth =>
      val session = (auth.json \ "result" \ "sessionId").asOpt[String]
      Logger.info("auth: " + session.toString())
      session match {
        case Some(cookie) => {
          Future.sequence(TimetableUtil.getRequestDate().map(timetableService.getTimetable(config.url, s"JSESSIONID=${cookie}", config.elementType, config.elmentId, _))
          ).map{ data =>

            val results = data.map(r => JsonUtil.parseTimetableResponse(r.body).get)    //TODO possible error
            Logger.info(results.toString())

            val irregularLesson = results.map( d => TimetableUtil.getIrregularEvents(d.data)).flatten
            val elements = results.map(d => d.data.elements).flatten.distinct

            analystActor ! (irregularLesson.map(d => MergedTimetablePeriod(d, elements)), uiBundle)
          }
        }
        case None => {
          Logger.error(s"Error auth: ${uiBundle.uiUser.email}")
          userProvider.setUserBundleFailed(uiBundle)
        }
      }
    }
  }

}
