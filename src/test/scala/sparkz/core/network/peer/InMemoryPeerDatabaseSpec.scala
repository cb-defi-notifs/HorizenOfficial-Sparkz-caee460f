package sparkz.core.network.peer

import org.scalatest.{Assertion, BeforeAndAfter}
import sparkz.ObjectGenerators
import sparkz.core.app.SparkzContext
import sparkz.core.network.NetworkTests
import sparkz.core.network.peer.PeerDatabase.{PeerConfidence, PeerDatabaseValue}

import java.net.InetSocketAddress
import scala.concurrent.duration.DurationInt

@SuppressWarnings(Array(
  "org.wartremover.warts.Null",
  "org.wartremover.warts.OptionPartial"
))
class InMemoryPeerDatabaseSpec extends NetworkTests with ObjectGenerators with BeforeAndAfter {

  private val peerAddress1 = new InetSocketAddress("1.1.1.1", 27017)
  private val peerAddress2 = new InetSocketAddress("2.2.2.2", 27017)
  private val storedPeersLimit = 10
  private val sparkzContext = SparkzContext(Seq.empty, Seq.empty, mockTimeProvider, None)

  private def withDb(test: InMemoryPeerDatabase => Assertion): Assertion =
    test(new InMemoryPeerDatabase(settings.network.copy(storedPeersLimit = storedPeersLimit, penaltySafeInterval = 1.seconds), sparkzContext))

  "new DB" should "be empty" in {
    withDb { db =>
      db.isEmpty shouldBe true
      db.blacklistedPeers.isEmpty shouldBe true
      db.allPeers.isEmpty shouldBe true
      db.allPeers.isEmpty shouldBe true
    }
  }

  it should "be non-empty after adding a peer" in {
    withDb { db =>
      db.addOrUpdateKnownPeer(PeerDatabaseValue(peerAddress1, getPeerInfo(peerAddress1), PeerConfidence.Unknown))
      db.isEmpty shouldBe false
      db.blacklistedPeers.isEmpty shouldBe true
      db.allPeers.isEmpty shouldBe false
    }
  }

  it should "return a peer after adding a peer" in {
    withDb { db =>
      val peerInfo = getPeerInfo(peerAddress1)

      db.addOrUpdateKnownPeer(PeerDatabaseValue(peerAddress1, peerInfo, PeerConfidence.Unknown))
      val peers = db.allPeers

      peers.size shouldBe 1
      peers.contains(peerAddress1) shouldBe true
    }
  }

  it should "return an updated peer after updating a peer" in {
    withDb { db =>
      val peerInfo = getPeerInfo(peerAddress1, Some("initialName"))
      val peerDatabaseOld = PeerDatabaseValue(peerAddress1, peerInfo, PeerConfidence.Unknown)
      db.addOrUpdateKnownPeer(peerDatabaseOld)

      val newPeerInfo = getPeerInfo(peerAddress1, Some("updatedName"))
      val peerDatabaseNew = PeerDatabaseValue(peerAddress1, newPeerInfo, PeerConfidence.Unknown)
      db.addOrUpdateKnownPeer(peerDatabaseNew)

      db.allPeers shouldBe Map(peerAddress1 -> peerDatabaseNew)
    }
  }

  it should "return an updated peer after updating" in {
    withDb { db =>
      val peerInfo = getPeerInfo(peerAddress1, Some("initialName"))
      val peerDatabaseOld = PeerDatabaseValue(peerAddress1, peerInfo, PeerConfidence.Unknown)
      // First time we see the peer
      db.addOrUpdateKnownPeer(peerDatabaseOld)

      val newPeerInfo = getPeerInfo(peerAddress1, Some("updatedName"))
      val peerDatabaseNew = PeerDatabaseValue(peerAddress1, newPeerInfo, PeerConfidence.Unknown)
      // We want to update it and make this peer tried
      db.addOrUpdateKnownPeer(peerDatabaseNew)

      val newPeerInfo2 = getPeerInfo(peerAddress1, Some("updatedNameSecondTime"))
      val peerDatabaseNew2 = PeerDatabaseValue(peerAddress1, newPeerInfo2, PeerConfidence.Unknown)
      // We want to update its name
      db.addOrUpdateKnownPeer(peerDatabaseNew2)

      db.allPeers shouldBe Map(peerAddress1 -> peerDatabaseNew2)

      val newPeerInfoConfidence = getPeerInfo(peerAddress1, Some("updatedNameSecondTime"))
      val peerDatabaseNewConfidence = PeerDatabaseValue(peerAddress1, newPeerInfoConfidence, PeerConfidence.Medium)
      // We want to update its confidence
      db.addOrUpdateKnownPeer(peerDatabaseNewConfidence)

      db.allPeers shouldBe Map(peerAddress1 -> peerDatabaseNewConfidence)
    }
  }

