2.3.0
---------
* Updated dependencies
* [Bugfix] /node/removeFromBlackList now don't require explicitly the port number
* [Bugfix] Node was not able to get new peers other than the known ones under some configurations

2.2.0
---------
* Improved banning mechanism for deprecated nodes (nodes not updated after an hardfork will start to ban updated nodes because receiving newer blocks without a known parent - this will prevent unnecessary network traffic)

2.1.0
---------
* Added support for forger's connections prioritization
* Node synchronization improvements:
  * Optimization of lookup strategy in modifiersCache
  * Preserve the order of block during synchronization
* Added option to force only connecting to known peers
* Fixes/Improvements on the way the SyncTracker handles the internal statuses maps

2.0.3
---------
* Fix in the handshake process - start connecting to nodes only after the Synchronizer is initialized

2.0.2
---------
* P2p rate limitng feature - added tx rebroadcast when rate limiting is reenabled
* Seeder nodes support

2.0.1
---------
* P2p rate limitng feature

2.0.0-RC11
---------
* Changed library for the Bcrypt hashing algorithm and added additional unit tests

2.0.0-RC10
---------
* UPnP module removed
* DisconnectPenalty: new penalty that causes the node to close the connection with the peer
* CustomPenaltyDuration(duration: Long): a class for a custom penalty duration time
* added new endpoints:
  * GET /peers/peer/{address} -> get peer by address
  * DELETE /peers/peer -> delete a peer from the internal database
  * DELETE /peers/blacklist -> remove a peer from the blacklist
* existing endpoints changes:
  * POST /peers/blacklist -> body {"address": String, "durationInMinutes": Int} (the durationInMinutes default value is 60)
  * POST /peers/connect -> body {"address": String}
* messages renaming:
  * RandomPeerExcluding → RandomPeerForConnectionExcluding
  * Blacklisted [NetworkController.message] → DisconnectFromAddress
* configuration changes:
  * maxIncomingConnections & maxOutgoingConnections in place of maxConnections
  * penalizeNonDelivery: penalize remote peers not answering our requests during deliveryTimeout
  * messageLengthBytesLimit: max message size
  * maxRequestedPerPeer: limit for the number of modifiers to request and process at once
  * maxModifiersSpecMessageSize in place of maxPacketSize: maximum modifier spec message size
  * storageBackupInterval: interval to backup peers' storages
  * storageBackupDelay: delay to start peers' backup

2.0.0-RC9
---------
* Hotfix - mistakenly penalizing peers during synchronization

2.0.0-RC8
---------
* Application execution context name fixed.
* Peers penalization flow fix: do not increment safe interval in case of repeated penalties. 


2.0.0-RC7
---------
* CI/CD implementation
* Delivery Tracker - limit requests map per peer
* add request tracker actor
* add upper bound limit for peers database
* rename Scorex -> Sparkz
* update lib versions to latest, check and fix vulnerabilities
* KnownPeers should never be discarded
* exclude unconfirmedConnections and already active connections when selecting a new candidate peer to connect to
* akka, akkaHttp and circe updated to latest version.
* add SessionIdPeerFeature
* updating last seen & dropping inactive connections
* maven https support
* pass details message by name into ValidationState.validate
* message parse in Synchronizer trait
* Simplified check for transaction delivery
* externalizing MessageSerializer
* Fixed "too many open files" error
* moved peerSynchronizer creation to app
* Keep-Alive flag removed
* Banning peers for sending adversarially constructed messages

2.0.0-RC6
---------
* api_key header added to CORS
* Terminate node if port is in use
* akka versions 2.5.24 and 10.1.9

2.0.0-RC4
---------
* *modifierCompanions* renamed to *modifierSerializers* in *NodeViewHolder*

2.0.0-RC3
---------
* *MinimalState* interface simplification: *validate()* puled away from the basic trait
* *maxRollback* field added to *MinimalState*
* No *transactions* field with an optional value in *PersistentNodeViewModifier*,
  use *TransactionsCarryingPersistentNodeViewModifier* descendant for modifiers with transactions.
* Non-exhaustive pattern-matching fix in *NodeViewholder.pmodModify()*
* Simplification of type parameters in many classes around the whole codebase
* *FastCryptographicHash* removed
* Some obsolete code removed, such as *temp/mining* folder, *ScoreObserver* class
* Scrypto 2.0.0
* Using tagged types instead of *Array[Byte]*, *suppertagged* microframework is used for that

2.0.0-RC2
---------
* *MinimalState* interface made minimal
* protocolVersion in P2P Handshake
* Scrypto 1.2.3
* *BoxMinimalState* moved to *scorex.mid.state*

2.0.0-RC1
---------
* Transaction interface simplified (*fee* & *timestamp* fields removed)
* Scala 2.12
* IODB 0.3.1
* *reportInvalid()* in History
* Issue #19 fixed
* MapDB dependency removed
* StateChanges reworked
* TwinsCoin example improved

2.0.0-M4
--------

* IODB dependency upgraded to 0.2.+
* TwinsChain example massively improved, README on it has been added
  (see "examples" folder)
* akka-http dependency removed, Swagger updated


2.0.0-M3
--------

Serialization have been reworked throughout the core to be consistent
(see the new Serializer interface).

Handshake timeout implemented: (p2p/handshakeTimeout in settings.json)
Agent name and version have been moved to settings.json
("agent" and "version" settings)

Hybrid chain example got bugfixing and new tests.


2.0.0-M2
--------

* Wallet interface added
* MvStore dependency removed
* StoredBlockchain trait removed
* ViewSynchronizer trait removed
* Miner and MiningController are removed from the core
* Maven artefact has been renamed from "scorex-basics" to "scorex-core"
* blockFields method of a Block has been removed
