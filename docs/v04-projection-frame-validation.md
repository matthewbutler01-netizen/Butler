# Exact projection season/week validation

The weekly projection parser previously converted JSON numbers to integers
with truncation. A row labeled week 2.9 could therefore enter a week 2 snapshot.
It now preserves decimal input precision and requires an exact integer value
for numeric season/week fields. Fractional or out-of-range values are excluded
before precomputed points, raw-stat scoring or coverage gaps are collected.
Whole-valued decimals and existing integer strings remain supported. Missing
frame fields retain the existing endpoint-bound behavior.

Regression fixtures reproduce fractional and tiny-fraction failures and check
both production and compatibility paths. Current rows remain usable; invalid
rows cannot become projections or coverage gaps. Exact whole-valued and text
frames remain usable. Existing raw-stat scoring and missing-evidence tests
continue to apply. This does not supply missing projections or clear player
holds, and it does not change Sleeper data.
