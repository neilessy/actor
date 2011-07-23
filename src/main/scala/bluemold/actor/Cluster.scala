package bluemold.actor

import java.io._
import java.util.concurrent.ConcurrentHashMap

/**
 * Cluster<br/>
 * Author: Neil Essy<br/>
 * Created: 5/29/11<br/>
 * <p/>
 * [Description]
 */

object Cluster {
  @volatile var defaultCluster: Cluster = null

  def setDefaultCluster( cluster: Cluster ) { // able to set only once
    if ( defaultCluster == null ) {
      synchronized {
        if ( defaultCluster == null ) {
          defaultCluster = cluster
        }
      }
    }
  }

  implicit def getDefaultCluster: Cluster = { // will create it if not set
    if ( defaultCluster == null ) {
      synchronized {
        if ( defaultCluster == null ) {
          defaultCluster = UDPCluster.getCluster( "bluemold", "default" )
        }
      }
    }
    defaultCluster
  }
}

trait Cluster {
  def startup()
  def shutdown()
  def getAppName: String
  def getGroupName: String

  private[actor] def send( clusterId: ClusterIdentity, message:ClusterMessage )

  def send( uuid: UUID, msg: Any, sender: UUID )

  def send( uuid: UUID, msg: Any )( implicit sender: ActorRef )

  def sendAll( clusterId: ClusterIdentity, className: String, msg: Any )( implicit sender: ActorRef )

  def sendAllWithId( clusterId: ClusterIdentity, id: String, msg: Any )( implicit sender: ActorRef )

  def sendAll( msg: Any )(implicit sender: ActorRef) { sendAll( null, null, msg ) }

  def sendAll(className: String, msg: Any)(implicit sender: ActorRef) { sendAll( null, className, msg ) }

  def sendAll(clusterId: ClusterIdentity, msg: Any)(implicit sender: ActorRef) { sendAll( clusterId, null, msg ) }

  def sendAllWithId(id: String, msg: Any)(implicit sender: ActorRef) { sendAllWithId( null, id, msg ) }

  val _clusterId = UUID.getClusterIdentity( getAppName )
  def getClusterId: ClusterIdentity = _clusterId

  val registry = ClusterIdentity.getRegistry( getClusterId )

  val byClassName = new ConcurrentHashMap[String,ConcurrentHashMap[LocalActorRef,BaseRegisteredActor]]
  val byId = new ConcurrentHashMap[String,ConcurrentHashMap[LocalActorRef,BaseRegisteredActor]]
  val byUUID = new ConcurrentHashMap[UUID,BaseRegisteredActor]

  def register( registeredActor: BaseRegisteredActor ) { registry.register( registeredActor ) }
  def unRegister( registeredActor: BaseRegisteredActor ) { registry.unRegister( registeredActor ) }
  def getAllByClassName( className: String ) = registry.getAllByClassName( className )
  def getAll() = registry.getAll()
  def getAllById( id: String ) = registry.getAllById( id )
  def getByUUID( uuid: UUID ) = registry.getByUUID( uuid )
}



class ClusterAddress extends Serializable

class ClusterMessage extends Serializable

class IdentityClusterMessage extends ClusterMessage {
  var identity: ClusterIdentity = null
  var address: ClusterAddress = null
} 

case class ActorClusterMessage( recipient: UUID, msg: Any, sender: UUID ) extends ClusterMessage
case class ActorClusterAllMessage( clusterId: ClusterIdentity, className: String, msg: Any, sender: UUID ) extends ClusterMessage
case class ActorClusterMessageById( clusterId: ClusterIdentity, id: String, msg: Any, sender: UUID ) extends ClusterMessage
case class StopActorClusterMessage( recipient: UUID, sender: UUID ) extends ClusterMessage
case class StatusRequestClusterMessage( recipient: UUID, sender: UUID ) extends ClusterMessage
case class StatusResponseClusterMessage( recipient: UUID, sender: UUID, stopped: Boolean ) extends ClusterMessage
