# Lint task compatibility

The project has two distribution flavors: `internal` and `play`. Android Gradle Plugin therefore creates `lintInternalDebug` and `lintPlayDebug`, not one automatically resolvable `lintDebug` task.

The module defines an explicit `lintDebug` compatibility task that runs both flavor-specific debug lint tasks. This preserves older diagnostics workflows while ensuring neither distribution is skipped.
