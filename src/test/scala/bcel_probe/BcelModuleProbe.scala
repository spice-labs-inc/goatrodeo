package bcel_probe

import munit.FunSuite
import org.apache.bcel.classfile.Attribute
import org.apache.bcel.classfile.JavaClass

class BcelModuleProbe extends FunSuite {
  test("JavaClass has getAttributes") {
    val jcClazz = classOf[JavaClass]
    val methods = jcClazz.getMethods.map(_.getName)
    assert(methods.contains("getAttributes"))
    println(s"getAttributes: yes")

    // Check if Module extends Attribute
    val moduleClazz = Class.forName("org.apache.bcel.classfile.Module")
    val isAttr = classOf[Attribute].isAssignableFrom(moduleClazz)
    println(s"Module extends Attribute: $isAttr")
    assert(isAttr)
  }
}
