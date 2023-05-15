package sparkz.core.network

import akka.actor.{ActorRef, ActorSystem, Cancellable}
import sparkz.core.consensus.ContainsModifiers
import sparkz.core.network.ModifiersStatus._
import sparkz.core.network.NodeViewSynchronizer.ReceivableMessages.CheckDelivery
import sparkz.util.SparkzEncoding
import sparkz.core.{ModifierTypeId, NodeViewModifier}
import sparkz.util.{ModifierId, SparkzLogging}

import java.util.concurrent.TimeUnit
import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Try}

/**
  * This class tracks modifier statuses.
  * Modifier can be in one of the following states: Unknown, Requested, Received, Held, Invalid.
  * See ModifiersStatus for states description.
  * Modifiers in `Requested` state are kept in `requested` map containing info about peer and number of retries.
  * Modifiers in `Received` state are kept in `received` set.
  * Modifiers in `Invalid` state are kept in `invalid` set to prevent this modifier download and processing.
  * Modifiers in `Held` state are not kept in this class - we can get this status from object, that contains
  * these modifiers (History for PersistentNodeViewModifier, Mempool for EphemerealNodeViewModifier).
  * If we can't identify modifiers status based on the rules above, it's status is Unknown.
  *
  * In success path modifier changes his statuses `Unknown`->`Requested`->`Received`->`Held`.
  * If something went wrong (e.g. modifier was not delivered) it goes back to `Unknown` state
  * (if we are going to receive it in future) or to `Invalid` state (if we are not going to receive
  * this modifier anymore)
  * Locally generated modifiers may go to `Held` or `Invalid` states at any time.
  * These rules are also described in `isCorrectTransition` function.
  *
  * This class is not thread-save so it should be used only as a local field of an actor
  * and its methods should not be called from lambdas, Future, Future.map, etc.
  */
