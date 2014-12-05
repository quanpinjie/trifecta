package com.ldaniels528.trifecta.rest

import java.io.File

import akka.actor.{ActorSystem, Props}
import com.ldaniels528.trifecta.TxConfig
import com.ldaniels528.trifecta.io.zookeeper.ZKProxy
import com.ldaniels528.trifecta.rest.EmbeddedWebServer._
import com.ldaniels528.trifecta.rest.PushEventActor._
import com.ldaniels528.trifecta.util.ResourceHelper._
import com.typesafe.config.ConfigFactory
import org.mashupbots.socko.infrastructure.Logger
import org.mashupbots.socko.routes._
import org.mashupbots.socko.webserver.{WebServer, WebServerConfig}
import org.slf4j.LoggerFactory

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.util.Try

/**
 * Embedded Web Server
 * @author Lawrence Daniels <lawrence.daniels@gmail.com>
 */
class EmbeddedWebServer(config: TxConfig, zk: ZKProxy) extends Logger {
  private lazy val logger = LoggerFactory.getLogger(getClass)
  private val actorSystem = ActorSystem("EmbeddedWebServer", ConfigFactory.parseString(actorConfig))
  private val facade = new KafkaRestFacade(config, zk)
  private val sessions = TrieMap[String, String]()

  // define all of the routes
  val routes = Routes({
    case HttpRequest(request) => wcActor ! request
    case WebSocketHandshake(wsHandshake) => wsHandshake match {
      case Path("/websocket/") =>
        logger.info(s"Authorizing websocket handshake...")
        wsHandshake.authorize(
          onComplete = Option(onWebSocketHandshakeComplete),
          onClose = Option(onWebSocketClose))
    }
    case WebSocketFrame(wsFrame) => wsActor ! wsFrame
  })

  // create the web service instance
  private val webServer = new WebServer(WebServerConfig(hostname = config.webHost, port = config.webPort), routes, actorSystem)

  // create the web content actors
  private var wcRouter = 0
  private val wcActors = (1 to config.queryConcurrency) map (_ => actorSystem.actorOf(Props(new WebContentActor(facade))))

  // create the web socket actors
  private var wsRouter = 0
  private val wsActors = (1 to config.queryConcurrency) map (_ => actorSystem.actorOf(Props(new WebSocketActor(facade))))

  // create the push event actors
  private var pushRouter = 0
  private val pushActors = (1 to 2) map (_ => actorSystem.actorOf(Props(new PushEventActor(webServer, facade))))

  // create the actor references
  private def wcActor = wcActors(wcRouter % wcActors.length) and (_ => wcRouter += 1)

  private def wsActor = wsActors(wsRouter % wsActors.length) and (_ => wsRouter += 1)

  private def pushActor = pushActors(pushRouter % pushActors.length) and (_ => pushRouter += 1)

  Runtime.getRuntime.addShutdownHook(new Thread {
    override def run() = {
      EmbeddedWebServer.this.stop()
    }
  })

  /**
   * Starts the embedded app server
   */
  def start() {
    webServer.start()

    // setup event management
    implicit val ec = actorSystem.dispatcher
    actorSystem.scheduler.schedule(initialDelay = 5.seconds, interval = config.consumerPushInterval.seconds, pushActor, PushConsumers)
    actorSystem.scheduler.schedule(initialDelay = 5.seconds, interval = config.topicPushInterval.seconds, pushActor, PushTopics)
  }

  /**
   * Stops the embedded app server
   */
  def stop() {
    Try(webServer.stop())
    ()
  }

  private def onWebSocketHandshakeComplete(webSocketId: String) {
    logger.info(s"Web Socket $webSocketId connected")
    sessions += webSocketId -> webSocketId // TODO do we need to a session instance for tracking?
  }

  private def onWebSocketClose(webSocketId: String) {
    logger.info(s"Web Socket $webSocketId closed")
    sessions -= webSocketId
  }

}

/**
 * Embedded Web Server Singleton
 * @author Lawrence Daniels <lawrence.daniels@gmail.com>
 */
object EmbeddedWebServer {
  private[this] val logger = LoggerFactory.getLogger(getClass)

  private val actorConfig = """
      my-pinned-dispatcher {
        type=PinnedDispatcher
        executor=thread-pool-executor
      }
      akka {
        event-handlers = ["akka.event.slf4j.Slf4jEventHandler"]
        loglevel=DEBUG
        actor {
          deployment {
            /static-file-router {
              router = round-robin
              nr-of-instances = 5
            }
            /file-upload-router {
              router = round-robin
              nr-of-instances = 5
            }
          }
        }
      }"""

  /**
   * Trifecta Web Configuration
   * @param config the given [[TxConfig]]
   */
  implicit class TxWebConfig(val config: TxConfig) extends AnyVal {

    /**
     * Returns the push interval (in seconds) for topic changes
     * @return the interval
     */
    def topicPushInterval: Int = config.getOrElse("trifecta.rest.push.interval.topic", "15").toInt

    /**
     * Returns the push interval (in seconds) for consumer offset changes
     * @return the interval
     */
    def consumerPushInterval: Int = config.getOrElse("trifecta.rest.push.interval.consumer", "15").toInt

    /**
     * Returns the location of the queries file
     * @return the [[File]] representing the location of queries file
     */
    def queriesFile: File = new File(TxConfig.trifectaPrefs, "queries.txt")

    /**
     * Returns the query execution concurrency
     * @return the query execution concurrency
     */
    def queryConcurrency: Int = config.getOrElse("trifecta.query.concurrency", "10").toInt

  }

}
