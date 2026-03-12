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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.PriorityBlockingQueue;
import io.spicelabs.rodeocomponents.APIS.artifacts.*;
import io.spicelabs.baharat.*;

class BaharatMarker implements RodeoItemMarker {

    private Pair<List<Pair<RodeoArtifact, RodeoItemMarker>>, ArtifactHandler> items;
    private PackageFormat format;

    public BaharatMarker(RodeoArtifact artifact, ArtifactHandler handler, PackageFormat detectedFormat)
    {
        format = detectedFormat;
        ArrayList<Pair<RodeoArtifact, RodeoItemMarker>> list = new ArrayList<Pair<RodeoArtifact, RodeoItemMarker>>(1);
        list.add(new Pair(artifact, this));
        items = new Pair(list, handler);
    }

    public void onCompletion(RodeoArtifact artifact) { }
    public int length() { return 1; }
    public Pair<List<Pair<RodeoArtifact, RodeoItemMarker>>, ArtifactHandler> getItemsToProcess() {
        return items;
    }
}
