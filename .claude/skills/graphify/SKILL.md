---
name: graphify
description: Map the Kotlin code of Jarvis Android as a dependency graph (modules, files, links, test coverage) and publish it as an interactive page with a short report. Use for /graphify or when asked for a map, graph or overview of the codebase's structure.
---

# graphify

Builds the dependency graph of `app/src/main/java` and reports the structure of the code.

1. Run the script from the repo root (Python 3, no dependency):

   ```sh
   python3 .claude/skills/graphify/graphify.py . <output dir>
   ```

   Use the session's scratchpad as the output dir, never a path inside the repo. It writes:
   - `graph.json`: modules (top-level package under `com.jarvis.android`), module links weighted by file links, files with incoming and outgoing link counts, file links.
   - `GRAPH_REPORT.md`: hub files, the largest modules, modules with no unit test, circular dependencies between modules.
   - `graph.html`: an interactive force-directed graph of the modules (click one for its files, neighbours and tests), loaded with d3 from cdnjs.

2. Publish `graph.html` as an Artifact (it is already a full page with title and light/dark tokens). When a map was already published in this project, update that one instead of creating a new link.

3. Answer in French with the link and the four or five findings from `GRAPH_REPORT.md` that matter most. Say that the links are inferred from the types a file names, so a few may be wrong.

When the user passes a path (`/graphify app/src/main/java/com/jarvis/android/actions`), still build the whole graph and focus the answer on that module.
