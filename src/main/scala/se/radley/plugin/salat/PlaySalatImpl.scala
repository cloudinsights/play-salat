package se.radley.plugin.salat

import javax.inject.Inject
import play.api._
import play.api.inject.ApplicationLifecycle
import com.mongodb.casbah._
import com.mongodb.{ MongoClientOptions, MongoException, ServerAddress, MongoOptions }
import com.mongodb.casbah.gridfs.GridFS
import scala.concurrent.Future

class PlaySalatImpl @Inject() (lifecycle: ApplicationLifecycle, environment: Environment, config: Configuration)
    extends PlaySalat {

  // previous content of Plugin.onStart
  sources.map { source =>
    environment.mode match {
      case Mode.Test =>
      case _ => {
        try {
          source._2.connection(source._2.dbName).getCollectionNames()
        } catch {
          case e: MongoException =>
            Logger("play").debug("error: " + e.printStackTrace)

            throw configuration.reportError(
              "mongodb." + source._1, "couldn't connect to [" + source._2.hosts.mkString(", ") + "]",
              Some(e))
        } finally {
          Logger("play").info("mongodb [" + source._1 + "] connected at " + source._2)
        }
      }
    }
  }

  lifecycle.addStopHook { () =>
    // previous contents of Plugin.onStop
    sources.map { source =>
      // @fix See if we can get around the plugin closing connections in testmode
      if (environment.mode != Mode.Test)
        source._2.reset()
    }
    Future.successful(())
  }

  lazy val configuration = config.getConfig("mongodb").getOrElse(Configuration.empty)

  lazy val sources: Map[String, MongoSource] = configuration.subKeys.map { sourceKey =>
    val source = configuration.getConfig(sourceKey).getOrElse(Configuration.empty)
    val options: Option[MongoClientOptions] = source.getConfig("options").flatMap(opts =>
      OptionsFromConfig(opts))

    source.getString("uri").map { str =>
      // MongoURI config - http://www.mongodb.org/display/DOCS/Connections
      val uri = MongoClientURI(str)
      val hosts = uri.hosts.map { host =>
        if (host.contains(':')) {
          val Array(h, p) = host.split(':')
          new ServerAddress(h, p.toInt)
        } else {
          new ServerAddress(host)
        }
      }.toList
      val db = uri.database.getOrElse(
        throw configuration.reportError(
          "mongodb." + sourceKey + ".uri",
          "db missing for source[" + sourceKey + "]"))
      val writeConcern = uri.options.getWriteConcern
      val user = uri.username
      val password = uri.password.map(_.mkString).filterNot(_.isEmpty)
      sourceKey -> MongoSource(hosts, db, writeConcern, user, password, options)
    }.getOrElse {
      val dbName = source.getString("db").getOrElse(
        throw configuration.reportError(
          "mongodb." + sourceKey + ".db",
          "db missing for source[" + sourceKey + "]"))

      // Simple config
      val host = source.getString("host").getOrElse("127.0.0.1")
      val port = source.getInt("port").getOrElse(27017)
      val user: Option[String] = source.getString("user")
      val password: Option[String] = source.getString("password")

      // Replica set config
      val hosts: List[ServerAddress] = source.getConfig("replicaset").map { replicaset =>
        replicaset.subKeys.map { hostKey =>
          val c = replicaset.getConfig(hostKey).get
          val host = c.getString("host").getOrElse(
            throw configuration.reportError(
              "mongodb." + sourceKey + ".replicaset",
              "host missing for replicaset in source[" + sourceKey + "]"))
          val port = c.getInt("port").getOrElse(27017)
          new ServerAddress(host, port)
        }.toList.reverse
      }.getOrElse(List.empty)

      val writeConcern =
        source.getString("writeconcern", Some(Set("fsyncsafe", "replicassafe", "safe", "normal")))
          .flatMap(WriteConcern.valueOf)
          .getOrElse(WriteConcern.Safe)

      // If there are replicasets configured go with those otherwise fallback to simple config
      if (hosts.isEmpty)
        sourceKey ->
          MongoSource(List(new ServerAddress(host, port)), dbName, writeConcern, user, password, options)
      else
        sourceKey -> MongoSource(hosts, dbName, writeConcern, user, password, options)
    }
  }.toMap

  /**
   * Returns the MongoSource that has been configured in application.conf
   * @param source The source name ex. default
   * @return A MongoSource
   */
  def source(source: String): MongoSource = {
    sources.get(source).getOrElse(
      throw configuration.reportError("mongodb." + source, source + " doesn't exist"))
  }

  /**
   * Returns MongoDB for configured source
   * @param sourceName The source name ex. default
   * @return A MongoDB
   */
  def db(sourceName: String = "default"): MongoDB = source(sourceName).db

  /**
   * Returns MongoCollection that has been configured in application.conf
   * @param collectionName The MongoDB collection name
   * @param sourceName The source name ex. default
   * @return A MongoCollection
   */
  def collection(collectionName: String, sourceName: String = "default"): MongoCollection =
    source(sourceName).collection(collectionName)

  /**
   * Returns Capped MongoCollection that has been configured in application.conf
   * @param collectionName The MongoDB collection name
   * @param size The capped collection size
   * @param max The capped collection max number of documents
   * @param sourceName The source name ex. default
   * @return A MongoCollection
   */
  def cappedCollection(collectionName: String,
                       size: Long,
                       max: Option[Long] = None,
                       sourceName: String = "default"): MongoCollection =
    source(sourceName).cappedCollection(collectionName, size, max)

  /**
   * Returns GridFS for configured source
   * @param bucketName The bucketName for the GridFS instance
   * @param sourceName The source name ex. default
   * @return A GridFS
   */
  def gridFS(bucketName: String = "fs", sourceName: String = "default"): GridFS =
    source(sourceName).gridFS(bucketName)
}