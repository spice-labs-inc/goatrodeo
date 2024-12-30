package goatrodeo.strategies

import goatrodeo.util.ArtifactWrapper

case class SingleArtifactStrategy(f: ArtifactWrapper) extends Strategy {

  def processGroup(): ProcessGroup =
    ProcessGroup(List(() => f).iterator, (info, store, purlOut) => {})

}
