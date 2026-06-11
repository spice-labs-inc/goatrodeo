import org.json4s._
import org.json4s.native.JsonMethods._
class DockerDebugSuite extends munit.FunSuite {
  test("debug history") {
    val json = parse("""[
      {"created":"2025-02-14T03:28:36Z","created_by":"ADD alpine-minirootfs-3.21.3-x86_64.tar.gz / # buildkit","comment":"buildkit.dockerfile.v0"},
      {"created":"2025-02-14T03:28:36Z","created_by":"CMD [\"/bin/sh\"]","comment":"buildkit.dockerfile.v0","empty_layer":true},
      {"created":"2025-03-22T13:10:35.59966703-04:00","created_by":"COPY /src/bigtent/target/release/bigtent /bigtent # buildkit","comment":"buildkit.dockerfile.v0"},
      {"created":"2025-03-22T13:10:35.59966703-04:00","created_by":"CMD [\"/bigtent\"]","comment":"buildkit.dockerfile.v0","empty_layer":true}
    ]""")
    val arr = json.asInstanceOf[JArray].arr
    val result1 = arr.collect {
      case JObject(fields) if !fields.exists(_ == ("empty_layer", JBool(true))) =>
        fields.collectFirst { case ("created_by", JString(s)) => s }
    }.flatten.toVector
    println(s"result1 (tuple eq): $result1")
    
    val result2 = arr.collect {
      case JObject(fields) if !fields.exists {
        case ("empty_layer", JBool(true)) => true
        case _ => false
      } =>
        fields.collectFirst { case ("created_by", JString(s)) => s }
    }.flatten.toVector
    println(s"result2 (pattern match): $result2")
    assert(true)
  }
}
