# Goat Rodeo Documentation

This directory contains detailed documentation for Goat Rodeo. Use this index to find what you need.

---

## Quick Links

| I want to... | Read this |
|--------------|-----------|
| Get started quickly | [Main README](../README.md) |
| Understand how Goat Rodeo works | [How It Works](#how-it-works) |
| Use Goat Rodeo as a library | [API Documentation](#api-documentation) |
| Tune performance for large datasets | [Performance Tuning](#performance-tuning) |
| Understand the output format | [Data Formats](#data-formats) |
| Learn about Hidden Reapers | [Security Use Cases](#security-use-cases) |
| Contribute to the project | [Contributing](../CONTRIBUTING.md) |

---

## How It Works

### [Goat Rodeo Operation](goat_rodeo_operation.md)
Complete guide to how Goat Rodeo processes artifacts:
- CLI parameters and their effects
- File discovery phase
- Strategy determination (Maven, Docker, Debian, Generic)
- ADG building process
- Memory management with `MemStorage`
- Thread tuning guidelines

### [Append-Only Graph Database](append_only_graph.md)
Technical deep-dive into the graph database design:
- Graph structure and relationships
- Design constraints and trade-offs
- File formats (`.grd`, `.gri`, `.grc`)
- Sharding strategies
- Hash collision probability

---

## API Documentation

### [Goat Rodeo API](goat_rodeo_api.md)
How to embed Goat Rodeo in your application:
- `GoatRodeoBuilder` fluent interface
- All `with*` configuration methods
- Running the configuration
- Java and Scala usage examples

---

## Performance Tuning

### [Performance Guide](goat_rodeo_operation.md#tuning-for-performance)
Guidelines for processing large artifact sets:
- RAM disk setup and configuration
- Thread count optimization
- `--maxrecords` tuning
- I/O vs CPU bottleneck identification
- Concrete tuning recommendations

---

## Data Formats

### [Corpus File Format](corpus.md)
How the OmniBOR Corpus stores and retrieves data:
- Entry format and structure
- MD5 indexing strategy
- Corpus sharding (16, 256, or 4096 shards)
- Merge algorithm (O(n) complexity)
- BigTent serving architecture

### [Metadata Tags](metadata_tags.md)
Standard metadata fields attached to processed artifacts:
- `NAME`, `VERSION`, `PUBLISHER`, `LICENSE`, etc.
- Dependencies format (JSON structure)
- Cross-ecosystem consistency guidelines

---

## Filtering and Configuration

### [MIME Type Filtering](block_list.md)
Include/exclude artifacts by MIME type:
- Filter syntax (`+`, `-`, `*`, `/`, `#`)
- "No, but..." pattern for complex filtering
- Regular expression support
- Performance considerations

### [MIME Type Handling](mime_types.md)
How Goat Rodeo detects and refines MIME types:
- Apache Tika integration
- Post-processing refinements
- Custom MIME type detection

---

## Security Use Cases

### [Hidden Reapers](hidden_reapers.md)
Finding vulnerable dependencies hidden in artifacts:
- What are Hidden Reapers?
- How vulnerabilities hide from standard tools
- Artifact Dependency Graph approach
- Marker-based detection
- Using the unmasking tool
- Interpreting results

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                        Goat Rodeo                               │
├─────────────────────────────────────────────────────────────────┤
│  CLI / GoatRodeoBuilder API                                     │
├─────────────────────────────────────────────────────────────────┤
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐          │
│  │ File         │  │ Strategy     │  │ ADG          │          │
│  │ Discovery    │──▶│ Selection    │──▶│ Building     │          │
│  └──────────────┘  └──────────────┘  └──────────────┘          │
│         │                 │                  │                  │
│         ▼                 ▼                  ▼                  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐          │
│  │ Artifact     │  │ Maven        │  │ MemStorage   │          │
│  │ Wrapper      │  │ Docker       │  │ (low-lock)   │          │
│  │ + Tika MIME  │  │ Debian       │  │              │          │
│  └──────────────┘  │ Generic      │  └──────────────┘          │
│                    └──────────────┘          │                  │
│                                              ▼                  │
│                                    ┌──────────────┐            │
│                                    │ Output       │            │
│                                    │ .grd/.gri    │            │
│                                    └──────────────┘            │
└─────────────────────────────────────────────────────────────────┘
```

---

## Key Concepts

### GitOID (Git Object Identifier)
A content-addressable identifier based on Git's object hashing. Goat Rodeo uses `gitoid:blob:sha256:` format for unique artifact identification.

### Artifact Dependency Graph (ADG)
A bidirectional graph showing relationships between artifacts. Enables queries like "what JARs contain this class?" or "what source files built this binary?"

### OmniBOR
The [OmniBOR specification](https://omnibor.io) defines how to create and use artifact identifiers for software supply chain transparency.

### pURL (Package URL)
A standardized way to identify software packages across ecosystems. See [pURL spec](https://github.com/package-url/purl-spec).

---

## For Developers

### [Architecture Guide](architecture.md)
Internal architecture for contributors and extenders:
- Directory structure and code organization
- Core data flow and processing pipeline
- Key components (ArtifactWrapper, ToProcess, Item, Storage)
- Processing lifecycle
- How to add new artifact types
- Testing guide
- Performance considerations

---

## File Index

| File | Description |
|------|-------------|
| [append_only_graph.md](append_only_graph.md) | Graph database design and file formats |
| [architecture.md](architecture.md) | Internal architecture for developers |
| [block_list.md](block_list.md) | MIME type include/exclude filtering |
| [corpus.md](corpus.md) | Corpus file format and indexing |
| [goat_rodeo_api.md](goat_rodeo_api.md) | Programmatic API documentation |
| [goat_rodeo_operation.md](goat_rodeo_operation.md) | CLI usage and performance tuning |
| [hidden_reapers.md](hidden_reapers.md) | Finding hidden vulnerabilities |
| [metadata_tags.md](metadata_tags.md) | Standard metadata field definitions |
| [mime_types.md](mime_types.md) | MIME type detection and handling |

---

## See Also

- [Main README](../README.md) - Quick start guide
- [Contributing](../CONTRIBUTING.md) - How to contribute
- [Release Process](../RELEASE_PROCESS.md) - How releases are made
- [Code of Conduct](../code_of_conduct.md) - Community guidelines
