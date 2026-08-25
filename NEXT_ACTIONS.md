# Immediate Actions - High-Value Files

Based on AST analysis, here are the concrete next steps.

## Summary

- **Files Present:** 1/2 (50.0%)
- **Function parity:** 6/27 matched (target 25) — 22.2%
- **Class/type parity:** 2/3 matched (target 21) — 66.7%
- **Combined symbol parity:** 8/30 matched (target 46) — 26.7%
- **Average inline-code cosine:** 0.00 (function body across 1 matched files)
- **Average documentation cosine:** 0.00 (doc text across 1 matched files)
- **Cheat-zeroed Files:** 1
- **Critical Issues:** 1 files with <0.60 function similarity

## Priority 1: Fix Incomplete High-Dependency Files

No incomplete high-dependency files detected.

## Priority 2: Port Missing High-Value Files

Critical missing files (>10 dependencies):

No missing high-value files detected.

## Detailed Work Items

Every matched file is listed below with function and type symbol parity.

### 1. layer

- **Target:** `opentelemetryappendertracing.Layer [ZERO]`
- **Similarity:** 0.00
- **Dependents:** 0
- **Priority Score:** 223010.0
- **Functions:** 6/27 matched (target 25)
- **Missing functions:** `is_duplicated_metadata`, `get_filename`, `new`, `visit_experimental_metadata`, `record_str`, `record_bool`, `record_f64`, `record_i64`, `record_u64`, `record_i128`, `record_u128`, `attributes_contains`, `create_tracing_subscriber`, `tracing_appender_inside_tracing_context`, `tracing_appender_inside_tracing_crate_context`, `tracing_appender_standalone_with_tracing_log`, `tracing_appender_inside_tracing_context_with_tracing_log`, `emit`, `event_enabled`, `force_flush`, `is_enabled`
- **Types:** 2/3 matched (target 21)
- **Missing types:** `LogProcessorWithIsEnabled`
- **Tests:** 1/11 matched

## Success Criteria

For each file to be considered "complete":
- **Similarity ≥ 0.85** (Excellent threshold)
- All public APIs ported
- All tests ported
- Documentation ported
- port-lint header present

## Reexport / Wiring Modules

These files match `reexport_modules` patterns in `.ast_distance_config.json`. They are filtered out of
normal priority and missing-file ladders because they are wiring
modules, not direct logic ports. Consult them for call-site routing;
do not treat them as the next implementation target by default.

### Missing

| Source | Expected target | Deps | Source path | Expected path |
|--------|-----------------|------|-------------|---------------|
| `lib` | `Lib` | 0 | `lib.rs` | `Lib.kt` |

