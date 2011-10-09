package bluemold.actor

import collection.immutable.HashMap
import java.net._
import annotation.tailrec
import bluemold.concurrent.{CancelableQueue, AtomicBoolean}
import java.io._
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit, ConcurrentHashMap}

case class LocalClusterIdentity( appName: String, groupName: String )

object UDPCluster {
  private[actor] val broadcastPort = 9900
  private[actor] val minPort = 9901
  private[actor] val maxPort = 9999

  private[actor] val sendingChunkSize = 1024.shortValue()
  private[actor] val maxMissingList = 256

  val clusters = new ConcurrentHashMap[LocalClusterIdentity,UDPCluster]

  def getCluster( appName: String, groupName: String ) = {
    val localId = LocalClusterIdentity( appName, groupName )
    val cluster = clusters.get( localId )
    if ( cluster == null ) {
      val cluster = new UDPCluster( localId )
      val oldCluster = clusters.putIfAbsent( localId, cluster )
      if ( oldCluster == null ) { cluster.startup(); cluster }
      else oldCluster
    } else cluster
  }
}

case class UDPAddress( address: InetAddress, port: Int )

final class UDPCluster( localId: LocalClusterIdentity ) extends Cluster {
  import UDPCluster._

  val done = new AtomicBoolean()

  val pollTimeout = 200
  val waitingForReceiptTimeout = 1000
  val maxReceiptWaits = 3
  val waitingAfterReceiptTimeout = 1000
  val sent = new ConcurrentHashMap[UUID,SendingMessage]
  val sentToProcess = new LinkedBlockingQueue[SendingMessage]
  val sentInWaiting = new CancelableQueue[SendingMessage]
  val sentCompleted = new LinkedBlockingQueue[SendingMessage]
  
  val waitingForAllChunksTimeout = 1000
  val maxChunkWaits = 3
  val waitingAfterCompleteTimeout = 6000
  val received = new ConcurrentHashMap[UUID,ReceivingMessage]
  val receivedInWaiting = new CancelableQueue[ReceivingMessage]
  val receivedCompleted = new LinkedBlockingQueue[ReceivingMessage]
  
  private def startSendingMessage( message: SendingMessage ) { 
    sent.put( message.uuid, message )
    sentToProcess.add( message )
  }

  private def startReceivingMessage( message: ReceivingMessage ): ReceivingMessage = {
    val oldMessage = received.putIfAbsent( message.uuid, message )
    if ( oldMessage == null ) {
      message.startWaiting()
      message
    } else oldMessage
  }

  private def getSendingMessage( uuid: UUID ) = sent.get( uuid )  
  private def getReceivingMessage( uuid: UUID ) = received.get( uuid )  

  @volatile var sockets: HashMap[InterfaceAddress,(DatagramSocket,DatagramSocket,InetAddress)] = _

  private def getSockets = {
    if ( sockets == null ) {
      synchronized {
        if ( sockets == null ) {
          sockets = getSockets0( NetworkInterface.getNetworkInterfaces )
        }
      }
    }
    sockets
  }

  private def getSockets0( interfaces: java.util.Enumeration[NetworkInterface] ): HashMap[InterfaceAddress,(DatagramSocket,DatagramSocket,InetAddress)] = {
    var map = new HashMap[InterfaceAddress,(DatagramSocket,DatagramSocket,InetAddress)]()
    while ( interfaces.hasMoreElements ) {
      val interface = interfaces.nextElement()
      if ( ! interface.isLoopback ) {
        val addresses = interface.getInterfaceAddresses.iterator() 
        while ( addresses.hasNext ) {
          val address = addresses.next()
          val socket = getSocket( minPort, address )
          if ( socket != null ) {
            val broadcastSocket = new DatagramSocket( null: SocketAddress )
            broadcastSocket.setReuseAddress( true )
            broadcastSocket.bind( new InetSocketAddress( address.getAddress, broadcastPort ) )

            val broadcastReceiver = new Thread( new Receiver( broadcastSocket ), "UDPCluster-" + getAppName + "-Broadcast-" + address.getAddress + ":" + broadcastPort )
            broadcastReceiver.setDaemon( true )
            broadcastReceiver.start()

            val socketReceiver = new Thread( new Receiver( socket ), "UDPCluster-" + getAppName + "-Receiver-" + address.getAddress + ":" + socket.getLocalPort )
            socketReceiver.setDaemon( true )
            socketReceiver.start()

            map += ((address,((socket,broadcastSocket,address.getBroadcast))))
          }
        }
      }
    }
    map
  }

  private def getSocket( port: Int, address: InterfaceAddress ): DatagramSocket = {
    if( port >= minPort && port <= maxPort ) {
      try {
        val socket = new DatagramSocket( port, address.getAddress )
        socket.setBroadcast( true )
        socket
      } catch { case _ => getSocket( port + 1, address ) }
    } else null
  }

  def getAppName: String = localId.appName
  def getGroupName: String = localId.groupName

