# MIME Type Detection and Handling

> **Navigation:** [Documentation Index](README.md) | [MIME Filtering](block_list.md) | [How It Works](goat_rodeo_operation.md)

## Overview

MIME types are an attempt to assign a category for the content of a stream of data. It is a relatively simple mechanism, but it tends to over-simplify content.

Goat rodeo uses [Apache Tika](https://tika.apache.org/) to classify the content of files, which is does very well, to the limits that are available.

As such, it is expedient for goat rodeo to be able to make some MIME types more specific than what it provided by Tika. Tika has extensibility which can do that, but there is no direct API and instead uses configurations and external code which look like that are an attack surface.

It is simpler in the short term to let a default Tika installation do the heavy lifting and then do post processing of the detected MIME type.

## Post-Processing Refinements

At present there are two post-processing operations that refine MIME types:

| Original | Refined |
|----------|---------|
| `text/plain` | `application/json` (when content is valid JSON) |
| `application/x-msdownload; format=pe32` | `application/x-msdownload; format=pe32-dotnet` (for .NET assemblies) |

## Future Work

If Goat Rodeo adds a component model, MIME type transmogrifiers could be made extensible.
