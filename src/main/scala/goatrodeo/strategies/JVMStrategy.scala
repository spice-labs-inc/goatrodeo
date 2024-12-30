package goatrodeo.strategies

import goatrodeo.util.ArtifactWrapper

case class JVMStrategy(pom: Option[ArtifactWrapper], source: Option[ArtifactWrapper], jar: ArtifactWrapper) extends Strategy {
  def processGroup() : ProcessGroup = ProcessGroup(List(pom, source, Some(jar)).flatten.map(v => () => v).iterator, (info, store, purlOut) => {})
}
