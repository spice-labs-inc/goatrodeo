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

import io.spicelabs.baharat.*;
import io.spicelabs.rodeocomponents.APIS.artifacts.*;
import io.spicelabs.rodeocomponents.APIS.logging.*;
import io.spicelabs.rodeocomponents.APIS.purls.*;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Optional;

public class BaharatFilter implements RodeoProcessFilter {
    RodeoLogger logger;
    PurlAPI purls;
    public BaharatFilter(RodeoLogger logger, PurlAPI purls) {
        this.logger = logger;
        this.purls = purls;
    }
    public String getName() {
        return "Baharat Filter";
    }

    public List<Triple<String, RodeoArtifact, RodeoProcessItems>> filterByName(Map<String, List<RodeoArtifact>> namesToFilter) {
        ArrayList<Triple<String, RodeoArtifact, RodeoProcessItems>> result = new ArrayList();
        namesToFilter.forEach((key, artifacts) -> {
            artifacts.forEach((artifact) -> {
                Optional<PackageFormat> packageOpt = artifact.withInputStream((is) -> {
                    try {
                        return PackageFormat.detect(is, artifact.getFilenameWithNoPath());
                    } catch (IOException ioerr) {
                        return Optional.empty();
                    }
                });
                if (packageOpt.isPresent()) {
                    logger.debug(String.format("Examined %s, found package %s", artifact.getFilenameWithNoPath(), packageOpt.get().toString()));
                    BaharatMarker marker = new BaharatMarker(artifact, new BaharatHandler(logger, purls), packageOpt.get());
                    result.add(new Triple(key, artifact, marker));
                }

            });
        });
        return result;
    }
}
