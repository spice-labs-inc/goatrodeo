Some notes on parsing the Ruby Gem Metadata file with Snake YAML.

Using the "convert me directly to an object or map" code doesn't seem to work with the weird annotated semi-minified syntax of the metadata, but it seems to work ok, with the event library.

Here's the `java-properties.gem` metadata file sample:
```
--- !ruby/object:Gem::Specification
name: java-properties
version: !ruby/object:Gem::Version
  version: 0.3.0
platform: ruby
authors:
- Jonas Thiel
autorequire:
bindir: bin
cert_chain: []
date: 2021-02-26 00:00:00.000000000 Z
dependencies:
- !ruby/object:Gem::Dependency
  name: rake
  requirement: !ruby/object:Gem::Requirement
    requirements:
    - - "~>"
      - !ruby/object:Gem::Version
        version: '13.0'
  type: :development
  prerelease: false
  version_requirements: !ruby/object:Gem::Requirement
    requirements:
    - - "~>"
      - !ruby/object:Gem::Version
        version: '13.0'
- !ruby/object:Gem::Dependency
  name: inch
  requirement: !ruby/object:Gem::Requirement
    requirements:
    - - "~>"
      - !ruby/object:Gem::Version
        version: '0.8'
  type: :development
  prerelease: false
  version_requirements: !ruby/object:Gem::Requirement
    requirements:
    - - "~>"
      - !ruby/object:Gem::Version
        version: '0.8'
- !ruby/object:Gem::Dependency
  name: minitest
  requirement: !ruby/object:Gem::Requirement
    requirements:
    - - "~>"
      - !ruby/object:Gem::Version
        version: '5.14'
  type: :development
  prerelease: false
  version_requirements: !ruby/object:Gem::Requirement
    requirements:
    - - "~>"
      - !ruby/object:Gem::Version
        version: '5.14'
- !ruby/object:Gem::Dependency
  name: coveralls
  requirement: !ruby/object:Gem::Requirement
    requirements:
    - - "~>"
      - !ruby/object:Gem::Version
        version: '0.8'
  type: :development
  prerelease: false
  version_requirements: !ruby/object:Gem::Requirement
    requirements:
    - - "~>"
      - !ruby/object:Gem::Version
        version: '0.8'
description: Tool for loading and writing Java properties files
email:
- jonas@thiel.io
executables: []
extensions: []
extra_rdoc_files: []
files:
- LICENSE
- README.md
- Rakefile
- java-properties.gemspec
- lib/java-properties.rb
- lib/java-properties/encoding.rb
- lib/java-properties/encoding/separators.rb
- lib/java-properties/encoding/special_chars.rb
- lib/java-properties/encoding/unicode.rb
- lib/java-properties/generating.rb
- lib/java-properties/generating/generator.rb
- lib/java-properties/parsing.rb
- lib/java-properties/parsing/normalizer.rb
- lib/java-properties/parsing/parser.rb
- lib/java-properties/properties.rb
- lib/java-properties/version.rb
- spec/fixtures/bom.properties
- spec/fixtures/test.properties
- spec/fixtures/test_normalized.properties
- spec/fixtures/test_out.properties
- spec/fixtures/test_out_skip_separators.properties
- spec/fixtures/test_out_skip_special_chars.properties
- spec/fixtures/test_out_skip_unicode.properties
homepage: https://github.com/jnbt/java-properties
licenses:
- MIT
metadata: {}
post_install_message:
rdoc_options: []
require_paths:
- lib
required_ruby_version: !ruby/object:Gem::Requirement
  requirements:
  - - ">="
    - !ruby/object:Gem::Version
      version: 2.0.0
required_rubygems_version: !ruby/object:Gem::Requirement
  requirements:
  - - ">="
    - !ruby/object:Gem::Version
      version: 1.3.5
requirements: []
rubygems_version: 3.2.3
signing_key:
specification_version: 4
summary: Loader and writer for *.properties files
test_files:
- spec/fixtures/bom.properties
- spec/fixtures/test.properties
- spec/fixtures/test_normalized.properties
- spec/fixtures/test_out.properties
- spec/fixtures/test_out_skip_separators.properties
- spec/fixtures/test_out_skip_special_chars.properties
- spec/fixtures/test_out_skip_unicode.properties
```
Here's a stream of events for `java-properties.gem`'s metadata file