  it should "return a blacklisted peer after blacklisting" in {
    withDb { db =>
      db.addOrUpdateKnownPeer(PeerDatabaseValue(peerAddress1, getPeerInfo(peerAddress1), PeerConfidence.Unknown))
      db.addOrUpdateKnownPeer(PeerDatabaseValue(peerAddress1, getPeerInfo(peerAddress2), PeerConfidence.Unknown))
      db.addToBlacklist(peerAddress1, PenaltyType.PermanentPenalty)

      db.isBlacklisted(peerAddress1.getAddress) shouldBe true
      db.isBlacklisted(peerAddress2.getAddress) shouldBe false
      db.blacklistedPeers shouldBe Seq(peerAddress1.getAddress)
    }
  }

  it should "the blacklisted peer be absent in knownPeers" in {
    withDb { db =>
      val peerInfo1 = getPeerInfo(peerAddress1)
      val peerDatabaseValue1 = PeerDatabaseValue(peerAddress1, peerInfo1, PeerConfidence.Unknown)
      db.addOrUpdateKnownPeer(peerDatabaseValue1)

      val peerInfo2 = getPeerInfo(peerAddress2)
      db.addOrUpdateKnownPeer(PeerDatabaseValue(peerAddress2, peerInfo2, PeerConfidence.Unknown))
      db.addToBlacklist(peerAddress2, PenaltyType.PermanentPenalty)

      db.allPeers shouldBe Map(peerAddress1 -> peerDatabaseValue1)
    }
  }

  it should "not add the peer if it exists in the blacklist" in {
    withDb { db =>
      val peer = PeerDatabaseValue(peerAddress1, getPeerInfo(peerAddress1), PeerConfidence.Unknown)
      db.addOrUpdateKnownPeer(peer)
      db.addToBlacklist(peerAddress1, PenaltyType.PermanentPenalty)
      db.addOrUpdateKnownPeers(Seq(peer))

      db.isBlacklisted(peerAddress1.getAddress) shouldBe true
      db.blacklistedPeers shouldBe Seq(peerAddress1.getAddress)
      db.allPeers.isEmpty shouldBe true
    }
  }

  it should "remove a peer from blacklist" in {
    withDb { db =>
      val peer = PeerDatabaseValue(peerAddress1, getPeerInfo(peerAddress1), PeerConfidence.Unknown)
      db.addOrUpdateKnownPeer(peer)
      db.addToBlacklist(peerAddress1, PenaltyType.PermanentPenalty)
      db.removeFromBlacklist(peerAddress1.getAddress)

      db.isBlacklisted(peerAddress1.getAddress) shouldBe false
      db.blacklistedPeers shouldBe Seq()
      db.allPeers.isEmpty shouldBe true
    }
  }

  it should "remove peers from db correctly" in {
    withDb { db =>
      db.addOrUpdateKnownPeer(PeerDatabaseValue(peerAddress1, getPeerInfo(peerAddress1), PeerConfidence.Unknown))
      db.isEmpty shouldBe false
      db.blacklistedPeers.isEmpty shouldBe true
      db.allPeers.isEmpty shouldBe false

      db.remove(peerAddress1)

      db.isEmpty shouldBe true
    }
  }

  it should "get peer by address" in {
    withDb { db =>
      val peerDatabaseValue = PeerDatabaseValue(peerAddress1, getPeerInfo(peerAddress1), PeerConfidence.Unknown)
      db.addOrUpdateKnownPeer(peerDatabaseValue)

      val retrievedPeer = db.get(peerAddress1)
      retrievedPeer shouldNot be(empty)

      val peer = retrievedPeer.getOrElse(fail("Unexpected error in test"))
      peer shouldBe peerDatabaseValue
    }
  }

  it should "return None if peer does not exist" in {
    withDb { db =>
      val retrievedPeer = db.get(peerAddress1)
      retrievedPeer shouldBe empty
    }
  }

  it should "blacklist immediately when a permanent penalty is applied" in {
    withDb { db =>
      db.peerPenaltyScoreOverThreshold(peerAddress1, PenaltyType.SpamPenalty) shouldBe false
      db.peerPenaltyScoreOverThreshold(peerAddress1, PenaltyType.PermanentPenalty) shouldBe true
    }
  }

