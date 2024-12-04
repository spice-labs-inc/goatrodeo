## File Type Detection Notes

Notes on how other libraries do file type detection of things like archives, etc
and in some cases how they map "extra" types (e.g. a jar is a zip, a gem is a zip, etc... 
so we detect that they're a zip but we need to make the next step to re-narrow it based on 
actual mimetype such as `application/java-archive`)

I looked at both Apache Commons Compress, which at the time of writing we are already using in Goat Rodeo to decompress files, and Apache Tika, which has a large slate of file types it is able to detect & parse metadata from…

### How Goat Rodeo currently works


### Apache Commons Compress

Apache Commons Compress has an ability to automatically figure out what the type of a given file is, to see if it can be decompressed. The determination of whether or not it is a supported file is done by way of reading "magic" bytes from head of the archive file to determine archive type.

https://github.com/apache/commons-compress/blob/master/src/main/java/org/apache/commons/compress/archivers/ArchiveStreamFactory.java#L203-L296

When you run `detect`, it reads the header bytes of the given `InputStream`, and then compares it format by format to see if the bytes match the signature for a specific format (zip, gzip, 7zip, etc). If they do, it attempts to decompress with that format's algorithm.

If the decompress fails, an exception is thrown; there is no fallthrough behavior. 

There is no additional behavior in `detect` – it either has a set of magic bytes which match a known type, or it fails to detect.

### Apache Tika

There are two core concepts to Apache Tika:
- `Detectors` which examine a file's contents and/or metadata (such as the filename) to try to determine the mime type; there are layers of detectors that get called, as outlined below.
- `Parsers` which attempt to extract data from the `File` / `InputStream` by parsing them after detecting format. This could be the actual contents of the file, or (in a case that may aid us in filetype detection) some metadata from the file.

#### Detectors

https://tika.apache.org/3.0.0/detection.html

The `DefaultDetector` in Tika runs through a registry of Detectors, in order. By default the first detector it runs is the `MagicDetector`, which uses the magic bytes at the header of files to determine their Mime Type. Other `Detectors` are run (including just matching filename) custom user written ones until it finds one that matches.

The `MagicDetector` is driven by the [type detection XML](https://github.com/apache/tika/blob/main/tika-core/src/main/resources/org/apache/tika/mime/tika-mimetypes.xml), which has defaults and the ability to add custom types. 
The default behavior of `MagicDetector` is to match files up to the magic bytes that identifies it. By defining a custom type in Tika, however, you can say "OK if the magic number says its a zip file but it's named `*.jar`, set the mimetype to `application/java-archive`:

```xml
  <mime-type type="application/java-archive">
    <_comment>Java Archive</_comment>
    <tika:link>http://en.wikipedia.org/wiki/.jar</tika:link>
    <tika:uti>com.sun.java-archive</tika:uti>
    <sub-class-of type="application/zip"/>
    <glob pattern="*.jar"/>
  </mime-type>
```

Its also possible to add a "weight" to custom types, for where they fall in the magic match hierarchy.

```xml
  <mime-type type="hello/world-file">
     <_comment>A "Hello World" file</_comment>
     <hello>world</hello>
     <glob pattern="*.hello.world" />
     <magic priority="50">
        <match value="Hello, World!" type="string" offset="0:13" />
     </magic>
     <sub-class-of type="hello/world" />
  </mime-type>
```

Can customize the mime type detection XML; https://tika.apache.org/3.0.0/parser_guide.html#Add_your_MIME-Type

Default mime type detection…
"By default, the mime type detection in Tika is provided by `org.apache.tika.mime.MimeTypes`. This detector makes use of `tika-mimetypes.xml` to power magic based and filename based detection."

… It looks like, with a `Parser`, we could do a custom parse that pulls out a metadata block. e.g. for RPM we'd extract the binary metadata into a file that the `Parser` returns (instead of the actual full content); for say, Ruby, we could extract the metadata file in the Gem and either return that in full, or parse it into some common intermediary format (we'll probably need the i/f)
