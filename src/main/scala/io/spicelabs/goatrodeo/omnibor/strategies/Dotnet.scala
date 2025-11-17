package io.spicelabs.goatrodeo.omnibor.strategies

import com.github.packageurl.PackageURL
import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.ParentScope
import io.spicelabs.goatrodeo.omnibor.ProcessingState
import io.spicelabs.goatrodeo.omnibor.SingleMarker
import io.spicelabs.goatrodeo.omnibor.Storage
import io.spicelabs.goatrodeo.omnibor.StringOrPair
import io.spicelabs.goatrodeo.omnibor.ToProcess
import io.spicelabs.goatrodeo.omnibor.ToProcess.ByName
import io.spicelabs.goatrodeo.omnibor.ToProcess.ByUUID
import io.spicelabs.goatrodeo.util.ArtifactWrapper
import io.spicelabs.goatrodeo.util.GitOID
import io.spicelabs.goatrodeo.util.TreeMapExtensions.+?

import java.io.FileInputStream

import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet
import io.spicelabs.cilantro.AssemblyDefinition
import io.spicelabs.goatrodeo.omnibor.MetadataKeyConstants
import org.apache.tika.metadata.Metadata
import io.spicelabs.goatrodeo.util.Helpers.toHex
import io.spicelabs.cilantro.CustomAttribute
import io.spicelabs.goatrodeo.util.DotnetDetector
import io.spicelabs.cilantro.AssemblyNameReference
import scala.collection.mutable.ArrayBuffer
import io.spicelabs.goatrodeo.util.Helpers
import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._

class DotnetState extends ProcessingState[SingleMarker, DotnetState] {
    private var fileStm: FileInputStream = null
    private var assembly: AssemblyDefinition = null
    def beginProcessing(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
    ): DotnetState = {
      try {
        fileStm = FileInputStream(artifact.path())
        assembly = AssemblyDefinition.readAssembly(fileStm)
      } catch {
        case _ =>
          if (fileStm != null) {
            fileStm.close()
            fileStm = null
          }
      }
      this
    }
  