  it should "not apply another penalty within a safe interval" in {
    withDb { db =>
      db.peerPenaltyScoreOverThreshold(peerAddress1, PenaltyType.SpamPenalty)
      db.penaltyScore(peerAddress1) shouldBe PenaltyType.SpamPenalty.penaltyScore
      db.peerPenaltyScoreOverThreshold(peerAddress1, PenaltyType.MisbehaviorPenalty)
      db.penaltyScore(peerAddress1) shouldBe PenaltyType.SpamPenalty.penaltyScore
    }
  }

  it should "not update penalty timestamp on consecutive violations during safeInterval" in {
    withDb { db =>
      // penalty interval is 1 second
      // 1. ~0.0 -> first penalty  - applied
      // 2. ~0.0 -> second penalty - not applied
      // 3. ~0.6 -> third penalty  - not applied and not updating timestamp
      // 4. ~1.2 -> fourth penalty - applied
      val address = new InetSocketAddress("192.168.31.1", 7280)
      val penalty = PenaltyType.SpamPenalty
      // ==1==
      db.peerPenaltyScoreOverThreshold(address, penalty)
      db.penaltyScore(address) shouldBe penalty.penaltyScore
      // ==2==
      db.peerPenaltyScoreOverThreshold(address, penalty)
      db.penaltyScore(address) shouldBe penalty.penaltyScore
      // ==3==
      Thread.sleep(600)
      db.peerPenaltyScoreOverThreshold(address, penalty)
      db.penaltyScore(address) shouldBe penalty.penaltyScore
      // ==4==
      Thread.sleep(600)
      db.peerPenaltyScoreOverThreshold(address, penalty)
      db.penaltyScore(address) shouldBe penalty.penaltyScore * 2
    }
  }

  it should "penalize a peer by banning it" in {
    withDb { db =>
      val address = new InetSocketAddress("192.168.31.1", 7280)
      val penaltyType = PenaltyType.DisconnectPenalty(settings.network)

      val penalizeWithBan = db.peerPenaltyScoreOverThreshold(address, penaltyType)

      penalizeWithBan shouldBe true
    }
  }

  it should "retrieve a knownPeer" in {
    val firstAddress = new InetSocketAddress(10)
    val knownPeers = Seq(firstAddress)

    def withDbHavingKnownPeers(test: InMemoryPeerDatabase => Assertion): Assertion =
      test(new InMemoryPeerDatabase(
        settings.network.copy(storedPeersLimit = storedPeersLimit, penaltySafeInterval = 1.seconds, knownPeers = knownPeers),
        sparkzContext
      ))

    withDbHavingKnownPeers { db =>
      val peerDatabaseValueOne = PeerDatabaseValue(firstAddress, getPeerInfo(firstAddress), PeerConfidence.Unknown)

      db.addOrUpdateKnownPeer(peerDatabaseValueOne)

      val retrievedKnownPeer = db.get(firstAddress)
      retrievedKnownPeer shouldNot be(empty)

      val peerDataValue = retrievedKnownPeer.getOrElse(fail("Unexpected error in test"))
      peerDataValue.address shouldBe firstAddress
    }
  }

  it should "not add a peer if it's already a knownPeer" in {
    val firstAddress = new InetSocketAddress(10)
    val secondAddress = new InetSocketAddress(11)
    val thirdAddress = new InetSocketAddress(12)
    val knownPeers = Seq(firstAddress, secondAddress, thirdAddress)

    def withDbHavingKnownPeers(test: InMemoryPeerDatabase => Assertion): Assertion =
      test(new InMemoryPeerDatabase(
        settings.network.copy(storedPeersLimit = storedPeersLimit, penaltySafeInterval = 1.seconds, knownPeers = knownPeers),
        sparkzContext
      ))

    withDbHavingKnownPeers { db =>
      val peerDatabaseValueOne = PeerDatabaseValue(firstAddress, getPeerInfo(firstAddress), PeerConfidence.Unknown)
      val peerDatabaseValueThree = PeerDatabaseValue(thirdAddress, getPeerInfo(thirdAddress), PeerConfidence.Unknown)

      db.addOrUpdateKnownPeer(peerDatabaseValueOne)
      db.addOrUpdateKnownPeer(peerDatabaseValueThree)

      val allPeers = db.allPeers
      allPeers.foreach(p => p._2.confidence shouldBe PeerConfidence.High)
      allPeers.contains(firstAddress) shouldBe true
      allPeers.contains(secondAddress) shouldBe true
      allPeers.contains(thirdAddress) shouldBe true
    }
  }
}