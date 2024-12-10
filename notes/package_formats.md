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

### Debian `.deb` Packages
References Used:
- [Debian package structure](https://wiki.freepascal.org/Debian_package_structure)
- [Basics of the Debian package management system](https://www.debian.org/doc/manuals/debian-faq/pkg-basics.en.html)
- [Anatomy of a deb Package](https://radagast.ca/linux/anatomy_of_a_deb_file.html)

Debian `.deb` packages are an `ar` (note this is `ar` and not `tar`) package containing three subfiles:
- `control.tar.gz` contains a Debian 'control' file which contains metadata about the package
- `debian-binary` which is a text file containing the version of the Debian system
- `data.tar.gz` contains the actual package files - source code or binaries

#### Debian Package Metadata
The `control.tar.gz` file is a tarball containing a single `control` file. This file contains metadata about the package.

Sample: 

```
Package: hello
Version: 2.2-2
Section: devel
Priority: optional
Architecture: i386
Depends: libc6 (>= 2.5-0ubuntu1)
Installed-Size: 584
Maintainer: Ubuntu Core Developers 
Original-Maintainer: Santiago Vila 
Description: The classic greeting, and a good example
 The GNU hello program produces a familiar, friendly greeting.  It
 allows non-programmers to use a classic computer science tool which
 would otherwise be unavailable to them.
 .
 Seriously, though: this is an example of how to do a Debian package.
 It is the Debian version of the GNU Project's `hello world' program
 (which is itself an example for the GNU Project).
 ```

 ##### Metadata Fields
 - `Package` - the name of the package
 - `Version` - the version of the package
 - `Section` - the category of the package
 - `Priority` - the priority of the package
 - `Architecture` - the CPU architecture used by this package
 - `Depends` - other packages upon which this package depends
 - `Installed-Size` - the size, in kilobytes (kb), that the package takes up when decompressed and installed
 - `Description` - a long description of the package
 - `Maintainer` - 
 - `Original-Maintainer` - 

 #### Debian Package Data
 The data tarball, `data.tar.gz`, contains the actual files that will be installed with the package. 

e.g.:
 ```
 bash$ tar -xzvf data.tar.gz 
./
./usr/
./usr/share/
./usr/share/doc/
./usr/share/doc/hello/
./usr/share/doc/hello/NEWS
./usr/share/doc/hello/copyright
./usr/share/doc/hello/changelog.gz
./usr/share/doc/hello/changelog.Debian.gz
./usr/share/man/
./usr/share/man/man1/
./usr/share/man/man1/hello.1.gz
./usr/bin/
./usr/bin/hello
 ```

### ISO 9660 / UDF `.iso` Files
References used:
- [Docs for Palantir's `isofilereader` Java Library](https://github.com/palantir/isofilereader)

### Java Archives (`.jar` and related)
#### Java JAR (Java Archive) Format `.jar`
- [JAR Manifest Files](https://docs.oracle.com/javase/tutorial/deployment/jar/manifestindex.html)
`.jar` is a zip file containing code and some optional metadata.
#### Java WAR (Web Archive) Format `.war`
WAR is for deploying Java webapps on e.g. tomcat or jetty. 
`.war` is a zip file containing code and metadata.
#### Java EAR (Enterprise Archive) Format `.ear`
EAR is for deploying Java Enterprise applications, e.g. EJBs. 
`.ear` is a zip file containing code and metadata.

### RPM `.rpm` Packages
References Used
- [jRPM - RPM Specs](https://jrpm.sourceforge.net/rpmspec/index.html)
- [RPM.org V4 Package Format](https://rpm-software-management.github.io/rpm/manual/format_v4.html)
- [RPM.org RPM File Format](http://ftp.rpm.org/max-rpm/s1-rpm-file-format-rpm-file-format.html)

RPMs are a binary format, constructed of 4 distinct pieces:
- The Lead: this section is the first part of the file and helps identify the package as an RPM file; other previous uses of the lead have been deprecated and abandoned
- The Signature: This section follows the lead in the file, and can be used to verify the integrity and optionally the authenticity of the package
- The Header: this section "contains all available information about the package". This includes name, version, and file lists.
### Android APK `.apk` Packages
References Used
- [Wikipedia `.apk` File Format](https://en.wikipedia.org/wiki/Apk_(file_format))
- [Structure of an Android App Binary (.apk)](https://www.appdome.com/how-to/devsecops-automation-mobile-cicd/appdome-basics/structure-of-an-android-app-binary-apk/)
