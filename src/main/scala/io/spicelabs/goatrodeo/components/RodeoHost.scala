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

// The RodeoHost is a class that is used for hosting and managing the component system.
// It acts as both an APIFactoryReceiver and an APIFactorySource for components by managing lists
// of components. For now, we have a collection of "active" components and "inactive" components.
// The inactive components are components which didn't meet requirements for loading or had some
// kind of error.

/**
  * Represents an API that has been published by a component.
  *
  * @param publisher the component that published the API factory
  * @param apiFactory the factory for building the API
  * @param apiType the type of the API
  * @param subscribers a collection of subscribers to the API factory
  */
private case class PublishedAPI(
    publisher: RodeoComponent,
    apiFactory: APIFactory[? <: API],
    apiType: Class[? <: API],
    subscribers: Vector[RodeoComponent] = Vector()
) {}

/**
  * A class to act as a host for components. It manages the list of components, acts as both an APIFactoryReceiver (components export to this)
  * and an APIFactorySource (components import from this). It also provides a number of steps for managing the lifespan of components including
  * discovery, initialization, validation, exporting and importing, providing an opportunity to do late initialization and computation, and
  * clean up.
  * RodeoHost is used in two main places: Main.scala which uses it for the real component use and it is also used in tests.
  * In terms of usage, the methods called should be in this order:
  * [[this.begin()]] - discovers components then initializes and filters them
  * [[this.exportImport()]] - calls each active component to export API factories then import them
  * [[this.completeLoading()]] - calls each active component to perform late-stage initialization
  * [[this.end()]] - calls each active component to shut down
  * All of this is done within Goat Rodeo and except for tests, there really isn't any reason to call these methods.
  * In addition to the functioning of the component system, there are also a number of utility methods for looking
  * at components and APIs.
  * Internally, RodeoHost maintains a sense of whether [[this.begin()]] and [[this.end()]] has been called and uses that
  * information to prevent it from being used incorrectly.
  */
class RodeoHost extends APIFactoryReceiver, APIFactorySource {
  private val publishedAPIS: HashMap[String, PublishedAPI] = HashMap()
  private val log = Logger(classOf[RodeoHost])
  private val activeComponents: ArrayBuffer[RodeoComponent] = ArrayBuffer(
    BuiltInComponent.component
  )
  private val inactiveComponents: ArrayBuffer[RodeoComponent] = ArrayBuffer()
  private var hasBegun = false
  private var hasEnded = false

  /**
    * Performs discovery and initialization of components. Upon entry, RodeoHost marks that it this method has been
    * called. Calling begin multiple times on the same instance will have no effect. Calling begin after end will
    * have no effect.
    */
  def begin(): Unit = {
    if (hasBegun || hasEnded)
      return
    hasBegun = true
    val components = discoverComponents()
    val (active, inactive) = initializeAndfilterComponents(components)
    activeComponents.addAll(active)
    inactiveComponents.addAll(inactive)
  }