```
 <org.yaml.snakeyaml.events.StreamStartEvent()>
 <org.yaml.snakeyaml.events.DocumentStartEvent()>
 <org.yaml.snakeyaml.events.MappingStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=name)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=java-properties)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=version)>
 <org.yaml.snakeyaml.events.MappingStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=version)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=0.3.0)>
 <org.yaml.snakeyaml.events.MappingEndEvent()>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=platform)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=ruby)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=authors)>
 <org.yaml.snakeyaml.events.SequenceStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=Jonas Thiel)>
 <org.yaml.snakeyaml.events.SequenceEndEvent()>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=autorequire)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=bindir)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=bin)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=cert_chain)>
 <org.yaml.snakeyaml.events.SequenceStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.SequenceEndEvent()>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=date)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=2021-02-26 00:00:00.000000000 Z)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=dependencies)>
 <org.yaml.snakeyaml.events.SequenceStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.MappingStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=name)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=rake)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=requirement)>
 <org.yaml.snakeyaml.events.MappingStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=requirements)>
 <org.yaml.snakeyaml.events.SequenceStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.SequenceStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: '"',implicit=[false, true], value=~>)>
 <org.yaml.snakeyaml.events.MappingStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=version)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: ''',implicit=[false, true], value=13.0)>
 <org.yaml.snakeyaml.events.MappingEndEvent()>
 <org.yaml.snakeyaml.events.SequenceEndEvent()>
 <org.yaml.snakeyaml.events.SequenceEndEvent()>
 <org.yaml.snakeyaml.events.MappingEndEvent()>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=type)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=:development)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=prerelease)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=false)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=version_requirements)>
 <org.yaml.snakeyaml.events.MappingStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=requirements)>
 <org.yaml.snakeyaml.events.SequenceStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.SequenceStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: '"',implicit=[false, true], value=~>)>
 <org.yaml.snakeyaml.events.MappingStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=version)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: ''',implicit=[false, true], value=13.0)>
 <org.yaml.snakeyaml.events.MappingEndEvent()>
 <org.yaml.snakeyaml.events.SequenceEndEvent()>
 <org.yaml.snakeyaml.events.SequenceEndEvent()>
 <org.yaml.snakeyaml.events.MappingEndEvent()>
 <org.yaml.snakeyaml.events.MappingEndEvent()>
 <org.yaml.snakeyaml.events.MappingStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=name)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=inch)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=requirement)>
 <org.yaml.snakeyaml.events.MappingStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=requirements)>
 <org.yaml.snakeyaml.events.SequenceStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.SequenceStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: '"',implicit=[false, true], value=~>)>
 <org.yaml.snakeyaml.events.MappingStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=version)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: ''',implicit=[false, true], value=0.8)>
 <org.yaml.snakeyaml.events.MappingEndEvent()>
 <org.yaml.snakeyaml.events.SequenceEndEvent()>
 <org.yaml.snakeyaml.events.SequenceEndEvent()>
 <org.yaml.snakeyaml.events.MappingEndEvent()>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=type)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=:development)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=prerelease)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=false)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=version_requirements)>
 <org.yaml.snakeyaml.events.MappingStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=requirements)>
 <org.yaml.snakeyaml.events.SequenceStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.SequenceStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: '"',implicit=[false, true], value=~>)>
 <org.yaml.snakeyaml.events.MappingStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=version)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: ''',implicit=[false, true], value=0.8)>
 <org.yaml.snakeyaml.events.MappingEndEvent()>
 <org.yaml.snakeyaml.events.SequenceEndEvent()>
 <org.yaml.snakeyaml.events.SequenceEndEvent()>
 <org.yaml.snakeyaml.events.MappingEndEvent()>
 <org.yaml.snakeyaml.events.MappingEndEvent()>
 <org.yaml.snakeyaml.events.MappingStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=name)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=minitest)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=requirement)>
 <org.yaml.snakeyaml.events.MappingStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=requirements)>
 <org.yaml.snakeyaml.events.SequenceStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.SequenceStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: '"',implicit=[false, true], value=~>)>
 <org.yaml.snakeyaml.events.MappingStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=version)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: ''',implicit=[false, true], value=5.14)>
 <org.yaml.snakeyaml.events.MappingEndEvent()>
 <org.yaml.snakeyaml.events.SequenceEndEvent()>
 <org.yaml.snakeyaml.events.SequenceEndEvent()>
 <org.yaml.snakeyaml.events.MappingEndEvent()>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=type)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=:development)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=prerelease)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=false)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=version_requirements)>
 <org.yaml.snakeyaml.events.MappingStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=requirements)>
 <org.yaml.snakeyaml.events.SequenceStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.SequenceStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: '"',implicit=[false, true], value=~>)>
 <org.yaml.snakeyaml.events.MappingStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=version)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: ''',implicit=[false, true], value=5.14)>
 <org.yaml.snakeyaml.events.MappingEndEvent()>
 <org.yaml.snakeyaml.events.SequenceEndEvent()>
 <org.yaml.snakeyaml.events.SequenceEndEvent()>
 <org.yaml.snakeyaml.events.MappingEndEvent()>
 <org.yaml.snakeyaml.events.MappingEndEvent()>
 <org.yaml.snakeyaml.events.MappingStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=name)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=coveralls)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=requirement)>
 <org.yaml.snakeyaml.events.MappingStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=requirements)>
 <org.yaml.snakeyaml.events.SequenceStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.SequenceStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: '"',implicit=[false, true], value=~>)>
 <org.yaml.snakeyaml.events.MappingStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=version)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: ''',implicit=[false, true], value=0.8)>
 <org.yaml.snakeyaml.events.MappingEndEvent()>
 <org.yaml.snakeyaml.events.SequenceEndEvent()>
 <org.yaml.snakeyaml.events.SequenceEndEvent()>
 <org.yaml.snakeyaml.events.MappingEndEvent()>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=type)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=:development)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=prerelease)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=false)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=version_requirements)>
 <org.yaml.snakeyaml.events.MappingStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=requirements)>
 <org.yaml.snakeyaml.events.SequenceStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.SequenceStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: '"',implicit=[false, true], value=~>)>
 <org.yaml.snakeyaml.events.MappingStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=version)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: ''',implicit=[false, true], value=0.8)>
 <org.yaml.snakeyaml.events.MappingEndEvent()>
 <org.yaml.snakeyaml.events.SequenceEndEvent()>
 <org.yaml.snakeyaml.events.SequenceEndEvent()>
 <org.yaml.snakeyaml.events.MappingEndEvent()>
 <org.yaml.snakeyaml.events.MappingEndEvent()>
 <org.yaml.snakeyaml.events.SequenceEndEvent()>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=description)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=Tool for loading and writing Java properties files)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=email)>
 <org.yaml.snakeyaml.events.SequenceStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=jonas@thiel.io)>
 <org.yaml.snakeyaml.events.SequenceEndEvent()>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=executables)>
 <org.yaml.snakeyaml.events.SequenceStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.SequenceEndEvent()>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=extensions)>
 <org.yaml.snakeyaml.events.SequenceStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.SequenceEndEvent()>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=extra_rdoc_files)>
 <org.yaml.snakeyaml.events.SequenceStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.SequenceEndEvent()>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=files)>
 <org.yaml.snakeyaml.events.SequenceStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=LICENSE)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=README.md)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=Rakefile)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=java-properties.gemspec)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=lib/java-properties.rb)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=lib/java-properties/encoding.rb)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=lib/java-properties/encoding/separators.rb)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=lib/java-properties/encoding/special_chars.rb)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=lib/java-properties/encoding/unicode.rb)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=lib/java-properties/generating.rb)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=lib/java-properties/generating/generator.rb)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=lib/java-properties/parsing.rb)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=lib/java-properties/parsing/normalizer.rb)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=lib/java-properties/parsing/parser.rb)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=lib/java-properties/properties.rb)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=lib/java-properties/version.rb)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=spec/fixtures/bom.properties)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=spec/fixtures/test.properties)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=spec/fixtures/test_normalized.properties)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=spec/fixtures/test_out.properties)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=spec/fixtures/test_out_skip_separators.properties)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=spec/fixtures/test_out_skip_special_chars.properties)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=spec/fixtures/test_out_skip_unicode.properties)>
 <org.yaml.snakeyaml.events.SequenceEndEvent()>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=homepage)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=https://github.com/jnbt/java-properties)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=licenses)>
 <org.yaml.snakeyaml.events.SequenceStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=MIT)>
 <org.yaml.snakeyaml.events.SequenceEndEvent()>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=metadata)>
 <org.yaml.snakeyaml.events.MappingStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.MappingEndEvent()>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=post_install_message)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=rdoc_options)>
 <org.yaml.snakeyaml.events.SequenceStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.SequenceEndEvent()>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=require_paths)>
 <org.yaml.snakeyaml.events.SequenceStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=lib)>
 <org.yaml.snakeyaml.events.SequenceEndEvent()>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=required_ruby_version)>
 <org.yaml.snakeyaml.events.MappingStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=requirements)>
 <org.yaml.snakeyaml.events.SequenceStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.SequenceStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: '"',implicit=[false, true], value=>=)>
 <org.yaml.snakeyaml.events.MappingStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=version)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=2.0.0)>
 <org.yaml.snakeyaml.events.MappingEndEvent()>
 <org.yaml.snakeyaml.events.SequenceEndEvent()>
 <org.yaml.snakeyaml.events.SequenceEndEvent()>
 <org.yaml.snakeyaml.events.MappingEndEvent()>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=required_rubygems_version)>
 <org.yaml.snakeyaml.events.MappingStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=requirements)>
 <org.yaml.snakeyaml.events.SequenceStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.SequenceStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: '"',implicit=[false, true], value=>=)>
 <org.yaml.snakeyaml.events.MappingStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=version)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=1.3.5)>
 <org.yaml.snakeyaml.events.MappingEndEvent()>
 <org.yaml.snakeyaml.events.SequenceEndEvent()>
 <org.yaml.snakeyaml.events.SequenceEndEvent()>
 <org.yaml.snakeyaml.events.MappingEndEvent()>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=requirements)>
 <org.yaml.snakeyaml.events.SequenceStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.SequenceEndEvent()>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=rubygems_version)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=3.2.3)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=signing_key)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=specification_version)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=4)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=summary)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=Loader and writer for *.properties files)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=test_files)>
 <org.yaml.snakeyaml.events.SequenceStartEvent(anchor=null, tag=null, implicit=true)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=spec/fixtures/bom.properties)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=spec/fixtures/test.properties)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=spec/fixtures/test_normalized.properties)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=spec/fixtures/test_out.properties)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=spec/fixtures/test_out_skip_separators.properties)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=spec/fixtures/test_out_skip_special_chars.properties)>
 <org.yaml.snakeyaml.events.ScalarEvent(anchor=null, tag=null, style=Scalar style: 'null',implicit=[true, false], value=spec/fixtures/test_out_skip_unicode.properties)>
 <org.yaml.snakeyaml.events.SequenceEndEvent()>
 <org.yaml.snakeyaml.events.MappingEndEvent()>
 <org.yaml.snakeyaml.events.DocumentEndEvent()>
 <org.yaml.snakeyaml.events.StreamEndEvent()>
```