  val addressToId: ConcurrentHashMap[UDPAddress,ClusterIdentity] = new ConcurrentHashMap[UDPAddress,ClusterIdentity]
  val idToAddresses: ConcurrentHashMap[ClusterIdentity,List[UDPAddress]] = new ConcurrentHashMap[ClusterIdentity, List[UDPAddress]]

  def getDestination( clusterId: ClusterIdentity ): Option[(UDPAddress,DatagramSocket)] = {
    val target: UDPAddress = if ( clusterId != null ) {
      val addys = idToAddresses.get( clusterId )
      addys match {
        case head :: tail => head
        case Nil => null
        case null => null
      }
    } else null
    
    if ( target != null ) {
      val socket = getSocketForTarget( target )
      if ( socket != null ) Some((target,socket)) else None
    } else None
  } 

  def getSocketForTarget( target: UDPAddress ): DatagramSocket = {
    var socket: DatagramSocket = null
    val sockets = getSockets
    for ( address <- sockets.keys ) {
      if ( socket == null ) {
        val targetBytes = target.address.getAddress
        val interfaceBytes = address.getAddress.getAddress
        var prefixLen: Int = address.getNetworkPrefixLength
        if ( targetBytes.length == interfaceBytes.length ) {
          var same = true
          for ( i <- 0 until targetBytes.length ) {
            if ( prefixLen >= 8 ) {
              if ( targetBytes(i) != interfaceBytes(i) )
                same = false
              prefixLen -= 8
            } else if ( prefixLen > 0 ) {
              if ( ( targetBytes(i) >>> ( 8 - prefixLen ) ) != ( interfaceBytes(i) >>> ( 8 - prefixLen ) ) )
                same == false
              prefixLen = 0
            }
          }
          if ( same ) {
            sockets.get(address) match {
              case Some( ( aSocket, aBroadcast, bAddress ) ) => socket = aSocket
              case None =>
            }
          }
        }
      }
    }
    socket
  } 

  def startup() {
    getSockets
    val sender = new Thread( new Sender(), "UDPCluster-" + getAppName + "-Sender" )
    sender.setDaemon( true )
    sender.start()
    val sentWaiting = new Thread( new SentWaitingProcessor(), "UDPCluster-" + getAppName + "-Sent-Waiting" )
    sentWaiting.setDaemon( true )
    sentWaiting.start()
    val sentCleaner = new Thread( new SentCompletedCleaner(), "UDPCluster-" + getAppName + "-Sent-Completed" )
    sentCleaner.setDaemon( true )
    sentCleaner.start()
    val receivedWaiting = new Thread( new ReceivedWaitingProcessor(), "UDPCluster-" + getAppName + "-Received-Waiting" )
    receivedWaiting.setDaemon( true )
    receivedWaiting.start()
    val receivedCleaner = new Thread( new ReceivedCompletedCleaner(), "UDPCluster-" + getAppName + "-Received-Completed" )
    receivedCleaner.setDaemon( true )
    receivedCleaner.start()
  }

  def shutdown() {
    done.set( true )
    clusters.remove( localId, this )
    for ( (socket,broadcast,bAddress) <- getSockets.values ) {
      try {
        socket.close()
      } catch { case t: Throwable => t.printStackTrace() }
      try {
        broadcast.close()
      } catch { case t: Throwable => t.printStackTrace() }
    }
  }

  private def send( clusterId: ClusterIdentity, out: ByteArrayOutputStream ) {
    val bytes = out.toByteArray
    val msg = new SendingMessage( bytes, clusterId, sendingChunkSize )
    startSendingMessage( msg )
  } 

  
  
  def send( clusterId: ClusterIdentity, message:ClusterMessage ) {
    val out = new ByteArrayOutputStream
    val objectOut = new ObjectOutputStream( out )
    objectOut.writeObject( message )
    objectOut.flush()
    send( clusterId, out )
  }

  def send(uuid: UUID, msg: Any, sender: UUID ) {
    send( uuid.clusterId, ActorClusterMessage( uuid, msg, sender ) )
  }

  def send(uuid: UUID, msg: Any)(implicit sender: ActorRef) {
    val localActorRef = if ( sender.isInstanceOf[LocalActorRef] ) sender.asInstanceOf[LocalActorRef] else null
    if ( localActorRef != null )
      send( uuid, msg, localActorRef._getUUID )
  }

  def sendAll(clusterId: ClusterIdentity, className: String, msg: Any)(implicit sender: ActorRef) {
    val localActorRef = if ( sender.isInstanceOf[LocalActorRef] ) sender.asInstanceOf[LocalActorRef] else null
    if ( localActorRef != null )
      send( clusterId, ActorClusterAllMessage( clusterId, className, msg, localActorRef._getUUID ) )
  }

  def sendAllWithId(clusterId: ClusterIdentity, id: String, msg: Any)(implicit sender: ActorRef) {
    val localActorRef = if ( sender.isInstanceOf[LocalActorRef] ) sender.asInstanceOf[LocalActorRef] else null
    if ( localActorRef != null )
      send( clusterId, ActorClusterMessageById( clusterId, id, msg, localActorRef._getUUID ) )
  }
  
