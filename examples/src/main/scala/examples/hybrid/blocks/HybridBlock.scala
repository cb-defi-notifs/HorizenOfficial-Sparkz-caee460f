package examples.hybrid.blocks

import examples.commons.SimpleBoxTransaction
import sparkz.core.PersistentNodeViewModifier
import sparkz.core.block.Block

trait HybridBlock extends PersistentNodeViewModifier with Block[SimpleBoxTransaction]
