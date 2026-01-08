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
import io.spicelabs.rodeocomponents.APIS.arguments.RodeoArgumentConstants
import io.spicelabs.rodeocomponents.APIS.arguments.RodeoArgumentRegistrar
import io.spicelabs.rodeocomponents.APIS.arguments.RodeoArgumentListener
import io.spicelabs.rodeocomponents.APIFactory
import io.spicelabs.rodeocomponents.RodeoComponent
import scala.collection.mutable.ArrayBuffer
import com.typesafe.scalalogging.Logger

/**
  * This is the implementation of the command line argument API for components.
  * Command line arguments will be accumulated prior to discovering components. Command line argument listeners will
  * be called after import and before onLoadingComplete. Therefore component listeners should register during the import step.
  *
  * @param name The name of the component that wants to listen to arguments
  */
class Arguments(private val name: String) extends RodeoArgumentRegistrar {
    override def register(listener: RodeoArgumentListener): Unit = {
        Arguments.addListener(name, listener)
    }
    override def release(): Unit = { }
}

object Arguments {
    lazy val logger = Logger(getClass())
    val listeners = ArrayBuffer[(String, RodeoArgumentListener)]()

    /**
      * Adds an argument listener to the list of existing listeners.
      *
      * @param name the name of the component listening
      * @param listener an interface to call with command line arguments
      */
    def addListener(name: String, listener: RodeoArgumentListener): Unit = {
        if (listener == null) {
            logger.warn(s"Component $name attempting to register null listener; ignoring")
            return
        }
        listeners.synchronized {
            if (listeners.exists((n, l) => n == name)) {
                logger.warn(s"Component $name attempting to register duplicate argument listener; ignoring")
            } else {
                listeners.addOne(name -> listener)
            }
        }
    }


    def printDescriptions() = {
        listeners.synchronized {
            listeners.foreach( (name, listener) => {
                println(s"Component $name: ${listener.getDescription()}")
            })
        }       
    }

    // The arguments are held as a map of component name -> Vector[parameters]
    // parameters is an array of string which does NOT include the component name.
    // Therefore, if you get the command line argument --component foo,bar,baz, this will
    // show up in the map as foo -> Vector(Array("bar", "baz"))
    // If there are multiple arguments (eg, --component foo,bar,baz --component foo,verbose), the
    // vector will contain multiple entries: foo -> Vector(Array("bar", "baz"), Array("verbose")).
    // If the same argument appears multiple times, there will be a vector entry for each one.
    def processComponentArguments(arguments: Map[String, Vector[Array[String]]]): Unit = {
        listeners.synchronized {

            // why the ugly filter?
            // it finds arguments for which we have listeners with a side-effect of
            // logging errors for every set of arguments that don't have a matching component
            // This means that when this is done, onlyActualArgs is a map
            // that will have matching listeners.
            // This, however, doesn't mean that there are args for every listener.
            // This is possibly acceptable (component rules depending) and every listener will get
            // called with either a list of argument lists or nothing, which is fine.
            val onlyActualArgs = arguments.filter((compName, args) => {
                listeners.find((n, l) => {
                    n == compName
                }) match {
                    case Some(value) => true
                    case None =>
                        logger.warn(s"No component argument listener found named $compName")
                        false
                }
            })

            listeners.foreach ((name, listener) => {
                val args = onlyActualArgs.get(name)
                processArguments(name, listener, args)
            })
        }
    }

    private def processArguments(name: String, listener: RodeoArgumentListener, args: Option[Vector[Array[String]]]): Unit = {
        // every listener gets begin
        var memento = listener.begin()

        // only listeners with arguments get calls to receiveArgument
        args.foreach(argList => {
            argList.foreach(args => {
                import scala.jdk.CollectionConverters._
                val jargs = args.toList.asJava
                val result = listener.receiveArgument(jargs, memento)
                if (result.hasError()) {
                    logger.error(s"Component $name reported and error processing '${args.mkString}': ${result.getError().get()}")
                } else {
                    memento = result.getMemento().get()
                }
            })
        })

        // every listener gets an end
        var finalResult = listener.end(memento)
        if (finalResult.hasError()) {
            logger.error(s"Component $name reported an error finishing argument processing: ${finalResult.getError().get()}")
        }
    }

    def addArgs(name: String, newArgs: Array[String], argsSoFar: Map[String, Vector[Array[String]]]): Map[String, Vector[Array[String]]] = {
        argsSoFar.get(name) match {
            case Some(existingSet) => argsSoFar + (name -> (existingSet :+ newArgs))
            case None => argsSoFar + (name -> Vector(newArgs))
        }
    }

}

class ArgumentsFactory extends APIFactory[RodeoArgumentRegistrar] {
    override def buildAPI(subscriber: RodeoComponent): RodeoArgumentRegistrar = {
        Arguments(subscriber.getIdentity().name())
    }
    override def name(): String = RodeoArgumentConstants.NAME
}