class DeliveryTracker(system: ActorSystem,
                      deliveryTimeout: FiniteDuration,
                      maxDeliveryChecks: Int,
                      maxRequestedPerPeer: Int,
                      slowModeFeatureFlag: Boolean,
                      slowModeThresholdMs: Long,
                      nvsRef: ActorRef) extends SparkzLogging with SparkzEncoding {

  protected case class RequestedInfo(peer: ConnectedPeer, cancellable: Cancellable, checks: Int)

  // when a remote peer is asked for a modifier we add the requested data to `requested`
  protected val requested: mutable.Map[ModifierId, RequestedInfo] = mutable.Map()

  // to keep track of requested modifiers on per-peer basis, and limit to prevent overflow
  protected val peerLimits: mutable.Map[ConnectedPeer, Int] = mutable.Map()

  // when our node received invalid modifier we put it to `invalid`
  protected val invalid: mutable.HashSet[ModifierId] = mutable.HashSet()

  // when our node received a modifier we put it to `received`
  protected val received: mutable.Map[ModifierId, (ConnectedPeer, Long)] = mutable.Map()

  private var averageProcessingTimeMs: Long = 0
  var slowMode: Boolean = false

  /**
    * @return status of modifier `id`.
    *         Since this class do not keep statuses for modifiers that are already in NodeViewHolder,
    *         `modifierKeepers` are required here to check that modifier is in `Held` status
    */
  def status(modifierId: ModifierId, modifierKeepers: Seq[ContainsModifiers[_]]): ModifiersStatus =
    if (received.contains(modifierId)) Received
    else if (requested.contains(modifierId)) Requested
    else if (invalid.contains(modifierId)) Invalid
    else if (modifierKeepers.exists(_.contains(modifierId))) Held
    else Unknown

  def status(modifierId: ModifierId, mk: ContainsModifiers[_ <: NodeViewModifier]): ModifiersStatus = {
    status(modifierId, Seq(mk))
  }

  def status(modifierId: ModifierId): ModifiersStatus = {
    status(modifierId, Seq())
  }

  /**
    *
    * Our node have requested a modifier, but did not received it yet.
    * Stops processing and if the number of checks did not exceed the maximum continue to waiting.
    *
    * @return `true` if number of checks was not exceed, `false` otherwise
    */
  def onStillWaiting(cp: ConnectedPeer, modifierTypeId: ModifierTypeId, modifierId: ModifierId)
                    (implicit ec: ExecutionContext): Try[Unit] =
    tryWithLogging {
      val checks = requested(modifierId).checks + 1
      setUnknown(modifierId)
      if (checks < maxDeliveryChecks) setRequested(modifierId, modifierTypeId, cp, checks)
      else throw new StopExpectingError(modifierId, checks)
    }

  /**
    * Set status of modifier with id `id` to `Requested`
    */
  private def setRequested(id: ModifierId, typeId: ModifierTypeId, supplier: ConnectedPeer, checksDone: Int = 0)
                          (implicit ec: ExecutionContext): Unit =
    tryWithLogging {
      require(isCorrectTransition(status(id), Requested), s"Illegal status transition: ${status(id)} -> Requested")
      val cancellable = system.scheduler.scheduleOnce(deliveryTimeout, nvsRef, CheckDelivery(supplier, typeId, id))
      requested.put(id, RequestedInfo(supplier, cancellable, checksDone)) match {
        case Some(RequestedInfo(peer, _, _)) if supplier.connectionId == peer.connectionId => //we already had this modifier, it is counted
        case Some(RequestedInfo(peer, _, _)) => decrementPeerLimitCounter(peer); incrementPeerLimitCounter(supplier)
        case None => incrementPeerLimitCounter(supplier)
      }
    }

  def setRequested(ids: Seq[ModifierId], typeId: ModifierTypeId, cp: ConnectedPeer)
                  (implicit ec: ExecutionContext): Unit = ids.foreach(setRequested(_, typeId, cp))

  /**
    * Modified with id `id` is permanently invalid - set its status to `Invalid`
    * and return [[ConnectedPeer]] which sent bad modifier.
    */
  def setInvalid(modifierId: ModifierId): Option[ConnectedPeer] = {
    val oldStatus: ModifiersStatus = status(modifierId)
    val transitionCheck = tryWithLogging {
      require(isCorrectTransition(oldStatus, Invalid), s"Illegal status transition: $oldStatus -> Invalid")
    }
    transitionCheck
      .toOption
      .flatMap { _ =>
        val senderOpt = oldStatus match {
          case Requested =>
            requested(modifierId).cancellable.cancel()
            requested.remove(modifierId).map(_.peer)
              .map(decrementPeerLimitCounter)
          case Received =>
            received.remove(modifierId)
              .collect { case (peer, timestamp) =>
                updateProcessingTime(timestamp)
                peer
              }
          case _ =>
            None
        }
        invalid.add(modifierId)
        senderOpt
      }
  }

  /**
    * Modifier with id `id` was successfully applied to history - set its status to `Held`.
    */
  def setHeld(id: ModifierId): Unit =
    tryWithLogging {
      val oldStatus: ModifiersStatus = status(id)
      require(isCorrectTransition(oldStatus, Held), s"Illegal status transition: $oldStatus -> Held")
      clearStatusForModifier(id, oldStatus) // clear old status
    }

  /**
    * Set status of modifier with id `id` to `Unknown`.
    *
    * We're not trying to process modifier anymore in this case.
    * This may happen when received modifier bytes does not correspond to declared modifier id,
    * this modifier was removed from cache because cache is overfull or
    * we stop trying to download this modifiers due to exceeded number of retries
    */
  def setUnknown(id: ModifierId): Unit =
    tryWithLogging {
      val oldStatus: ModifiersStatus = status(id)
      require(isCorrectTransition(oldStatus, Unknown), s"Illegal status transition: $oldStatus -> Unknown")
      clearStatusForModifier(id, oldStatus) // clear old status
    }

  /**
    * Modifier with id `id`  was received from remote peer - set its status to `Received`.
    */
  def setReceived(id: ModifierId, sender: ConnectedPeer): Unit =
    tryWithLogging {
      val oldStatus: ModifiersStatus = status(id)
      require(isCorrectTransition(oldStatus, Received), s"Illegal status transition: $oldStatus -> Received")
      if (oldStatus != Received) {
        requested(id).cancellable.cancel()
        requested.remove(id)
        decrementPeerLimitCounter(sender)
        received.put(id, (sender, System.nanoTime()))
      }
    }

  def peerInfo(id: ModifierId): Option[ConnectedPeer] = {
    val modifierStatus: ModifiersStatus = status(id)
    modifierStatus match {
      case Requested =>
        requested.get(id).map(_.peer)
      case Received =>
        received.get(id).map(_._1)
      case _ =>
        None
    }
  }

  def getPeerLimit(peer: ConnectedPeer): Int = {
    maxRequestedPerPeer - peerLimits.getOrElse(peer, 0)
  }

  private def incrementPeerLimitCounter(peer: ConnectedPeer): Unit = {
    peerLimits.get(peer) match {
      case Some(value) => peerLimits.put(peer, value + 1)
      case None => peerLimits.put(peer, 1)
    }
  }

  private def decrementPeerLimitCounter(peer: ConnectedPeer): ConnectedPeer = {
    peerLimits.get(peer) match {
      case Some(value) if value == 1 => peerLimits.remove(peer)
      case Some(value) => peerLimits.put(peer, value - 1)
      case _ =>
    }
    peer
  }

  /**
    * Self-check that transition between states is correct.
    *
    * Modifier may stay in current state,
    * go to Requested state form Unknown
    * go to Received state from Requested
    * go to Invalid state from any state (this may happen on invalid locally generated modifier)
    * go to Unknown state from Requested and Received states
    */
  private def isCorrectTransition(oldStatus: ModifiersStatus, newStatus: ModifiersStatus): Boolean =
    oldStatus match {
      case old if old == newStatus => true
      case _ if newStatus == Invalid || newStatus == Held => true
      case Unknown => newStatus == Requested
      case Requested => newStatus == Unknown || newStatus == Received
      case Received => newStatus == Unknown
      case _ => false
    }

  private[network] def clearStatusForModifier(id: ModifierId, oldStatus: ModifiersStatus): Unit =
    oldStatus match {
      case Requested =>
        requested(id).cancellable.cancel()
        requested.remove(id).map(_.peer)
          .map(decrementPeerLimitCounter)
      case Received =>
        received.remove(id)
          .foreach(peer_timestamp => updateProcessingTime(peer_timestamp._2))
      case _ =>
        ()
    }

  class StopExpectingError(mid: ModifierId, checks: Int)
    extends Error(s"Stop expecting ${encoder.encodeId(mid)} due to exceeded number of retries $checks")

  private def tryWithLogging[T](fn: => T): Try[T] =
    Try(fn).recoverWith {
      case e: StopExpectingError =>
        log.warn(e.getMessage)
        Failure(e)
      case e =>
        log.warn("Unexpected error", e)
        Failure(e)
    }

  private def updateProcessingTime(startTime: Long): Unit = {
    if (slowModeFeatureFlag) {
      val elapsedMs: Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime)
      averageProcessingTimeMs = (averageProcessingTimeMs * 0.9).toLong + (elapsedMs * 0.1).toLong
      if (averageProcessingTimeMs > slowModeThresholdMs && !slowMode) {
        slowMode = true
        logger.warn("Slow mode enabled on P2P layer due to high load. Transactions won't be requested or broadcasted.")
      } else if (averageProcessingTimeMs < slowModeThresholdMs && slowMode) {
        slowMode = false
        logger.warn("Slow mode disabled on P2P layer. Transactions will be requested or broadcasted.")
      }
    }
  }
}
