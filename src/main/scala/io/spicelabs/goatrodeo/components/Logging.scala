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

package io.spicelabs.goatrodeo.components

import com.typesafe.scalalogging.Logger
import io.spicelabs.rodeocomponents.APIS.RodeoLogger
import io.spicelabs.rodeocomponents.RodeoComponent
import io.spicelabs.rodeocomponents.APIFactory
import io.spicelabs.rodeocomponents.APIS.RodeoLoggerConstants

class LoggingAPI(subscriber: RodeoComponent) extends RodeoLogger {
    private val log = Logger(subscriber.getClass())
    override def release() = { }
    override def debug(message: String): Unit = log.debug(message)
    override def error(message: String): Unit = log.error(message)
    override def error(message: String, cause: Throwable): Unit = log.error(message, cause)
    override def info(message: String): Unit = log.info(message)
    override def warn(message: String): Unit = log.warn(message)
}

class LoggingAPIFactory extends APIFactory[RodeoLogger] {
    override def buildAPI(subscriber: RodeoComponent): RodeoLogger = LoggingAPI(subscriber)
    override def name(): String = RodeoLoggerConstants.NAME
}
