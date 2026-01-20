/* Copyright 2025-2026 David Pollak, Steve Hawley, Spice Labs, Inc. & Contributors

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

import com.github.dwickern.macros.NameOf.*
import com.typesafe.scalalogging.Logger
import io.spicelabs.rodeocomponents.API
import io.spicelabs.rodeocomponents.APIFactory
import io.spicelabs.rodeocomponents.APIFactoryReceiver
import io.spicelabs.rodeocomponents.APIFactorySource
import io.spicelabs.rodeocomponents.RodeoComponent
import io.spicelabs.rodeocomponents.RodeoEnvironment

import java.util as ju
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.HashMap
import scala.jdk.CollectionConverters.*
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import ju.ServiceLoader

private case class PublishedAPI(
    publisher: RodeoComponent,
    apiFactory: APIFactory[? <: API],
    apiType: Class[? <: API],
    subscribers: Vector[RodeoComponent] = Vector()
) {}

class RodeoHost extends APIFactoryReceiver, APIFactorySource {
  private val publishedAPIS: HashMap[String, PublishedAPI] = HashMap()
  private val log = Logger(classOf[RodeoHost])
  private val activeComponents: ArrayBuffer[RodeoComponent] = ArrayBuffer(
    BuiltInComponent.component
  )
  private val inactiveComponents: ArrayBuffer[RodeoComponent] = ArrayBuffer()
  private var hasBegun = false
  private var hasEnded = false

  def begin(): Unit = {
    if (hasBegun)
      return
    hasBegun = true
    val components = discoverComponents()
    val (active, inactive) = initializeAndfilterComponents(components)
    activeComponents.addAll(active)
    inactiveComponents.addAll(inactive)
  }

  def exportImport(): Unit = {
    if (hasEnded)
      return
    import RodeoHost.elapsedTime
    log.debug("starting component export")
    val (_, exportTime) = elapsedTime {
      activeComponents.foreach(comp => comp.exportAPIFactories(this))
    }
    log.debug(
      s"exported ${publishedAPIS.size} in ${exportTime / 1000} milliseconds"
    )
    activeComponents.foreach(comp => comp.importAPIFactories(this))
  }

  def completeLoading(): Unit = {
    if (hasEnded)
      return
    activeComponents.foreach(comp => comp.onLoadingComplete())
  }

  def end(): Unit = {
    if (hasEnded)
      return
    if (hasBegun)
      activeComponents.foreach(comp => comp.shutDown())
    hasEnded = true
  }

  def hasComponent(component: RodeoComponent) =
    isActiveComponent(component) || isInactiveComponent(component)
  def isActiveComponent(component: RodeoComponent) =
    activeComponents.contains(component)
  def isInactiveComponent(component: RodeoComponent) =
    inactiveComponents.contains(component)
  def activeComponentCount = activeComponents.size
  def inactiveComponentCount = inactiveComponents.size

  def publishedAPICount = publishedAPIS.size

  def discoverComponents(): Iterator[RodeoComponent] = {
    import RodeoHost.elapsedTime
    log.debug("starting component discovery")
    val (loader, time) = elapsedTime {
      ServiceLoader.load(classOf[RodeoComponent])
    }
    log.debug(s"discovery complete: ${time / 1000} milliseconds")
    loader.iterator.asScala
  }

  def initializeAndfilterComponents(components: Iterator[RodeoComponent]) = {
    components.partition(isComponentValid)
  }

  private def isComponentValid(component: RodeoComponent): Boolean = {
    // true is a valid component
    val initializedOK = Try {
      component.initialize()
    } match {
      case Failure(exception) =>
        log.error(
          s"Component of type ${component.getClass().toGenericString()} failed to initialize"
        )
        false
      case Success(value) => true
    }
    if (!initializedOK)
      return false

    val validIdentity = isIdentityValid(component) match {
      case Some(error) =>
        log.error(
          s"Component of type ${component.getClass().toGenericString()} $error"
        )
        false
      case None => true
    }

    // for versioning, we need to think about what is considered valid
    val validVersion =
      component.getComponentVersion() == RodeoEnvironment.currentVersion()

    validIdentity && validVersion
  }

  private def isIdentityValid(component: RodeoComponent): Option[String] = {
    val identity = Option(component.getIdentity()) match {
      // Short circuit out - nothing else is good.
      case None        => return Some(s"has a null identity")
      case Some(value) => value
    }

    val whatsWrong =
      (Option(identity.publisher()), Option(identity.name())) match {
        case (None, None)       => Some(s"has neither a publisher nor a name")
        case (Some(_), None)    => Some(s"has a null name")
        case (None, Some(_))    => Some(s"has a null publisher")
        case (Some(_), Some(_)) => None
      }
    whatsWrong
  }

  // This is the method to implrment the APIFactoryReceiver interface
  // Since this is code that will be called from, effectively, outer space we
  // need to verify all the arguments because would *you* trust alien data
  // types?
  //
  // The general idea is that we check the args, build a handy data structure to
  // hold the APIFactory, its point of origin, the type of the API, and a list of
  // current subscribers. Finally, we drop that in to a Map of apiName -> info
  override def publishFactory[T <: API](
      publisher: RodeoComponent,
      apiName: String,
      apiFactory: APIFactory[T],
      apiType: Class[T]
  ): Unit = {
    import RodeoHost.isLoggedArgError

    val thisMethod = nameOf(publishFactory)

    // TODO - should also deactivate the publisher
    if (isLoggedArgError(publisher, nameOf(publisher), thisMethod, log))
      return
    if (isLoggedArgError(apiName, nameOf(apiName), thisMethod, log))
      return
    if (isLoggedArgError(apiFactory, nameOf(apiFactory), thisMethod, log))
      return
    if (isLoggedArgError(apiType, nameOf(apiType), thisMethod, log))
      return

    val identity = publisher.getIdentity()
    if (
      isLoggedArgError(identity, nameOf(publisher.getIdentity), thisMethod, log)
    )
      return

    val theAPI = PublishedAPI(publisher, apiFactory, apiType)
    addAPI(apiName, theAPI)
  }

  // helper for adding an API, likely to be called from outside the context of
  // publishFactory. For example, goat rodeo will need to populate the host
  // with APIs for components to use.
  def addAPI(apiName: String, theAPI: PublishedAPI): Unit = {
    val componentName = theAPI.publisher.getIdentity().name()
    val apiType = theAPI.apiType.toString()
    publishedAPIS.get(apiName) match {
      case Some(existingAPI) =>
        log.error(
          s"Component $componentName attempted to publish the API $apiName of type $apiType but it has already been published by ${existingAPI.publisher.getIdentity().name()}"
        )
      case None =>
        log.debug(
          s"publishing API $apiName of type $apiType from $componentName"
        )
        publishedAPIS.addOne(apiName -> theAPI)
    }
  }

  // we may not actually need this and that's fine. I put this in strictly for
  // completeness.
  def removeAPI(apiName: String): Unit = {
    publishedAPIS.remove(apiName) match {
      case Some(api) =>
        log.debug(s"Removed $apiName of type ${api.apiType
            .toString()} published by ${api.publisher.getIdentity().name()}")
      case None =>
        log.error(s"Attempt to remove API $apiName, but it doesn't exist.")
    }
  }

  // This is the
  override def getAPIFactory[T <: API](
      name: String,
      subscriber: RodeoComponent,
      factoryType: Class[T]
  ): ju.Optional[APIFactory[T]] = {
    import RodeoHost.isLoggedArgError

    val thisMethod = nameOf(publishFactory)

    val componentName = subscriber.getIdentity().name()
    val apiType = factoryType.toString()
    val emptyReturn = ju.Optional.empty[APIFactory[T]]()

    // TODO consider deactivating subscriber
    if (isLoggedArgError(name, nameOf(name), thisMethod, log))
      return emptyReturn
    if (isLoggedArgError(subscriber, nameOf(subscriber), thisMethod, log))
      return emptyReturn
    if (isLoggedArgError(factoryType, nameOf(factoryType), thisMethod, log))
      return emptyReturn

    publishedAPIS.get(name) match {
      case Some(publishedAPI) =>
        if publishedAPI.apiType != factoryType
        then {
          log.error(
            s"Component $componentName attempted to get API $name of type $apiType but the actual type was ${publishedAPI.apiType.toString()}"
          )
          emptyReturn
        } else {
          // TODO - check to see if we're already subscribed
          log.debug(
            s"Component $componentName subscribed to API $name of type $apiType"
          )
          updateAPI(name, publishedAPI, subscriber)
          ju.Optional.of[APIFactory[T]](
            publishedAPI.apiFactory.asInstanceOf[APIFactory[T]]
          )
        }
      case None =>
        log.debug(
          s"Component $componentName attempted to subscribe to API $name, but there is no API by that name"
        )
        emptyReturn
    }
  }

  private def updateAPI(
      name: String,
      publishedAPI: PublishedAPI,
      subscriber: RodeoComponent
  ) = {
    publishedAPIS.update(
      name,
      publishedAPI.copy(subscribers = publishedAPI.subscribers :+ subscriber)
    )
  }

  def printComponentInfo() = {
    printCompListInfo(activeComponents, "Active")
    printCompListInfo(inactiveComponents, "Inactive")
  }

  private def printCompListInfo(
      comps: ArrayBuffer[RodeoComponent],
      activeLabel: String
  ) = {
    if (comps.length > 0) {
      println(s"$activeLabel components (${comps.length})")
      activeComponents.foreach((c => {
        println(
          s"Component ${c.getIdentity().name()}, published by ${c.getIdentity().publisher()}, compiled against component host version ${c.getComponentVersion()}"
        )
      }))
    }
  }
}

object RodeoHost {
  def argCheck[T](
      arg: T,
      argName: String,
      methodName: String
  ): Either[T, String] = {
    Option(arg) match {
      case Some(_) => Left(arg)
      case None    => Right(s"In ${methodName}, ${argName} was null")
    }
  }

  def isLoggedArgError[T](
      arg: T,
      argName: String,
      methodName: String,
      log: Logger
  ): Boolean = {
    argCheck(arg, argName, methodName) match {
      case Left(value) => false
      case Right(value) => {
        log.error(value)
        true
      }
    }
  }
  def elapsedTime[T](body: => T): (T, Long) = {
    val start = System.nanoTime()
    val ret = body
    val end = System.nanoTime()
    (ret, end - start)
  }

  lazy val host: RodeoHost = RodeoHost()
}
