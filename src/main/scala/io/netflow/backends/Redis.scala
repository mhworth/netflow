package io.netflow.backends

import io.netflow.flows._
import io.wasted.util._

import org.joda.time.DateTime
import com.lambdaworks.redis._
import scala.collection.immutable.HashMap
import scala.collection.JavaConverters._

import java.util.UUID
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong
import io.netflow.lib.{ NetFlowInetPrefix, Storage, NodeConfig }

private[netflow] class Redis extends Storage {
  private val client = {
    val host = NodeConfig.values.redis.host
    val port = NodeConfig.values.redis.port
    debug(s"Opening new connection to $host:$port")
    new RedisClient(host, port)
  }

  private val session = client.connect()
  //private val redisAsyncConnection = client.connectAsync()

  def save(flowData: Map[(String, String), AtomicLong], sender: InetSocketAddress) {
    val senderIP = sender.getAddress.getHostAddress
    val senderPort = sender.getPort
    val prefix = "netflow:" + senderIP + "/" + senderPort

    flowData foreach {
      case ((hash, name), value) => session.hincrby(prefix + ":" + hash, name, value.get)
    }
  }

  def ciscoTemplateFields(sender: InetSocketAddress, id: Int): Option[HashMap[String, Int]] = {
    val (ip, port) = (sender.getAddress.getHostAddress, sender.getPort)
    var fields = HashMap[String, Int]()
    session.hgetall("template:" + ip + "/" + port + ":" + id).asScala foreach { field =>
      Tryo(fields ++= Map(field._1 -> field._2.toInt))
    }
    if (fields.size == 0) None else Some(fields)
  }

  def save(tmpl: cflow.Template) {
    val (ip, port) = (tmpl.sender.getAddress.getHostAddress, tmpl.sender.getPort)
    val key = "template:" + ip + "/" + port + ":" + tmpl.id
    session.del(key)
    session.hmset(key, tmpl.objectMap.asJava)
  }

  def countDatagram(date: DateTime, sender: InetSocketAddress, kind: String, flowsPassed: Int = 0) {
    val senderAddr = sender.getAddress.getHostAddress + "/" + sender.getPort
    session.hincrby("stats:" + senderAddr, kind, 1)
    session.hset("stats:" + senderAddr, "last", date.getMillis.toString)
  }

  def acceptFrom(sender: InetSocketAddress): Option[InetSocketAddress] = {
    val (ip, port) = (sender.getAddress.getHostAddress, sender.getPort)
    if (session.sismember("senders", ip + "/" + port)) return Some(sender)
    if (session.sismember("senders", ip + "/0")) return Some(new InetSocketAddress(sender.getAddress, 0))
    None
  }

  def getThruputPrefixes(sender: InetSocketAddress): List[NetFlowInetPrefix] = {
    val (ip, port) = (sender.getAddress.getHostAddress, sender.getPort)
    session.smembers("thruput:" + ip + "/" + port).asScala.toList.flatMap(getPrefix)
  }

  def getThruputPlatform(id: String): Option[ThruputPlatform] = {
    Tryo(UUID.fromString(id)) match {
      case Some(uuid) =>
        val map = session.hgetall("thruput:" + id).asScala
        if (map.size == 0) return None
        for {
          url <- map.get("url")
          auth <- map.get("auth")
          sign <- map.get("sign")
          platform <- Tryo(ThruputPlatform(url, auth, sign))
        } yield platform
      case _ => None
    }
  }

  def getThruputRecipients(sender: InetSocketAddress, prefix: NetFlowInetPrefix): List[ThruputRecipient] = {
    val (ip, port) = (sender.getAddress.getHostAddress, sender.getPort)
    session.smembers("thruput:" + ip + "/" + port + ":" + prefix.toString).asScala.toList flatMap { rcpt =>
      val split = rcpt.split(":", 2)
      split.length match {
        case 1 => getThruputPlatform(split(0)).map(pf => ThruputRecipient(pf))
        case 2 =>
          getThruputPlatform(split(0)) match {
            case Some(platform) if split(1).trim.length == 0 => // broadcast
              Some(ThruputRecipient(platform))
            case Some(platform) => // to user
              Some(ThruputRecipient(platform, Some(split(1))))
            case None => info("Thruput Platform " + split(0) + " could not be found"); None
          }
      }
    }
  }

  def getPrefixes(sender: InetSocketAddress): List[NetFlowInetPrefix] = {
    val (ip, port) = (sender.getAddress.getHostAddress, sender.getPort)
    session.smembers("sender:" + ip + "/" + port).asScala.toList.flatMap(getPrefix)
  }

  def stop() {
    session.close()
  }
}
