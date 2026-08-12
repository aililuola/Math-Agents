# Python Compute Service

This directory is the complete Python sidecar. It does not import the legacy
Python project and does not open a network listener.

`service.py` accepts one UTF-8 JSON-RPC 2.0 object per line on standard input
and emits one response line on standard output. Protocol version `1.0` supports
only the five explicitly registered SymPy/Z3 methods. The response
`stdout_hash` is the SHA-256 of canonical JSON for `result` or `error`, avoiding
a self-referential response hash.

The Java adapter supplies a cleared, allowlisted environment, validates all
identifiers, sizes and evidence fields, bounds stderr, and terminates the
process tree on timeout. Arbitrary program execution is not part of this
service and remains disabled unless the separate pinned-container policy is
explicitly enabled.