  /**
    * Performs the export and import steps for components. If begin has not yet been called or end has been called then
    * calling exportImport will have no effect.
    */
  def exportImport(): Unit = {
    if (!hasBegun || hasEnded)
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

  /**
    * Calls each component's onLoadingComplete method. If begin has not yet been called or end has been called then
    * calling completeLoading will have no effect.
    */
  def completeLoading(): Unit = {
    if (!hasBegun || hasEnded)
      return
    activeComponents.foreach(comp => comp.onLoadingComplete())
  }

  /**
   * Calls each component's shutDown method. If begin has not yet been called or end has been called then calling end
   * will have no effect.
   */
  def end(): Unit = {
    if (hasEnded)
      return
    if (hasBegun)
      activeComponents.foreach(comp => comp.shutDown())
    hasEnded = true
  }

  /**
    * A predicate to determine if a component has been detected. This searches through both active and inactive components.
    *
    * @param component the component to search for
    * @return true if the component exists, false otherwise
    */
  def hasComponent(component: RodeoComponent) =
    isActiveComponent(component) || isInactiveComponent(component)
  /**
    * A predicate to determine if a component is in the active component list.
    *
    * @param component the component to search for
    * @return true if the component exists, false otherwise
    */
  def isActiveComponent(component: RodeoComponent) =
    activeComponents.contains(component)
  /**
    * A predicate to determine if a component is in the inactive component list.
    *
    * @param component the component to search for
    * @return true if the component exists, false otherwise
    */
  def isInactiveComponent(component: RodeoComponent) =
    inactiveComponents.contains(component)
  /**
    * Gets the number of active components.
    *
    * @return the number of active components
    */
  def activeComponentCount = activeComponents.size
  /**
    * Gets the number of inactive components.
    *
    * @return the number of inactive components
    */
  def inactiveComponentCount = inactiveComponents.size

  /**
    * Gets the number of API factories that have been published.
    *
    * @return the number of published API factories
    */
  def publishedAPICount = publishedAPIS.size

  /**
    * Performs discovery of components. This is an internal method used and should not be called directly outside of tests.
    *
    * @return an iterable collection of [[io.spicelabs.rodeocomponents.RodeoComponent]] objects
    */
  def discoverComponents(): Iterator[RodeoComponent] = {
    import RodeoHost.elapsedTime
    log.debug("starting component discovery")
    val (loader, time) = elapsedTime {
      ServiceLoader.load(classOf[RodeoComponent])
    }
    log.debug(s"discovery complete: ${time / 1000} milliseconds")
    loader.iterator.asScala
  }

  /**
    * Calls the initialize method of each [[io.spicelabs.rodeocomponents.RodeoComponent]] and checks to see if the
    * component it valid. Validity is determined by a set of criteria:
    * - If initialize did not throw an exception
    * - If the identity is valid
    * - If the component API version is supported
    * There is no reason to call this method directly outside of tests.
    *
    * @param components an iterable collection of [[io.spicelabs.rodeocomponents.RodeoComponent]] objects to check
    * @return
    */
  def initializeAndfilterComponents(components: Iterator[RodeoComponent]) = {
    components.partition(isComponentValid)
  }

  // predicate to determine if a component is valid. 
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

  // Checks the identity object for validity
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

  // This is the method to implement the APIFactoryReceiver interface
  // Since this is code that will be called from, effectively, outer space we
  // need to verify all the arguments because would *you* trust alien data
  // types?
  //
  // The general idea is that we check the args, build a handy data structure to
  // hold the APIFactory, its point of origin, the type of the API, and a list of
  // current subscribers. Finally, we drop that in to a Map of apiName -> info
  /**
    * Provide a service for components to publish API factories.
    *
    * @param publisher the component publishing the API factory
    * @param apiName the name of the API
    * @param apiFactory the factory to build APIs
    * @param apiType the type of the API (**not** the API factory)
    */
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
  // publishFactory. For example, goat rodeo may need to populate the host
  // with APIs for components to use outside of the standard mechanism.

  /**
    * Internal method for adding an already validated published API factory. There is no reason to call this directly.
    *
    * @param apiName the name of the API factory
    * @param theAPI the API that has been published
    */
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
  /**
    * Remove an API factory by name. There is no reason to call this directly.
    *
    * @param apiName
    */
  def removeAPI(apiName: String): Unit = {
    publishedAPIS.remove(apiName) match {
      case Some(api) =>
        log.debug(s"Removed $apiName of type ${api.apiType
            .toString()} published by ${api.publisher.getIdentity().name()}")
      case None =>
        log.error(s"Attempt to remove API $apiName, but it doesn't exist.")
    }
  }

  /**
    * Implements the interface for importing API factories. Factories are retrieved by name then matched by type.
    *
    * @param name the name of the API to seach for
    * @param subscriber the subscriber to the API factory
    * @param factoryType the type of the API
    * @return an optional type with the API Factory if found, empty otherwise
    */
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
  // helper routint to check an arg for null and return Either the argument or an error with the method and argument
  private def argCheck[T](
      arg: T,
      argName: String,
      methodName: String
  ): Either[T, String] = {
    Option(arg) match {
      case Some(_) => Left(arg)
      case None    => Right(s"In ${methodName}, ${argName} was null")
    }
  }

  /**
    * Checks an argument for null (thanks, Java, you're the best). If the argument is null, log an error message and return true,
    * otherwise return false.
    *
    * @param arg the argument to check for null
    * @param argName the name of the argument to check
    * @param methodName the name of the method calling
    * @param log a place to log the error
    * @return true if there was an error, false otherwise
    */
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

  /**
    * Helper bit of pseudo syntax to measure the time it takes to execute a code block.
    *
    * @param body a code block to execute
    * @return a tuple with the first element being the return from the code block and a long which is the time in nanoseconds
    */
  def elapsedTime[T](body: => T): (T, Long) = {
    val start = System.nanoTime()
    val ret = body
    val end = System.nanoTime()
    (ret, end - start)
  }

  lazy val host: RodeoHost = RodeoHost()
}