  def updateClusterAddressMap( clusterId: ClusterIdentity, address: InetAddress, port: Int ) {
    updateClusterAddressMap0( clusterId, UDPAddress( address, port ) )
  }

  @tailrec
  def updateClusterAddressMap0( clusterId: ClusterIdentity, udpAddress: UDPAddress ) {
    idToAddresses.get( clusterId ) match {
      case addys: List[UDPAddress] =>
        if ( ! addys.contains( udpAddress ) && ! idToAddresses.replace( clusterId, addys, udpAddress :: addys ) )
          updateClusterAddressMap0( clusterId, udpAddress )
      case null =>
        if ( idToAddresses.putIfAbsent( clusterId, udpAddress :: Nil ) != null )
          updateClusterAddressMap0( clusterId, udpAddress )
    }
    
  }

  final class Sender extends Runnable {
    def run() {
      while ( ! done.get() ) {
        try {
          val message = sentToProcess.poll( pollTimeout, TimeUnit.MILLISECONDS )
          if ( message != null ) {
            if ( message.status == NotSent ) {
              message.send()
              message.markSentAndWait()
            }
          }
        } catch { case t: Throwable => t.printStackTrace() } // log and try again
      }
    }
  }
  
  def getTypeNum( mType: Byte ) = mType % 16

  final class Receiver( socket: DatagramSocket ) extends Runnable {
    def run() {
      val buffer = new Array[Byte](16384)
      val packet = new DatagramPacket(buffer,buffer.length)
      while ( ! done.get() ) {
        try {
          socket.receive( packet )
          val data = packet.getData
          val dataOffset = packet.getOffset
          val dataLength = packet.getLength
          val in = new ByteArrayInputStream( data, dataOffset, dataLength )
          val mType = readByte( in )
          val uuid = new UUID( new ClusterIdentity( readLong( in ), readLong( in ) ), readLong( in ), readLong( in ) )
          val destination = { val time = readLong( in ); val rand = readLong( in ); if ( time != 0 || rand != 0 ) new ClusterIdentity( time, rand ) else null }
          val totalSize = readInt( in )
          val chunkSize = readShort( in )
          
          getTypeNum( mType ) match {
            case 1 | 2 | 6 =>
              updateClusterAddressMap( uuid.clusterId, packet.getAddress, packet.getPort )

              if ( destination == null || destination == getClusterId ) {
                val receivingMessage = {
                  val receivingMessage = getReceivingMessage( uuid )
                  if ( receivingMessage != null ) receivingMessage
                  else startReceivingMessage( new ReceivingMessage( uuid, totalSize, chunkSize, destination ) )
                }
  
                getTypeNum( mType ) match {
                  case 1 => // MessageChunk => type(B)(1) + uuid(obj) + destCID + total size(int32) + chunk size(int16) + index( start with zero )(int32) + data(B[])
                    val index = readInt( in )
                    println( "Chunk:   " + uuid + " : " + index + " : " + packet.getAddress + " : " + packet.getPort + " to " + socket.getLocalAddress + " : " + socket.getLocalPort )
                    val remainder = totalSize - index * chunkSize
                    val dataSize = if ( remainder < chunkSize ) remainder else chunkSize
                    receivingMessage.addChunk( index, data, dataOffset + 59, dataSize ) // offset = 1 + 32 + 16 + 4 + 2 + 4
                    if ( receivingMessage.isComplete ) {
                      receivingMessage.processMessageOnce( packet.getAddress, packet.getPort )
                    }
                  case 2 => // MessageReceiptRequest => type(B)(2) + uuid + total size(int32) + chunk size(int16)
                    if ( destination == getClusterId ) {
                      if ( receivingMessage.isComplete )
                        receivingMessage.sendReceiptResponse( packet.getAddress, packet.getPort )
                      else
                        receivingMessage.requestMissingChunks( packet.getAddress, packet.getPort )
                    } // todo: else there is an error somewhere. Broadcast messages don't ask for receipts.
                  case 6 => // MessageNoLongerExists => type(B)(6) + uuid + total size(int32) + chunk size(int16)
                    // todo: report error
                  case _ => throw new RuntimeException( "What Happened!" )
                }
              }
            case 3 | 4 | 5 =>
              val sendingMessage = getSendingMessage( uuid )

              getTypeNum( mType ) match {
                case 3 => // MessageReceipt => type(B)(3) + uuid + total size(int32) + chunk size(int16) + errorCode ( 0=success, 1=failure )(int16)
                  if ( sendingMessage == null ) {
                    println( "ReceiptF:" + uuid + " : " + packet.getAddress + " : " + packet.getPort + " to " + socket.getLocalAddress + " : " + socket.getLocalPort )
                    // todo report error
                  } else {
                    println( "Receipt: " + uuid + " : " + packet.getAddress + " : " + packet.getPort + " to " + socket.getLocalAddress + " : " + socket.getLocalPort )
                    sendingMessage.markReceived()
                  }
                case 4 => // MessageChunksNeeded => type(B)(4) + uuid + total size(int32) + chunk size(int16) + num ids (int16) + List( id, id, id, id, id )
                  if ( sendingMessage == null ) {
                    sendMessageNotFound( uuid, totalSize, chunkSize, packet.getAddress, packet.getPort )
                  } else {
                    val num = readShort( in )
                    var missing: List[Int] = Nil
                    0 until num foreach { i => missing ::= readInt( in ) }
                    sendingMessage.resend( missing )
                  }
                case 5 => // MessageChunkRangesNeeded => type(B)(5) + uuid + total size(int32) + chunk size(int16) + num ids (int16) + List( id - id, id - id, id - id )
                  if ( sendingMessage == null ) {
                    sendMessageNotFound( uuid, totalSize, chunkSize, packet.getAddress, packet.getPort )
                  } else {
                    val num = readShort( in )
                    var missing: List[Int] = Nil
                    0 until num foreach { i => missing :::= ( readInt( in ) to readInt( in ) ).toList }
                    sendingMessage.resend( missing )
                  }
                case _ => throw new RuntimeException( "What Happened!" )
              }
            case _ => throw new RuntimeException( "What Happened! Bad IO? " )
          }
          
        } catch { case t: Throwable => t.printStackTrace() } // log and try again
      }
    }
  }

