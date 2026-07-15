# Large source file audit

Run from `mobile/calllog-android`:

```bash
python3 tools/check-large-source-files.py
```

The report lists source and resource files over 300 lines. It is informational: generated resources, cohesive UI controllers and storage adapters may legitimately remain larger until they can be split without changing behavior.
