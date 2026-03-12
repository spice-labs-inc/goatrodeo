/* Copyright 2024-2026 David Pollak, Spice Labs, Inc. & Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.spicelabs.rodeocomponents.APIS.artifacts.*;
import io.spicelabs.rodeocomponents.APIS.purls.*;
import io.spicelabs.rodeocomponents.APIS.logging.*;

public class BaharatHandler implements ArtifactHandler {
    RodeoLogger logger;
    PurlAPI purls;

    public BaharatHandler(RodeoLogger logger, PurlAPI purls)
    {
        this.logger = logger;
        this.purls = purls;
    }
    public boolean requiresFile() { return false; }
    public ArtifactMemento begin(InputStream stream, RodeoArtifact artifact, WorkItem item, RodeoItemMarker marker) {
        return new EmptyMemento();
    }

    public ArtifactMemento begin(FileInputStream stream, RodeoArtifact artifact, WorkItem item, RodeoItemMarker marker) {
        return new EmptyMemento();
    }

    public List<Purl> getPurls(ArtifactMemento memento, RodeoArtifact artifact, WorkItem item, RodeoItemMarker marker) {
        return new ArrayList();
    }

    public List<Metadata> getMetadata(ArtifactMemento memento, RodeoArtifact artifact, WorkItem item, RodeoItemMarker marker) {
        return new ArrayList();
    }

    public WorkItem augment(ArtifactMemento memento, RodeoArtifact artifact, WorkItem item, ParentFrame parent, BackendStorage storage, RodeoItemMarker marker) {
        return item;
    }

    public void postChildProcessing(ArtifactMemento memento, Optional<List<String>> gitoids, BackendStorage storage, RodeoItemMarker marker) {

    }

    public void end(ArtifactMemento memento) {
        
    }
}