  def processMessage( clusterMessage: ClusterMessage) {
    clusterMessage match {
      case StopActorClusterMessage( recipient: UUID, sender: UUID ) =>
        val actor = getByUUID( recipient )
        if ( actor != null )
          actor.stop()
      case StatusRequestClusterMessage( recipient: UUID, sender: UUID ) =>
        val actor = getByUUID( recipient )
        if ( actor != null )
          send( sender.clusterId, StatusResponseClusterMessage( sender, recipient, ! actor.isActive ) )
      case StatusResponseClusterMessage( recipient: UUID, sender: UUID, stopped: Boolean ) =>
        val actor = getByUUID( recipient )
        if ( actor != null )
          actor.!(stopped)(new RemoteActorRef(sender,UDPCluster.this)) 
      case ActorClusterMessage( null, msg, senderUUID ) => 
        for ( actor <- getAll )
          actor.!(msg)(new RemoteActorRef(senderUUID,UDPCluster.this)) 
      case ActorClusterMessage( uuid, msg, senderUUID ) =>  
        val actor = getByUUID( uuid )
        if ( actor != null )
          actor.!(msg)(new RemoteActorRef(senderUUID,UDPCluster.this))
      case ActorClusterAllMessage( null, null, msg, senderUUID ) =>
        for ( actor <- getAll )
          actor.!(msg)(new RemoteActorRef(senderUUID,UDPCluster.this)) 
      case ActorClusterAllMessage( null, className, msg, senderUUID ) =>
        for ( actor <- getAllByClassName( className ) )
          actor.!(msg)(new RemoteActorRef(senderUUID,UDPCluster.this))
      case ActorClusterAllMessage( clusterId, null, msg, senderUUID ) =>
        if ( clusterId == getClusterId )
          for ( actor <- getAll )
            actor.!(msg)(new RemoteActorRef(senderUUID,UDPCluster.this)) 
      case ActorClusterAllMessage( clusterId, className, msg, senderUUID ) =>
        if ( clusterId == getClusterId )
          for ( actor <- getAllByClassName( className ) )
            actor.!(msg)(new RemoteActorRef(senderUUID,UDPCluster.this))
      case ActorClusterMessageById( null, null, msg, senderUUID ) =>
        for ( actor <- getAll )
          actor.!(msg)(new RemoteActorRef(senderUUID,UDPCluster.this)) 
      case ActorClusterMessageById( null, id, msg, senderUUID ) =>
        for ( actor <- getAllById( id ) )
          actor.!(msg)(new RemoteActorRef(senderUUID,UDPCluster.this))
      case ActorClusterMessageById( clusterId, null, msg, senderUUID ) =>
        if ( clusterId == getClusterId )
          for ( actor <- getAll )
            actor.!(msg)(new RemoteActorRef(senderUUID,UDPCluster.this)) 
      case ActorClusterMessageById( clusterId, id, msg, senderUUID ) =>
        if ( clusterId == getClusterId )
          for ( actor <- getAllById( id ) )
            actor.!(msg)(new RemoteActorRef(senderUUID,UDPCluster.this))
      case _ => throw new RuntimeException( "What Happened!" )
    }
  }

