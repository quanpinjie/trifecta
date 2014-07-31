package com.ldaniels528.verify

import com.ldaniels528.verify.subsystems.kafka.Broker
import com.ldaniels528.verify.io.EndPoint
import com.ldaniels528.verify.subsystems.zookeeper.ZKProxy
import org.apache.zookeeper.{ Watcher, WatchedEvent }

/**
 * Verify Shell Runtime Context
 * @author lawrence.daniels@gmail.com
 */
class VerifyShellRuntime(props: java.util.Properties) {
  // the default state of the console is "alive"
  var alive = true

  // get the ZooKeeper host/port
  val zkEndPoint = EndPoint(props.getProperty("zookeeper"))

}

/**
 * Verify Shell Runtime Singleton
 * @author lawrence.daniels@gmail.com
 */
object VerifyShellRuntime {

  def getInstance(host: String): VerifyShellRuntime = {
    val p = new java.util.Properties()
    p.setProperty("zookeeper", s"$host:2181")
    new VerifyShellRuntime(p)
  }

}