package scorex.core.network.peer

import java.net.InetSocketAddress
import org.scalatest.Assertion
import scorex.core.network.Outgoing
import scorex.network.NetworkTests

import scala.util.Random

class InMemoryPeerDatabaseSpec extends NetworkTests {

  private val peerAddress1 = new InetSocketAddress("1.1.1.1", 27017)
  private val peerAddress2 = new InetSocketAddress("2.2.2.2", 27017)

  private def withDb(test: InMemoryPeerDatabase => Assertion): Assertion =
    test(new InMemoryPeerDatabase(settings.network.copy(storedPeersLimit = 10), timeProvider))

  "new DB" should "be empty" in {
    withDb { db =>
      db.isEmpty shouldBe true
      db.blacklistedPeers.isEmpty shouldBe true
      db.knownPeers.isEmpty shouldBe true
      db.knownPeers.isEmpty shouldBe true
    }
  }

  it should "be non-empty after adding a peer" in {
    withDb { db =>
      db.addOrUpdateKnownPeer(getPeerInfo(peerAddress1))
      db.isEmpty shouldBe false
      db.blacklistedPeers.isEmpty shouldBe true
      db.knownPeers.isEmpty shouldBe false
    }
  }

  it should "return a peer after adding a peer" in {
    withDb { db =>
      val peerInfo = getPeerInfo(peerAddress1)

      db.addOrUpdateKnownPeer(peerInfo)
      db.knownPeers shouldBe Map(peerAddress1 -> peerInfo)
    }
  }

  it should "return an updated peer after updating a peer" in {
    withDb { db =>
      val peerInfo = getPeerInfo(peerAddress1, Some("initialName"))
      db.addOrUpdateKnownPeer(peerInfo)
      val newPeerInfo = getPeerInfo(peerAddress1, Some("updatedName"))
      db.addOrUpdateKnownPeer(newPeerInfo)

      db.knownPeers shouldBe Map(peerAddress1 -> newPeerInfo)
    }
  }

  it should "return a blacklisted peer after blacklisting" in {
    withDb { db =>
      db.addOrUpdateKnownPeer(getPeerInfo(peerAddress1))
      db.addOrUpdateKnownPeer(getPeerInfo(peerAddress2))
      db.addToBlacklist(peerAddress1, PenaltyType.PermanentPenalty)

      db.isBlacklisted(peerAddress1.getAddress) shouldBe true
      db.isBlacklisted(peerAddress2.getAddress) shouldBe false
      db.blacklistedPeers shouldBe Seq(peerAddress1.getAddress)
    }
  }

  it should "the blacklisted peer be absent in knownPeers" in {
    withDb { db =>
      val peerInfo1 = getPeerInfo(peerAddress1)
      db.addOrUpdateKnownPeer(peerInfo1)
      db.addOrUpdateKnownPeer(getPeerInfo(peerAddress2))
      db.addToBlacklist(peerAddress2, PenaltyType.PermanentPenalty)

      db.knownPeers shouldBe Map(peerAddress1 -> peerInfo1)
    }
  }

  it should "remove peers from db correctly" in {
    withDb { db =>
      db.addOrUpdateKnownPeer(getPeerInfo(peerAddress1))
      db.isEmpty shouldBe false
      db.blacklistedPeers.isEmpty shouldBe true
      db.knownPeers.isEmpty shouldBe false

      db.remove(peerAddress1)

      db.isEmpty shouldBe true
    }
  }

  it should "blacklist immediately when a permanent penalty is applied" in {
    withDb { db =>
      db.penalize(peerAddress1, PenaltyType.SpamPenalty) shouldBe false
      db.penalize(peerAddress1, PenaltyType.PermanentPenalty) shouldBe true
    }
  }

  it should "not apply another penalty within a safe interval" in {
    withDb { db =>
      db.penalize(peerAddress1, PenaltyType.SpamPenalty)
      db.penaltyScore(peerAddress1) shouldBe PenaltyType.SpamPenalty.penaltyScore
      db.penalize(peerAddress1, PenaltyType.MisbehaviorPenalty)
      db.penaltyScore(peerAddress1) shouldBe PenaltyType.SpamPenalty.penaltyScore
    }
  }

  it should "not add more peers than configured by storedPeersLimit" in {
    withDb { db =>
      val ninePeers = generatePeers(9)
      val oneMorePeer = generatePeers(1)
      val fiveMorePeers = generatePeers(5)
      db.addOrUpdateKnownPeers(ninePeers)
      db.blacklistedPeers.isEmpty shouldBe true
      db.knownPeers.size shouldBe 9

      db.addOrUpdateKnownPeers(oneMorePeer)
      db.blacklistedPeers.isEmpty shouldBe true
      db.knownPeers.size shouldBe 10

      db.addOrUpdateKnownPeers(fiveMorePeers)
      db.blacklistedPeers.isEmpty shouldBe true
      db.knownPeers.size shouldBe 10
    }
  }

  it should "not replace existing peers if they are connected" in {
    withDb { db =>
      val peersLimit = 10
      val nineConnectedPeers = generatePeers(peersLimit - 1).map(_.copy(connectionType = Some(Outgoing)))
      val fiveMorePeers = generatePeers(5)
      db.addOrUpdateKnownPeers(nineConnectedPeers)
      db.blacklistedPeers.isEmpty shouldBe true
      db.knownPeers.size shouldBe 9

      db.addOrUpdateKnownPeers(fiveMorePeers)
      db.blacklistedPeers.isEmpty shouldBe true
      db.knownPeers.values should (contain allElementsOf nineConnectedPeers and have size 10)
    }
  }

  private def generatePeers(ammount: Int) = {
    val random = Random
    val base = (1 to 3).map(_ => random.nextInt(256)).mkString(".")
    var counter = 1
    (1 to ammount).map { _ =>
      val host = base + counter
      counter += 1
      getPeerInfo(
        new InetSocketAddress(host, 0)
      )
    }
  }

}
