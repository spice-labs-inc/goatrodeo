# MIME Type Filtering (Include/Exclude)

> **Navigation:** [Documentation Index](README.md) | [How It Works](goat_rodeo_operation.md) | [MIME Types](mime_types.md)

## Overview

It will be the case that clients of goat rodeo may wish to exclude or include MIME types of files queued for processing. Rather than depending on a static list or static code, this can be managed with a list of items or patterns to include or exclude that can be specified on the command line or through the configuration builder API.

## How It Works

The process will follow a "no, but..." model.

The include/exclude list will be aggregated and MIME types will be processed in either one or two steps:

1. a candidate MIME type will be tested for an exclusion match. If there is no match, it will be included.
2. if the candidate matched an exclusion rule, it will be checked for a match on an inclusion rule. If there is a match, it will be included.

In all other cases, it will be excluded.

## Syntax

> Some people, when confronted with a problem, think “I know, I'll use regular expressions.”  Now they have two problems. - [Jamie Zawinski, 12 August, 1997](https://regex.info/blog/2006-09-15/247)


The simplest approach to this is to adopt a simple syntax in the form:
```
<command_char><regular_expression><new_line>
```
Where `<command_char>` is one of:
- `*` - include
- `/` - exclude
- `#` - comment

While regular expressions can be processed efficiently, we can add two other operators to do exact matching which will be faster:
- `-` - include exact match
- `+` - exclude exact match

The naming reasoning is that `*` is an extension of `+` and `/` is an extension of `-`.

Based on this, we can create a type that includes a set of strings for exact matches and a list of regular expressions. The process for detection a match would be:
```scala
exact.contains(candidate) || regexes.exists((r) => r.matches(candidate))
```


