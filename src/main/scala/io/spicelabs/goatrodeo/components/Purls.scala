package io.spicelabs.goatrodeo.components

/* Copyright 2025 David Pollak, Steve Hawley Spice Labs, Inc. & Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */

import io.spicelabs.rodeocomponents.APIS.purls.*
import com.github.packageurl.PackageURL
import com.github.packageurl.PackageURLBuilder
import io.spicelabs.rodeocomponents.APIFactory
import io.spicelabs.rodeocomponents.RodeoComponent
import java.net.MalformedURLException
import scala.util.Try
import scala.util.Failure
import scala.util.Success

// Steve sez:
// This is the set of classes needed to expose the PackageURL api to components.
// There are 4 pieces to make this work. For biggest to smallest:
// PurlsAPIFactory - builds the API needed by components. In the case we only ever need one
// across all components.
// Purls - the actual API - it gets called to return a new instance of a LocalPurlFactory
// LocalPurlFactory - an adapter onto a PackageURLBuilder
// PurlAdapter - an adapter onto a PackageURL

// adapter onto PackageURL to hide the implementation from components
class PurlAdapter(val purl: PackageURL) extends Purl {}

// factory for making purls
class LocalPurlFactory(
    builder: PackageURLBuilder = PackageURLBuilder.aPackageURL()
) extends PurlFactory {
  override def withSubpath(subpath: String): PurlFactory = {
    LocalPurlFactory(builder.withSubpath(subpath))
  }

  override def withNamespace(namespace: String): PurlFactory = {
    LocalPurlFactory(builder.withNamespace(namespace))
  }

  override def withVersion(name: String): PurlFactory = {
    LocalPurlFactory(builder.withVersion(name))
  }

  override def withType(`type`: String): PurlFactory = {
    LocalPurlFactory(builder.withType(`type`))
  }

  override def withQualifier(key: String, value: String): PurlFactory = {
    LocalPurlFactory(builder.withQualifier(key, value))
  }

  override def withName(name: String): PurlFactory = {
    LocalPurlFactory(builder.withName(name))
  }

  override def toPurl(): Purl = {
    Try {
      PurlAdapter(builder.build())
    } match {
      // we expose this as a MalformedURLException so there doesn't need to
      // be a component dependency onto MalformedPackagedURLException
      case Failure(exception) =>
        throw MalformedURLException(exception.getMessage())
      case Success(value) => value
    }
  }
}

// API implementation to get a purl factory
class Purls extends PurlAPI {

  override def newPurlFactory(): PurlFactory = {
    LocalPurlFactory()
  }

  override def release(): Unit = {}
}

// API factory uses a singleton - don't need anything more
class PurlsAPIFactory extends APIFactory[PurlAPI] {
  override def buildAPI(subscriber: RodeoComponent): Purls =
    PurlsAPIFactory.theAPI
  override def name(): String = PurlAPIConstants.NAME
}

object PurlsAPIFactory {
  lazy val theAPI = Purls()
}
