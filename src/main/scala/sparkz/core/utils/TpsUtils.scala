package sparkz.core.utils

import sparkz.core.network.message._
import sparkz.core.settings.MessageDelays
import sparkz.core.transaction.Transaction

import scala.language.existentials
import scala.util.Success

object TpsUtils {
  def addDelay(msg: Message[_], delaySettings: MessageDelays): Long = {
    msg.spec.messageCode match {
      case GetPeersSpec.messageCode => delaySettings.GetPeersSpec
      case PeersSpec.messageCode => delaySettings.PeerSpec
      case InvSpec.MessageCode => msg match {
        case Message(spec, Left(msgBytes), _) =>
          spec.parseBytesTry(msgBytes) match {
            case Success(content) =>
              handleInvSpecContent(delaySettings, spec, content)
            case _ => 0
          }
        case Message(spec, Right(content), _) =>
          handleInvSpecContent(delaySettings, spec, content)
        case _ => 0
      }
      case RequestModifierSpec.MessageCode => delaySettings.RequestModifierSpec
      case ModifiersSpec.MessageCode => delaySettings.ModifiersSpec
      case _ => 0
    }
  }

  private def handleInvSpecContent(delaySettings: MessageDelays, spec: MessageSpec[_], content: Any): Long = {
    val parsedMsg = (spec, content, None)
    parsedMsg match {
      case (_: InvSpec, data: InvData, _) if data.typeId == Transaction.ModifierTypeId => delaySettings.Transaction
      case _ => delaySettings.Block
    }
  }
}