  override def getPurls(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (Vector[PackageURL], DotnetState) = {
    if (fileStm == null) {
      Vector.empty -> this
    } else {
      var purl = PackageURL("nuget", "", assembly.name.name, assembly.name.version.toString(), null, "")
      Vector(purl) -> this
    }
  }

  override def getMetadata(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (TreeMap[String, TreeSet[StringOrPair]], DotnetState) = {
    // some of these metadata elements may not exist in the assembly
    // under consideration. Therefore, we use the custom +? operator
    // which will add the element if it's Some(foo) and ignore it if
    // it's None.
    val tm = TreeMap[String, TreeSet[StringOrPair]]()
      +? assemblyFullName
      +? assemblyName
      +? assemblyVersion
      +? assemblyLocale
      +? assemblyPublicKey
      +? assemblyCopyright
      +? assemblyTrademark
      +? assemblyProducer
      +? assemblyDescription
      +? assemblyDependencies
    (tm, this)
  }

  def maybeSOP(k: String, v: String): Option[(String, TreeSet[StringOrPair])] =
    if v != null then Some(k, TreeSet[StringOrPair](v))
    else None


  // each of these assembly attributes should
  // 1. exist
  // 2. have a zeroth constructor argument
  // 3. that value should be type String
  // in the event that we get none of those, we return null
  def customAttributeArgumentZero(attrName: String): String = {
    if (assembly.hasCustomAttributes) {
      val attr = assembly.customAttributes.find(at => at.attributeType.fullName == attrName)
      return attr match {
        case Some(value) => argZeroValueAsString(value)
        case None => null
      }
    }
    null
  }

  // custom attributes in .NET may have constructor arguments that are
  // embedded in the assembly. In that case, when the constructor argument is read,
  // there is a certain amount of effort expended to turn it into a rarefied instance
  // this includes the standard value types (ints, bool) as well as strings.
  def argZeroValueAsString(ca: CustomAttribute): String = {
    if (ca.constructorArguments.length == 1) {
      if (ca.constructorArguments(0).value.isInstanceOf[String]) {
        val str = ca.constructorArguments(0).value.asInstanceOf[String]
        // an empty string is as good as a null for our purposes
        return if str != null && !str.isEmpty() then str else null
      }
    }
    null
  }


  def assemblyFullName: Option[(String, TreeSet[StringOrPair])] = {
    maybeSOP(MetadataKeyConstants.NAME, assembly.fullName)
  }

  def assemblyName: Option[(String, TreeSet[StringOrPair])] = {
    maybeSOP(MetadataKeyConstants.SIMPLE_NAME, assembly.name.name)
  }

  def assemblyVersion: Option[(String, TreeSet[StringOrPair])] = {
    maybeSOP(MetadataKeyConstants.VERSION, assembly.name.version.toString())
  }

  def assemblyLocale: Option[(String, TreeSet[StringOrPair])] = {
    maybeSOP(MetadataKeyConstants.LOCALE, assembly.name.culture)
  }

  def assemblyPublicKey: Option[(String, TreeSet[StringOrPair])] = {
    var pkStr = if assembly.name.publicKey != null then toHex(assembly.name.publicKey) else null
    maybeSOP(MetadataKeyConstants.PUBLIC_KEY, pkStr)
  }

  def assemblyCopyright: Option[(String, TreeSet[StringOrPair])] = {
    var cp = customAttributeArgumentZero("System.Reflection.CopyrightAttribute")
    maybeSOP(MetadataKeyConstants.COPYRIGHT, cp)
  }

  def assemblyTrademark: Option[(String, TreeSet[StringOrPair])] = {
    var tm = customAttributeArgumentZero("System.Reflection.TrademarkAttribute")
    maybeSOP(MetadataKeyConstants.TRADEMARK, tm)
  }

  def assemblyProducer: Option[(String, TreeSet[StringOrPair])] = {
    var pr = customAttributeArgumentZero("System.Reflection.AssemblyTrademarkAttribute")
    maybeSOP(MetadataKeyConstants.PUBLISHER, pr)
  }

  def assemblyDescription: Option[(String, TreeSet[StringOrPair])] = {
    var desc = customAttributeArgumentZero("System.Reflection.AssemblyDescriptionAttribute")
    maybeSOP(MetadataKeyConstants.DESCRIPTION, desc)
  }

  def assemblyDependencies: Option[(String, TreeSet[StringOrPair])] = {
    import DotnetState.formatDeps
    val refs = assembly.mainModule.assemblyReferences;
    if (refs.length == 0) return None
    val deps = formatDeps(refs)
    maybeSOP(MetadataKeyConstants.DEPENDENCIES, deps)
  }

  override def finalAugmentation(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker,
      parentScope: ParentScope,
      store: Storage
  ): (Item, DotnetState) = item -> this

  override def postChildProcessing(
      kids: Option[Vector[GitOID]],
      store: Storage,
      marker: SingleMarker
  ): DotnetState = {
    if (fileStm != null) {
      fileStm.close()
      fileStm = null
    }
    this
  }
}

object DotnetState {
  def formatDeps(deps: ArrayBuffer[AssemblyNameReference]) = {
    val sortedDeps = deps.sortBy(((elem) => elem.name))

    val json =
      ("dependencies" ->
        sortedDeps.map { dep =>
          var nameVersionToken = ("name" -> dep.name) ~
          ("version" -> dep.version.toString()) ~
          ("public_key_token" -> Helpers.toHex(dep.publicKeyToken))
          if (dep.hasPublicKey) {
            nameVersionToken = nameVersionToken ~ ("public_key" -> Helpers.toHex(dep.publicKey))
          }
          nameVersionToken
          }
      )
    compact(render(json))
  }
}

final case class DotnetFile(file: ArtifactWrapper) extends ToProcess {

  /** Call at the end of successfull completing the operation
    */
  def markSuccessfulCompletion(): Unit = {
    file.finished()
  }
  type MarkerType = SingleMarker
  type StateType = DotnetState
  override def main: String = file.path()

  override def itemCnt: Int = 1

  /** The mime type of the main artifact
    */
  def mimeType: String = DotnetDetector.DOTNET_MIME.toString()

  override def getElementsToProcess()
      : (Seq[(ArtifactWrapper, MarkerType)], StateType) =
          Vector(file -> SingleMarker()) -> DotnetState()
}

object DotnetFile {
  def computeDotnetFiles(
      byUUID: ToProcess.ByUUID,
      byName: ToProcess.ByName
  ): (Vector[ToProcess], ByUUID, ByName, String) = {
    var ret: Vector[ToProcess] = Vector()
    var retByUUID = byUUID
    val dotnetMime = DotnetDetector.DOTNET_MIME.toString()

    val retByName = byName.map { case (k, v) =>
      val isDotnet = v.filter(_.mimeType == dotnetMime)

      // no isDotnet files, just continue
      if (isDotnet.isEmpty) {
        k -> v
      } else {
        // all the non-dotnet files
        val newV = v.filter(_.mimeType != dotnetMime)

        // for each of the dotnet files, add to ret, substract from uuid
        for { dotnet <- isDotnet } {
          retByUUID -= dotnet.uuid
          ret = ret :+ DotnetFile(dotnet)
        }

        k -> newV
      }
    }

    (ret, retByUUID, retByName, "Dotnet")
  }
}