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

package io.spicelabs.goatrodeo.util

import org.apache.tika.config.TikaConfig
import org.apache.tika.detect.CompositeDetector
import org.apache.tika.detect.Detector

import java.util as ju
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

import ju.ArrayList

// notes:
// if you want to hook into tika's detection process you either need to statically create
// an xml configuration file and reference a jar with your detector(s) in it, or you
// can grab the detectors from TikaConfig and embed it and your detectors into a single
// CompositeDetector. This advice to do this came from here: https://user.tika.apache.narkive.com/iqudHf8O/add-custom-mime-type-programmatically
// which was 11 years old when I found it, so clearly not a lot is going on to improve things.
//
// The CompositeDetector is constructed from a java.util.List of Detector objects.
// The detection process works like this:
// The output MediaType is assumed to be an octet stream
// Each detector gets an oppotunity to run on the input stream and either returns a MediaType of the detected
// stream, or it returns MediaType.OCTET_STREAM.
// If the returned media type is a specialization of the current output MediatType, it will replace the
// current. Therefore, when crafting our own set of detectors the order may very well matter.
// Further, there may be an OverrideDetector in the mix that uses the metadata parameter to detect
// to set how the output should be set.

// TikaDetectorFactory is a simple class to aggregate a set of detectors that are broken into 3 categories:
// firstResponders - a list of detectors to run before the Tika detector
// tikaDetector - a detector grabbed from the TikaConfig object
// finalResponders - a list of detectors to run after the Tika detector

// Where to put a detector is going to depend upon how you're detecting. If for example, you are specifically doing a
// specialization of something that was detected previously, it would make sense to put it in the finalResponders.
// If you are doing a stand-alone detection with high confidence of true positive and true negative, it should go in
// the firstResponders.

// It wasn't clear to me in designing this how it will be configured and used, so I made it thread-safe and written
// such that the toDetector method is cached and any subsequent invocations of toDetector() won't interfere with previous
// invocations.

class TikaDetectorFactory(tika: TikaConfig, detectors: Detector*) {
  private val firstResponders = TikaDetectorFactory.toArrayBuffer(detectors)
  private val tikaDetector = tika.getDetector()
  private val finalResponders = ArrayBuffer[Detector]()
  private var detectorOpt: Option[Detector] = None

  def addFirst(detectors: Detector*) =
    this.synchronized {
      firstResponders.addAll(detectors)
      detectorOpt = None
    }
  def clearFirst() =
    this.synchronized {
      firstResponders.clear()
      detectorOpt = None
    }

  def addFinal(detectors: Detector*) =
    this.synchronized {
      finalResponders.addAll(detectors)
      detectorOpt = None
    }
  def clearFinal() =
    this.synchronized {
      finalResponders.clear()
      detectorOpt = None
    }

  def clearAll() =
    this.synchronized {
      clearFirst()
      clearFinal()
    }

  def toDetector(): Detector =
    this.synchronized {
      detectorOpt match {
        case Some(d) => d
        case None =>
          val detectors: ju.List[Detector] = ArrayList[Detector]()
          detectors.addAll(firstResponders.asJava)
          detectors.add(tikaDetector)
          detectors.addAll(finalResponders.asJava)
          val d = CompositeDetector(detectors)
          detectorOpt = Some(d)
          d
      }
    }
}

object TikaDetectorFactory {
  def toArrayBuffer(detectors: Seq[Detector]) = {
    val ab = ArrayBuffer[Detector]()
    ab.addAll(detectors)
  }
}