  def writeByte( out: OutputStream, value: Byte ) { out.write( value ) }
  def writeShort( out: OutputStream, value: Short ) {
    var temp: Int = value
    out.write( temp & 0xFF )
    temp >>>= 8; out.write( temp & 0xFF )
  }
  def writeInt( out: OutputStream, value: Int ) {
    var temp = value
    out.write( temp & 0xFF )
    temp >>>= 8; out.write( temp & 0xFF )
    temp >>>= 8; out.write( temp & 0xFF )
    temp >>>= 8; out.write( temp & 0xFF )
  }
  def writeLong( out: OutputStream, value: Long ) {
    var temp = value
    out.write( ( temp & 0xFF ).asInstanceOf[Int] ); 
    temp >>>= 8; out.write( ( temp & 0xFF ).asInstanceOf[Int] )
    temp >>>= 8; out.write( ( temp & 0xFF ).asInstanceOf[Int] )
    temp >>>= 8; out.write( ( temp & 0xFF ).asInstanceOf[Int] )
    temp >>>= 8; out.write( ( temp & 0xFF ).asInstanceOf[Int] )
    temp >>>= 8; out.write( ( temp & 0xFF ).asInstanceOf[Int] )
    temp >>>= 8; out.write( ( temp & 0xFF ).asInstanceOf[Int] )
    temp >>>= 8; out.write( ( temp & 0xFF ).asInstanceOf[Int] )
  }
  def readByte( in: InputStream ): Byte = in.read().asInstanceOf[Byte]
  def readShort( in: InputStream ): Short = {
    var temp: Short = in.read().asInstanceOf[Short]
    temp = ( temp + in.read() * 0x100 ).asInstanceOf[Short]
    temp
  }
  def readInt( in: InputStream ): Int = {
    var temp: Int = in.read()
    temp += in.read() * 0x100
    temp += in.read() * 0x10000
    temp += in.read() * 0x1000000
    temp
  }
  def readLong( in: InputStream ): Long = {
    var temp: Long = in.read().asInstanceOf[Long]
    temp += ( in.read().asInstanceOf[Long] ) * 0x100L
    temp += ( in.read().asInstanceOf[Long] ) * 0x10000L
    temp += ( in.read().asInstanceOf[Long] ) * 0x1000000L
    temp += ( in.read().asInstanceOf[Long] ) * 0x100000000L
    temp += ( in.read().asInstanceOf[Long] ) * 0x10000000000L
    temp += ( in.read().asInstanceOf[Long] ) * 0x1000000000000L
    temp += ( in.read().asInstanceOf[Long] ) * 0x100000000000000L
    temp
  }
  def sendMessageNotFound( uuid: UUID, totalSize: Int, chunkSize: Short, address: InetAddress, port: Int ) {
    val socket = getSocketForTarget( new UDPAddress( address, port ) )
    if ( socket != null ) {
      val out = new ByteArrayOutputStream()
      writeByte(out,6) // MessageNoLongerExists => type(B)(6) + uuid + total size(int32) + chunk size(int16)
      writeLong(out,uuid.clusterId.time)
      writeLong(out,uuid.clusterId.rand)
      writeLong(out,uuid.time)
      writeLong(out,uuid.rand)
      writeInt(out,totalSize)
      writeShort(out,chunkSize)
      out.flush()
      val packetBytes = out.toByteArray
      val packet = new DatagramPacket(packetBytes,packetBytes.length,address,port)
      try {
        socket.send(packet)
        // TODO: report errors
      } catch { case t: Throwable => t.printStackTrace() }
    } // todo: else what?
  }

  sealed abstract class SendingStatus
  case object NotSent extends SendingStatus
  case object WaitingForReceipt extends SendingStatus
  case object SuccessfullySent extends SendingStatus

  final class SendingMessage( _bytes: Array[Byte], destination: ClusterIdentity, chunkSize: Short ) {
    val uuid = new UUID( getClusterId )
    val bytes = _bytes
    val totalSize = bytes.length
    
    def isBroadCast = destination == null

    var status: SendingStatus = _
    var waitingEntry: CancelableQueue.Entry[SendingMessage] = _ 
    var waitTill: Long = _
    var waitRepeatedCount: Int = _

    synchronized {
      status = NotSent
    }

    def totalChunks = ( if ( totalSize % chunkSize > 0 ) totalSize / chunkSize + 1 else totalSize / chunkSize )

    def getWaitTill: Long = synchronized { waitTill }

    def startWaiting() {
      synchronized {
        if ( waitingEntry == null || ! waitingEntry.isInList )
          waitingEntry = sentInWaiting.push( this )
        waitTill = System.currentTimeMillis() + waitingForReceiptTimeout
      }
    }

    def resetWaiting() {
      synchronized {
        if ( waitingEntry != null && waitingEntry.isInList )
          sentInWaiting.delete( waitingEntry )
        waitingEntry = sentInWaiting.push( this )
        waitTill = System.currentTimeMillis() + waitingForReceiptTimeout
      }
    }

    def stopWaiting() {
      synchronized {
        if ( waitingEntry != null && waitingEntry.isInList )
          sentInWaiting.delete( waitingEntry )
      }
    }

    def markReceived() {
      synchronized {
        status = SuccessfullySent
        stopWaiting()
        waitTill = System.currentTimeMillis() + waitingAfterReceiptTimeout
        sentCompleted.add( this )
      }
    }

