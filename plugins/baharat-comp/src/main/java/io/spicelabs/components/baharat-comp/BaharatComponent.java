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

import io.spicelabs.rodeocomponents.*;
import io.spicelabs.rodeocomponents.APIS.purls.*;
import io.spicelabs.rodeocomponents.APIS.artifacts.*;
import io.spicelabs.rodeocomponents.APIS.logging.*;

import java.lang.Runtime.Version;
import java.util.Optional;

public class BaharatComponent implements RodeoComponent {
    private Optional<PurlAPI> purlAPIOpt;
    private Optional<ArtifactHandlerRegistrar> artifactAPIOpt;
    private Optional<RodeoLogger> loggerAPIOpt;
    private PurlAPI purlAPI = null;
    private ArtifactHandlerRegistrar artifactAPI = null;
    private RodeoLogger loggerAPI = null;
    private boolean can_run = true;
    private BaharatFilter filter = null;

    private static class BaharatComponentIdentity implements RodeoIdentity {
        public String name() { return "Baharat Component"; }
        public String publisher() { return "Spice Labs, Inc."; }
    }

    private static final BaharatComponentIdentity ID = new BaharatComponentIdentity();


    public BaharatComponent() { }

    public void initialize() throws Exception {
        if (!can_run)
            return;
        filter = new BaharatFilter(loggerAPI, purlAPI);
        artifactAPI.registerProcessFilter(filter);
    }

    public RodeoIdentity getIdentity() {
        return ID;
    }

    public Version getComponentVersion() {
        return RodeoEnvironment.currentVersion();
    }

    public void exportAPIFactories(APIFactoryReceiver receiver) { }

    public void importAPIFactories(APIFactorySource provider) {
        loggerAPIOpt = makeAPI(provider.getAPIFactory(RodeoLoggerConstants.NAME, this, RodeoLogger.class));
        purlAPIOpt = makeAPI(provider.getAPIFactory(PurlAPIConstants.NAME, this, PurlAPI.class));
        artifactAPIOpt = makeAPI(provider.getAPIFactory(ArtifactConstants.NAME, this, ArtifactHandlerRegistrar.class));
        can_run = can_run && loggerAPIOpt.isPresent();
        if (can_run)
            loggerAPI = loggerAPIOpt.get();
        can_run = can_run && purlAPIOpt.isPresent();
        if (can_run)
            purlAPI = purlAPIOpt.get();
        can_run = can_run && artifactAPIOpt.isPresent();
        if (can_run)
            artifactAPI = artifactAPIOpt.get();
    }

    public void onLoadingComplete() {
        if (!can_run) {
            if (loggerAPI != null) {
                if (purlAPI == null)
                    loggerAPI.error("unable to get PURL API");
                if (artifactAPI != null)
                    loggerAPI.error("unable to get artifact API");
            }
        } else {
            // TODO - register the BaharatFilter
        }
    }

    public void shutDown() { }

    private <T extends API> Optional<T> makeAPI(Optional<APIFactory<T>> factoryOpt) {
        if (factoryOpt.isPresent()) {
            return Optional.of(factoryOpt.get().buildAPI(this));
        } else {
            return Optional.empty();
        }
    }
}
