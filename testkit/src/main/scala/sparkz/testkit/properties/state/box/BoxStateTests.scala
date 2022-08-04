package sparkz.testkit.properties.state.box

import sparkz.core.PersistentNodeViewModifier
import sparkz.core.transaction.BoxTransaction
import sparkz.core.transaction.box.Box
import sparkz.core.transaction.box.proposition.Proposition
import scorex.mid.state.BoxMinimalState
import scorex.testkit.properties.state.StateTests


trait BoxStateTests[P <: Proposition,
                    B <: Box[P],
                    TX <: BoxTransaction[P, B],
                    PM <: PersistentNodeViewModifier,
                    BST <: BoxMinimalState[P, B, TX, PM, BST]] extends StateTests[PM, BST]{
}
