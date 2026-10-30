# Native DLL fixture

A real, native (non-.NET) Windows DLL, used to prove that the `.NET` container
type and the `Dotnet` strategy are decided by the PE's CLI data directory rather
than by the file extension. A native library has data-directory entry 14 zero,
so it must never be typed as a .NET assembly and must never reach the assembly
reader.

## sqlite3.dll

| | |
|---|---|
| Upstream | SQLite — https://www.sqlite.org/ |
| Release | 3.54.1, x64 Windows prebuilt |
| Archive | `https://www.sqlite.org/2026/sqlite-dll-win-x64-3540100.zip` |
| Member | `sqlite3.dll` |
| Size | 3,519,488 bytes |
| SHA-256 | `4b59d3cded9dc1608544160f3b4b10ac974c6ac0d6ffc23064e5f4c8dbd05aea` |
| Licence | Public domain (SQLite is dedicated to the public domain) |
| PE machine | `0x8664` (x86-64) |
| CLI directory | absent (`cli=false`) |

Verified as native, and as carrying no CLI header, by
`DotnetNativeDllSuite.DN-1`.