    def markSentAndWait() {
      synchronized {
        if ( status == NotSent ) {
          if ( destination == null ) {
            status = SuccessfullySent
            waitTill = System.currentTimeMillis() + waitingAfterReceiptTimeout
            sentCompleted.add( this )
          } else {
            status = WaitingForReceipt
            startWaiting()
          }
        }
      }
    }

    def send() {
      getDestination( destination ) match {
        case Some((udpAddress,socket)) =>
          send( socket, udpAddress.address,udpAddress.port )
        case None =>
          for ( (socket,broadcast,bAddress) <- getSockets.values )
            send( socket,bAddress,broadcastPort )
      }
    }
    
    def resend( chunks: List[Int] ) {
      getDestination( destination ) match {
        case Some((udpAddress,socket)) =>
          chunks foreach { send( socket, udpAddress.address,udpAddress.port, _ ) }
        case None =>
          for ( (socket,broadcast,bAddress) <- getSockets.values )
            chunks foreach { send( socket,bAddress,broadcastPort, _ ) }
      }
    }

    private def send( socket: DatagramSocket, address: InetAddress, port: Int ) {
      0 until totalChunks foreach { send( socket, address, port, _ ) }
    }

    private def send( socket: DatagramSocket, address: InetAddress, port: Int, index: Int ) {
      val offset = index * chunkSize
      val remainder = totalSize - offset 
      val size = if ( remainder < chunkSize ) remainder else chunkSize
      val out = new ByteArrayOutputStream()
      writeByte(out,1) // MessageChunk
      writeLong(out,uuid.clusterId.time)
      writeLong(out,uuid.clusterId.rand)
      writeLong(out,uuid.time)
      writeLong(out,uuid.rand)
      if ( destination == null ) {
        writeLong(out,0L)
        writeLong(out,0L)
      } else {
        writeLong(out,destination.time)
        writeLong(out,destination.rand)
      }
      writeInt(out,totalSize)
      writeShort(out,chunkSize)
      writeInt(out,index)
      out.write(bytes,offset,size)
      out.flush()
      val packetBytes = out.toByteArray
      val packet = new DatagramPacket(packetBytes,packetBytes.length,address,port)
      try {
        socket.send(packet)
        // TODO: report errors
      } catch { case t: Throwable => t.printStackTrace() }
    }

    def requestReceipt() {
      var doRequest = false
      synchronized {
        if ( status == WaitingForReceipt ) {
          if ( waitRepeatedCount < maxReceiptWaits ) {
            waitRepeatedCount += 1
            resetWaiting()
            doRequest = true
          } else {
            // report and delete
            sent.remove( uuid )
            // todo: report
          }
        }
      }
      if ( doRequest ) {
        getDestination( destination ) match {
          case Some((udpAddress,socket)) =>
            requestReceipt( socket, udpAddress.address,udpAddress.port )
          case None =>
            for ( (socket,broadcast,bAddress) <- getSockets.values )
              requestReceipt( socket,bAddress,broadcastPort )
        }
      }
    }

    private def requestReceipt( socket: DatagramSocket, address: InetAddress, port: Int ) {
      val out = new ByteArrayOutputStream()
      writeByte(out,2) // MessageReceiptRequest => type(B)(2) + uuid + destCID + total size(int32) + chunk size(int16)
      writeLong(out,uuid.clusterId.time)
      writeLong(out,uuid.clusterId.rand)
      writeLong(out,uuid.time)
      writeLong(out,uuid.rand)
      if ( destination == null ) {
        writeLong(out,0L)
        writeLong(out,0L)
      } else {
        writeLong(out,destination.time)
        writeLong(out,destination.rand)
      }
      writeInt(out,totalSize)
      writeShort(out,chunkSize)
      out.flush()
      val packetBytes = out.toByteArray
      val packet = new DatagramPacket(packetBytes,packetBytes.length,address,port)
      try {
        socket.send(packet)
        // TODO: report errors
      } catch { case t: Throwable => t.printStackTrace() }
    }
  }

  sealed abstract class ReceivingStatus
  case object WaitingForChunks extends ReceivingStatus
  case object SuccessfullyReceived extends ReceivingStatus

  final class ReceivingMessage( _uuid: UUID, totalSize: Int, chunkSize: Short, destination: ClusterIdentity ) {
    val bytes = new Array[Byte]( totalSize )
    def uuid = _uuid

    var chunks: List[Int] = Nil
    var message: ClusterMessage = _
    var messageProcessed: Boolean = _

    var status: ReceivingStatus = _
    var waitingEntry: CancelableQueue.Entry[ReceivingMessage] = _ 
    var waitTill: Long = _
    var waitRepeatedCount: Int = _

    synchronized {
      status = WaitingForChunks
    }

    def getWaitTill: Long = synchronized { waitTill }

    def startWaiting() {
      synchronized {
        if ( waitingEntry == null || ! waitingEntry.isInList )
          waitingEntry = receivedInWaiting.push( this )
        waitTill = System.currentTimeMillis() + waitingForAllChunksTimeout
      }
    }

