## Package Formats

Goat Rodeo supports scanning of many software package types & sources. This document is intended to preserve the specification rules we followed implementing our parsers for each format.

TODO - anchor links

### Ruby Gems
References used: 
- [Exploring The Structure of Ruby Gems @ CloudBees](https://www.cloudbees.com/blog/exploring-structure-ruby-gems)
- [Explain the Structure of a Ruby Gem](https://imvishalpandey.medium.com/explain-the-structure-of-a-ruby-gem-8774d88b1f00)
- [Diving Into the Internals of Gem Packaging](https://www.codemancers.com/blog/2020-08-17-understanding-gem-packaging/)

Ruby Gem archives are suffixed `.gem`, and are tarballs containing 3 files:
   * - `metadata.gz` - a gzipped file containing the metadata (dependency info, etc) and Gem Spec
   * - `checksums.yaml.gz` - a gzipped YAML file with the checksums for the archive
   * - `data.tar.gz` - a tarball containing the actual ruby dependency code

#### Ruby Gem Metadata
The `metadata.gz` file is a gzipped metadata file containing a Gem specification with data such as package name, version, author info, dependencies etc.

Snippet: 

```ruby
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

…
```
#### Ruby Gem Checksums
The checksums file is a gzipped YAML file containing the checksums for the files at the top of the Gem package.

e.g.
```yaml
---
SHA256:
  metadata.gz: 5c361bfd271f8672e0b30fc875cfeb546e4750d76663109821abaa16712b7d4d
  data.tar.gz: fa5a4a45d2f9df8d04cab3a3a64b5a0442a742de890b1dbeb9ec7f7148e32038
SHA512:
  metadata.gz: 34950a3536aa41fb22e18a207343bff2a9f159047326f2340e013d86f648e0c9edd0769cb500e2fab13566c95a04cda7015f0a06d14480b2b17abd7a698012d6
  data.tar.gz: a26f13baf630740d763672041c638c3c9af16202a4b9746cfffbb7260d04655f41c3fbec48bb4bff638c5bbf57e3e306b0c0b8427e6787abad824a0507c8650b
```

#### Ruby Gem Data
The `data.tar.gz` tarball contains the actual Ruby code that the Gem package represents.
