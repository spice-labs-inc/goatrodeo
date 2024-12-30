package goatrodeo.strategies

import goatrodeo.util.ArtifactWrapper
import goatrodeo.omnibor.BuiltItemResult
import java.io.BufferedWriter
import goatrodeo.omnibor.Storage



object Strategy {
  
}

trait Strategy {
    def processGroup() : ProcessGroup
}

final case class ProcessGroup(artifacts: Iterator[() => ArtifactWrapper], cleanUp: (Vector[(ArtifactWrapper, BuiltItemResult)], Storage, BufferedWriter) => Unit) {
    
}
