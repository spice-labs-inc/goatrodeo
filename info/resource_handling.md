# Resource Handling in Goat Rodeo

This document describes how Goat Rodeo manages resources including temporary files, file handles, and memory. Understanding these patterns is essential for contributors and for debugging resource-related issues.

---

## Overview

Goat Rodeo processes potentially thousands of artifacts, many of which are archives containing nested archives. Resource management is critical to:

- **Prevent file handle exhaustion** — "Too many open files" errors
- **Manage disk space** — Temporary files from archive extraction
- **Control memory usage** — Deciding when to keep data in memory vs. on disk

---

## Temporary Directory Lifecycle

### `FileWalker.withinArchiveStream`

The primary mechanism for archive processing is `FileWalker.withinArchiveStream`. This method:

1. **Creates a temporary directory** within the configured temp directory (or system temp)
2. **Extracts archive contents** into `ArtifactWrapper` instances (which may use the temp directory)
3. **Executes the provided function** with the extracted artifacts
4. **Cleans up the temporary directory** in a `finally` block, ensuring cleanup even on exceptions

```scala
// From FileWalker.scala:472-510
def withinArchiveStream[T](artifact: ArtifactWrapper)(
    function: Vector[ArtifactWrapper] => T
): Option[T] = {
  if (notArchive(artifact)) None
  else {
    // Create temporary directory within the configured tempDir
    val tempDir: Path = artifact.tempDir match {
      case Some(p) =>
        Files.createTempDirectory(p.toPath(), "goatrodeo_temp_dir")
      case None => Files.createTempDirectory("goatrodeo_temp_dir")
    }

    try {
      threadTempDir.set(Some(tempDir))
      tryToConstructArchiveStream(artifact, tempDir) match {
        case None => None
        case Some(artifacts -> wrapperName) =>
          try {
            Some(function(artifacts))
          } catch {
            case e: Exception =>
              logger.error(f"Failed to process ${artifact.path()}...", e)
              None
          }
      }
    } finally {
      threadTempDir.set(None)
      // Always clean up the temporary directory
      Helpers.deleteDirectory(tempDir)
    }
  }
}
```

**Key points:**

- The temp directory is created as a subdirectory of the user-configured `--tempdir` (if provided) or the system temp directory
- A `ThreadLocal` (`threadTempDir`) tracks the current temp directory for the thread
- Cleanup happens in `finally`, guaranteeing cleanup even when exceptions occur
- The method returns `None` if the artifact is not an archive or processing fails

### `FileWalker.withinTempDir`

For code that needs a temporary directory but may or may not be running inside `withinArchiveStream`, use `withinTempDir`:

```scala
// From FileWalker.scala:525-540
def withinTempDir[T](f: Path => T): T = {
  val (del, dir) = threadTempDir.get() match {
    case Some(p) => false -> p  // Reuse existing temp dir
    case _ =>
      true -> Files.createTempDirectory("goatrodeo_temp_dir")
  }

  try {
    f(dir)
  } finally {
    if (del) {
      Helpers.deleteDirectory(dir)
    }
  }
}
```

**Behavior:**

- If already inside a `withinArchiveStream` call, **reuses** the existing temp directory (no cleanup)
- If called standalone, **creates** a new temp directory and cleans it up after

**Example usage:**

```scala
// Safe to call whether or not we're inside withinArchiveStream
FileWalker.withinTempDir { tempPath =>
  val tempFile = Files.createTempFile(tempPath, "work", ".tmp").toFile()
  try {
    // Do work with tempFile
    processFile(tempFile)
  } finally {
    tempFile.delete()  // Clean up your own files
  }
}
// If we created the temp dir, it's now deleted
// If we reused an existing one, it remains for the parent to clean up
```

---

## ArtifactWrapper Resource Management

### Memory vs. Disk Decision

`ArtifactWrapper.newWrapper` decides whether to keep artifact content in memory or write to disk:

```scala
// From ArtifactWrapper.scala:233-265
def newWrapper(
    nominalPath: String,
    size: Long,
    data: InputStream,
    tempDir: Option[File],
    tempPath: Path
): ArtifactWrapper = {
  val name = fixPath(nominalPath)
  val forceTempFile = requireTempFile(name)

  // If tempDir is defined (RAM disk), use smaller threshold (64KB)
  // Otherwise use larger threshold (32MB)
  if (
    !forceTempFile && size <= (if (tempDir.isDefined) (64L * 1024L)
                               else maxInMemorySize)  // 32MB
  ) {
    // Keep in memory
    val bytes = slurpStream(data)
    ByteWrapper(bytes, name, tempDir = tempDir)
  } else {
    // Write to temp file
    val tempFile = Files.createTempFile(tempPath, "goats", ".temp").toFile()
    copyToFile(data, tempFile)
    FileWrapper(tempFile, name, tempDir = tempDir)
  }
}
```

**Thresholds:**

| Condition | Max In-Memory Size | Rationale |
|-----------|-------------------|-----------|
| `--tempdir` specified (RAM disk) | 64 KB | RAM disk is fast, prefer temp files to save heap |
| No `--tempdir` | 32 MB | Disk I/O is slow, keep more in memory |

**Forced temp files:**

Some file types always require temp files regardless of size:

```scala
// From ArtifactWrapper.scala:278-284
def requireTempFile(name: String): Boolean = {
  FilenameUtils.getExtension(name) match {
    case "dll" | "exe" => true  // .NET assemblies need files for cilantro
    case _             => false
  }
}
```

### The `finished()` Callback

Each `ArtifactWrapper` has a `finished()` method that should be called when processing completes successfully:

```scala
// FileWrapper can have a cleanup callback
final case class FileWrapper(
    wrappedFile: File,
    thePath: String,
    tempDir: Option[File],
    finishedFunc: File => Unit = f => ()  // Called by finished()
) extends ArtifactWrapper {
  def finished(): Unit = finishedFunc(wrappedFile)
}

// ByteWrapper has no cleanup needed
final case class ByteWrapper(...) extends ArtifactWrapper {
  def finished(): Unit = {}  // No-op
}
```

**Strategy implementations call `finished()`** when done processing:

```scala
// From Maven.scala:289-292
jar.finished()
pom.foreach(_.finished())
source.foreach(_.finished())
javaDoc.foreach(_.finished())

// From Docker.scala:253-255
manifest.finished()
layers.foreach { case (_, wrapper) => wrapper.finished() }
config.foreach(mi => mi.configFile.finished())

// From Generic.scala:90
file.finished()
```

---

## Stream Management

### Using `withStream`

Always use `withStream` to access artifact content — it handles stream lifecycle automatically:

```scala
// From ArtifactWrapper.scala:57-61
def withStream[T](f: InputStream => T): T = {
  Using.resource(asStream()) { stream =>
    f(stream)
  }
}
```

**Example usage:**

```scala
// Correct: Stream is automatically closed
val content = artifact.withStream { stream =>
  Helpers.slurpInputToString(stream)
}

// Correct: Computing a hash
val hash = artifact.withStream { stream =>
  Helpers.computeSHA256(stream)
}

// WRONG: Don't do this - stream may not be closed on exception
val stream = artifact.asStream()  // This is protected anyway
try {
  // work with stream
} finally {
  stream.close()
}
```

### Using `Using.resource`

For other resources, use Scala's `Using.resource`:

```scala
import scala.util.Using

// File streams
Using.resource(new FileInputStream(file)) { stream =>
  // Stream is closed when block exits
}

// Multiple resources
Using.resources(
  new FileInputStream(file1),
  new FileOutputStream(file2)
) { (in, out) =>
  Helpers.copy(in, out)
}
```

---

## Directory Cleanup

### `Helpers.deleteDirectory`

Recursively deletes a directory and all its contents:

```scala
// From Helpers.scala:578-600
def deleteDirectory(theDir: Path): Unit = {
  Files.walkFileTree(
    theDir,
    new SimpleFileVisitor[Path]() {
      override def visitFile(
          file: Path,
          attrs: BasicFileAttributes
      ): FileVisitResult = {
        Files.delete(file)
        FileVisitResult.CONTINUE
      }

      override def postVisitDirectory(
          x: Path,
          ioe: IOException
      ): FileVisitResult = {
        Files.delete(x)
        FileVisitResult.CONTINUE
      }
    }
  )
}
```

**Usage:** This is called automatically by `withinArchiveStream` and `withinTempDir`. You typically don't need to call it directly unless managing your own temp directories.

---

## Error Handling and Resource Cleanup

### Guaranteed Cleanup Pattern

Resources are cleaned up even when exceptions occur:

```scala
// The temp directory is ALWAYS cleaned up
FileWalker.withinArchiveStream(artifact) { extractedFiles =>
  // Even if this throws an exception...
  extractedFiles.foreach { file =>
    riskyOperation(file)  // May throw!
  }
}
// ...the temp directory is still deleted here
```

### File Handle Exhaustion Detection

The Builder detects "Too many open files" errors and terminates gracefully:

```scala
// From Builder.scala:399-412
case ioe: IOException => {
  if (
    ioe.getMessage() != null && ioe
      .getMessage()
      .indexOf("Too many open files") > 0
  ) {
    dead_?.set(true)  // Signal all threads to stop
    throw ioe
  }
  logger.error(f"Failed IO ${toProcess.main} ${toProcess.mimeType} ${ioe}")
}
```

### Disk Space Monitoring

When a temp directory is configured, the Builder monitors available space:

```scala
// From Builder.scala:337-360
tempDir match {
  case Some(theDir) =>
    for {
      fileStore <- Try { Files.getFileStore(theDir.toPath().toAbsolutePath()) }
      total <- Try { fileStore.getTotalSpace().toDouble }
      available <- Try { fileStore.getUsableSpace().toDouble }
    } {
      val remaining = available / total
      if (remaining < 0.05) {  // Less than 5% free
        logger.error(s"Temp filesystem ${theDir} is more than 95% full...")
        dead_?.set(true)
        throw new Exception(errorMsg)
      }
    }
  case _ => // No monitoring without explicit tempDir
}
```

---

## Best Practices

### Do

1. **Use `withStream`** for all artifact content access
2. **Call `finished()`** on artifacts when done processing
3. **Use `withinTempDir`** when you need a temp directory that may or may not exist
4. **Use `Using.resource`** for file handles and other closeable resources
5. **Let `withinArchiveStream` manage temp directories** — don't create your own

### Don't

1. **Don't hold references** to `ArtifactWrapper` instances longer than necessary
2. **Don't create temp files outside** the provided `tempPath` — they won't be cleaned up
3. **Don't catch and swallow** `IOException` without checking for "Too many open files"
4. **Don't assume** artifact content is in memory — always use `withStream`

---

## Debugging Resource Issues

### Symptoms and Solutions

| Symptom | Likely Cause | Solution |
|---------|--------------|----------|
| "Too many open files" | File handles not closed | Check for missing `withStream` usage |
| Out of disk space | Temp files not cleaned | Ensure `finished()` is called; check for exceptions bypassing cleanup |
| Out of memory | Large files in memory | Use `--tempdir` to prefer disk storage |
| Slow processing | Too much disk I/O | Use RAM disk for `--tempdir` |

### Monitoring Commands

```bash
# Watch open file handles for a process
watch -n 1 "ls -la /proc/$(pgrep -f goatrodeo)/fd | wc -l"

# Monitor temp directory usage
watch -n 5 "du -sh /path/to/tempdir"

# Check file handle limit
ulimit -n
```

---

## See Also

- [Architecture Guide](architecture.md) — Overall system design
- [Performance Tuning](goat_rodeo_operation.md#tuning-for-performance) — Optimizing resource usage
- [API Reference](goat_rodeo_api.md) — `GoatRodeoBuilder.withTempDir()`