    def resetWaiting() {
      synchronized {
        if ( waitingEntry != null && waitingEntry.isInList )
          waitingEntry.delete()
        waitingEntry = receivedInWaiting.push( this )
        waitTill = System.currentTimeMillis() + waitingForAllChunksTimeout
      }
    }

    def stopWaiting() {
      synchronized {
        if ( waitingEntry != null && waitingEntry.isInList )
          waitingEntry.delete()
        waitTill = 0
      }
    }

    def addChunk( index: Int, buf: Array[Byte], offset: Int, length: Int ) {
      synchronized {
        if ( ! chunks.contains( index ) ) {
         Array.copy( buf, offset, bytes, index * chunkSize, length )
         chunks ::= index
        }
        if ( isComplete )
          stopWaiting()
      }
    }
    
    def totalChunks = ( if ( totalSize % chunkSize > 0 ) totalSize / chunkSize + 1 else totalSize / chunkSize )
    
    def missingChunks(): List[Int] = {
      synchronized {
        ( 0 until totalChunks filter { ! chunks.contains( _ ) } ).toList
      }
    }
    
    def isComplete: Boolean = {
      synchronized {
        chunks.size == totalChunks
      }
    }
    
    def processMessageOnce( address: InetAddress, port: Int ) = {
      synchronized {
        if ( message == null ) {
          message = new ObjectInputStream( new ByteArrayInputStream( bytes ) ).readObject() match {
            case message: ClusterMessage => message
            case _ => null
          } 
        }
        if ( message != null && ! messageProcessed ) {
          messageProcessed = true
          if ( destination == null )
            processMessage( message )
          else if ( destination == getClusterId ) {
            processMessage( message )
            sendReceiptResponse( address, port )
          }
        }
        message
      }      
    }
    def getMessage = {
      synchronized {
        if ( message == null ) {
          message = new ObjectInputStream( new ByteArrayInputStream( bytes ) ).readObject() match {
            case message: ClusterMessage => message
            case _ => null
          } 
        }
        message
      }
    }
    
