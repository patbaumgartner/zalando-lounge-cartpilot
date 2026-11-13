Intentionally empty Flyway location used only during the AOT (native image
build) phase. The pom's process-aot execution points
-Dspring.flyway.locations at this directory so that Flyway stays enabled at
build time (keeping the Flyway auto-configuration and its db/migration/**
resource hints in the native image) while AOT processing sees zero migrations
and never connects to a real database. Real migrations live in
src/main/resources/db/migration and run at application startup against the
configured datasource.
