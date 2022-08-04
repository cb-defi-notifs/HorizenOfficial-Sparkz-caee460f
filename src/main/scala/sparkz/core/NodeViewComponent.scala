package sparkz.core

trait NodeViewComponent {
  self =>

  type NVCT >: self.type <: NodeViewComponent
}