    def sendReceiptResponse() {
      getDestination( uuid.clusterId ) match {
        case Some((address,socket)) =>
          sendReceiptResponse( socket, address.address, address.port )
        case None => // todo: what do we do here?
      }
    }
    def sendReceiptResponse( address: InetAddress, port: Int ) {
      val socket = getSocketForTarget( new UDPAddress( address, port ) )
      if ( socket != null )
        sendReceiptResponse( socket, address, port )
      // todo: else what?
    }
    def sendReceiptResponse( socket: DatagramSocket, address: InetAddress, port: Int ) {
      val out = new ByteArrayOutputStream()
      writeByte(out,3) // MessageReceipt => type(B)(3) + uuid + total size(int32) + chunk size(int16) + errorCode ( 0=success, 1=failure )(int16)
      writeLong(out,uuid.clusterId.time)
      writeLong(out,uuid.clusterId.rand)
      writeLong(out,uuid.time)
      writeLong(out,uuid.rand)
      if ( destination == null ) {
        writeLong(out,0L)
        writeLong(out,0L)
      } else {
        writeLong(out,destination.time)
        writeLong(out,destination.rand)
      }
      writeInt(out,totalSize)
      writeShort(out,chunkSize)
      writeShort(out,0) // success
      out.flush()
      val packetBytes = out.toByteArray
      val packet = new DatagramPacket(packetBytes,packetBytes.length,address,port)
      try {
        socket.send(packet)
        // TODO: report errors
      } catch { case t: Throwable => t.printStackTrace() }
    }
    def requestMissingChunksAndWait() {
      var doRequest = false
      synchronized {
        if ( status == WaitingForChunks ) {
          if ( waitRepeatedCount < maxChunkWaits ) {
            waitRepeatedCount += 1
            resetWaiting()
            doRequest = true
          } else {
            // report and delete
            received.remove( uuid )
            // todo: report
          }
        }
      }
      if ( doRequest )
        requestMissingChunks()
    }
    def requestMissingChunks() {
      getDestination( uuid.clusterId ) match {
        case Some((address,socket)) =>
          requestMissingChunks( socket, address.address, address.port )
        case None => // todo: what do we do here?
      }
    }
    def requestMissingChunks( address: InetAddress, port: Int ) {
      val socket = getSocketForTarget( new UDPAddress( address, port ) )
      if ( socket != null )
        requestMissingChunks( socket, address, port )
      // todo: else what?
    }
    def requestMissingChunks( socket: DatagramSocket, address: InetAddress, port: Int ) {
      val missing = missingChunks()
      if ( missing.size > maxMissingList ) {
        missing.sliding(maxMissingList,maxMissingList) foreach { requestMissingChunksList( socket, address, port, _ ) }
      } else requestMissingChunksList( socket, address, port, missing )
    }
    private def requestMissingChunksList( socket: DatagramSocket, address: InetAddress, port: Int, missing: List[Int] ) {
      val out = new ByteArrayOutputStream()
      writeByte(out,4) // MessageChunksNeeded => type(B)(4) + uuid + total size(int32) + chunk size(int16) + num ids (int16) + List( id, id, id, id, id )
      writeLong(out,uuid.clusterId.time)
      writeLong(out,uuid.clusterId.rand)
      writeLong(out,uuid.time)
      writeLong(out,uuid.rand)
      if ( destination == null ) {
        writeLong(out,0L)
        writeLong(out,0L)
      } else {
        writeLong(out,destination.time)
        writeLong(out,destination.rand)
      }
      writeInt(out,totalSize)
      writeShort(out,chunkSize)
      writeShort(out,missing.size.asInstanceOf[Short]) // num of missing
      missing foreach { writeInt( out, _ ) }
      out.flush()
      val packetBytes = out.toByteArray
      val packet = new DatagramPacket(packetBytes,packetBytes.length,address,port)
      try {
        socket.send(packet)
        // TODO: report errors
      } catch { case t: Throwable => t.printStackTrace() }
    }
  }
  /**
   * Message sending algorithm:
   * send message ( connection error may be immediate or not ) and mark successfully send chunks
   * wait for response, if no response in time report error on interface to cluster listener
   * then try another interface. If no more interfaces or out of tries then report message failure to cluster listener.
   * If actor instance of MessageFailureActor then report message as failed.
   * 
   * MessageChunk => type(B)(1) + uuid(obj) + destCID + total size(int32) + chunk size(int16) + index( start with zero )(int32) + data(B[])
   * MessageReceiptRequest => type(B)(2) + uuid + destCID + total size(int32) + chunk size(int16)
   * MessageReceipt => type(B)(3) + uuid + destCID + total size(int32) + chunk size(int16) + errorCode ( 0=success, 1=failure )(int16)
   * MessageChunksNeeded => type(B)(4) + uuid + destCID + total size(int32) + chunk size(int16) + num ids (int16) + List( id, id, id, id, id ) 
   * MessageChunkRangesNeeded => type(B)(5) + uuid + destCID + total size(int32) + chunk size(int16) + num ids (int16) + List( id - id, id - id, id - id )
   * MessageNoLongerExists => type(B)(6) + uuid + destCID + total size(int32) + chunk size(int16)
   * 
   * Receiver triggers MessageReceipt upon receiving all chunks
   * If sender does not hear back after a set time he sends a recipt request
   * Receiver replys to a recipt request with a recipt or chunks needed response.
   * The chunks needed response is sent even when the reciever never heard any of the chunks.
   * 
   * If the receiver has not gotten all the chunks by a set time they respond with a chunks needed.
   * If the receiver does not receive all the chunks by a set time then it is simply forgotten.
   */
  final class SentWaitingProcessor() extends Runnable {
    def run() {
      while ( ! done.get() ) {
        sentInWaiting.pop() match {
          case None => synchronized { wait( waitingForReceiptTimeout ) }
          case Some( message ) =>
            val currentTime = System.currentTimeMillis()
            val waitTill = message.getWaitTill
            if ( currentTime < waitTill )
              synchronized { wait( waitTill - currentTime ) }
            message.requestReceipt()
        }
      }
    }
  }

  final class SentCompletedCleaner() extends Runnable {
    def run() {
      while ( ! done.get() ) {
        try {
          val message = sentCompleted.poll( pollTimeout, TimeUnit.MILLISECONDS )
          if ( message != null ) {
            if ( message.status == SuccessfullySent ) {
              val currentTime = System.currentTimeMillis()
              val waitTill = message.getWaitTill
              if ( currentTime < waitTill )
                synchronized { wait( waitTill - currentTime ) }
              sent.remove( message.uuid )
            } else throw new RuntimeException( "What Happened!" )
          }
        } catch { case t: Throwable => t.printStackTrace() } // log and try again
      }
    }
  }

  final class ReceivedWaitingProcessor() extends Runnable {
    def run() {
      while ( ! done.get() ) {
        receivedInWaiting.pop() match {
          case None => synchronized { wait( waitingForAllChunksTimeout ) }
          case Some( message ) =>
            val currentTime = System.currentTimeMillis()
            val waitTill = message.getWaitTill
            if ( currentTime < waitTill )
              synchronized { wait( waitTill - currentTime ) }
            message.requestMissingChunksAndWait()
        }
      }
    }
  }

  final class ReceivedCompletedCleaner() extends Runnable {
    def run() {
      while ( ! done.get() ) {
        try {
          val message = receivedCompleted.poll( pollTimeout, TimeUnit.MILLISECONDS )
          if ( message != null ) {
            if ( message.status == SuccessfullyReceived ) {
              val currentTime = System.currentTimeMillis()
              val waitTill = message.getWaitTill
              if ( currentTime < waitTill )
                synchronized { wait( waitTill - currentTime ) }
              received.remove( message.uuid )
            } else throw new RuntimeException( "What Happened!" )
          }
        } catch { case t: Throwable => t.printStackTrace() } // log and try again
      }
    }
  }
}
