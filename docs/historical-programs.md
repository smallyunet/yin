# Historical program classification

Only programs under `tests/` are supported and normative. Files under
`experiments/` preserve design history and are classified here so an old syntax
experiment is not mistaken for a current feature.

| Historical file | Status | Current disposition |
| --- | --- | --- |
| `array.yin` | Migrated | Empty-vector behavior is maintained by `tests/empty-vector.yin`. |
| `test4.yin` | Migrated | Basic arithmetic is maintained by `tests/arithmetic.yin`. |
| `assign1.yin` | Archived | Mixes supported vector patterns with removed braces, attributes, subscripts, and obsolete record syntax. |
| `attr.yin` | Archived | Depends on removed dot attribute access and obsolete record descriptors. |
| `attr2.yin` | Archived | Depends on removed attribute and `#` subscript syntax. |
| `preparser.yin` | Archived | Exercises the abandoned attribute-oriented preparser. |
| `test1.yin` | Archived | Contains multiple abandoned lexical and surface-syntax proposals. |
| `test2.yin` | Archived | Uses brace records and obsolete descriptor syntax. |
| `test3.yin` | Archived | Uses obsolete definition and record forms. |
| `typecheck1.elt` | Archived | Explores generics, attributes, and dependent types outside Yin 0.3. |
| `types.yin` | Archived | Uses obsolete colon-prefixed descriptors and computed defaults. |
| `types2.yin` | Archived | Explores standalone declarations and unrestricted descriptor metadata. |
| `types3.yin` | Archived | Mixes current annotations with unfinished recursive type-level functions. |

The automated `HistoricalCorpusTest` requires every `.yin` and `.elt` file in
`experiments/` to remain classified. Migrated successors must exist and run
through both the interpreter and type checker.
