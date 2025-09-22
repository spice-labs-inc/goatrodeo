# Goat Rodeo API

When embedding Goat Rodeo in another application, there are two main operations:
- assembling options
- execution

## Assembling Options

Goat Rodeo uses a (fluent interface)[https://en.wikipedia.org/wiki/Fluent_interface] for building options through the type `GoatRodeoBuilder`. Every method on `GoatRodeoBuilder` with the exception of one is in the form:
```java
public GoatRodeoBuilder with/*OptionName*/(AnOption option) { }
```
Meaning that every `with` method returns an instance of `GoatRodeoBuilder` with the option applied to it. Note that `GoatRodeoBuilder` itself is immutable and every `with` method will return a different instance rather than mutating one instance carried through it. Note that some of the `with` are string mutators in that they replace the existing option, but some of them are accumulators in that they add to the existing option.

There is a one to one correspondence between the `with` methods and command-line options which are documented (here)[goat_rodeo_operation.md] in the CLI Parameters section.

- `withPayload(String o)`
- `withOutput(String o)`
- `withThreads(Int t)`
- `withIngested(String i)`
- `withIgnore(String i)` - accumulator
- `withFileList(String f)` - accumulator
- `withExcludePattern(String p)` - accumulator
- `withMaxRecords(Int r)`
- `withBlockList(String b)`
- `withTempDir(String d)`
- `withTag(String t)`
- `withStatisMetadata(Boolean b)`
- `withTagJson(String t)`
- `withExtraArgs(java.util.Map<String, String> args)`
- `withExtraArg(String arg, String value)` - possibly an accumulator

`withExtraArg` is a special case in that it will allow many arguments in `arg`. The following arguments are accepted:
- payload
- output
- threads
- maxRecords
- ingested
- ignore
- fileList
- excludePattern
- blockList
- tempDir
- tag-json
- tag

Each arg corresponds to a `with` method above. The args `threads` and `maxRecords` take a String representation of an integer. The parsing is simple and will throw an exception if conversion fails.

## Running the Configuration

This done with the single `GoatRodeoBuilder` method `run`. It takes no arguments, instead operating on the `GoatRodeoBuilder` object.

