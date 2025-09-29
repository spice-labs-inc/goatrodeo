import io.spicelabs.goatrodeo.GoatRodeoBuilder
import org.apache.tika.config.TikaConfig
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.Metadata
import org.apache.tika.metadata.TikaCoreProperties
import org.apache.tika.mime.MediaType
import io.spicelabs.goatrodeo.util.ArtifactWrapper
import java.io.File

class DotNetTesting extends munit.FunSuite {
    test("get-me-a-mime") {
        val path = "test_data/Smoke.dll"
        val metadata = new Metadata()
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, path)
        val input = TikaInputStream.get(File(path), metadata)
        val mime = ArtifactWrapper.mimeTypeFor(input, path)
        assertEquals("application/x-msdownload; format=pe32-dotnet", mime)
    }
    test("get-me-a-mime-exe") {
        val path = "test_data/hackproj.dll"
        val metadata = new Metadata()
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, path)
        val input = TikaInputStream.get(File(path), metadata)
        val mime = ArtifactWrapper.mimeTypeFor(input, path)
        assertEquals("application/x-msdownload; format=pe32-dotnet", mime)
    }
}
