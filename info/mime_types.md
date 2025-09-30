## Special Handling of MIME Types

MIME types are an attempt to assign a category for the content of a stream of data. It is a relatively simple mecxhanism, but it tends to over-simplify content.

Goat rodeo uses (Apache Tika)[https://tika.apache.org/] to classify the content of files, which is does very well, to the limits that are available.

As such, it is expedient for goat rodeo to be able to make some MIME types more specific than what it provided by Tika. Tika has extensibility which can do that, but there is no direct API and instead uses configurations and external code which look like that are an attack surface.

It is simpler in the short term to let a default Tika installation do the heavy lifting and then do post processing of the detected MIME type.

At present there are two such post-processing operations that are being done:
`text/plain` -> `application/json`
`application/x-msdownload; format=pe32` -> `application/x-msdownload; format=pe32-dotnet`

# TODO
If goat rodeo adds a component model, make MIME type transmogrifiers available.
