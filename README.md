# Calendeer

An Android bridge being developed to synchronize Proton Calendar
with Android's system calendars. The application will not provide an event editor
or calendar browsing interface. The development baseline specifies
bidirectional synchronization as the final target and a possible read-only first
milestone. Offline processing, progressive loading and maximum practical reuse
of Proton libraries are confirmed requirements.
Each selected calendar will also offer a persistent read-only/editable mode,
with editing available where permissions and supported capabilities allow it.
Event viewing and editing stay in the Android calendar application.

The confirmed architectural constraint is a replaceable Proton integration:
changes to Proton's network API, cryptography or future SDK must be contained at
the integration boundary rather than spread through the application.

## Start here

- [Implementation status](docs/STATUS.md): available behavior and current limits.
- [Specification](docs/SPECIFICATION.md): requirements, provisional policies and acceptance scenarios.
- [Architecture](docs/ARCHITECTURE.md): module boundaries and incremental delivery.
- [Synchronization behavior](docs/SYNCHRONIZATION_BEHAVIOR.md): proposed read-only and editing capability gates.
- [Build and validation](docs/VALIDATION.md): repeatable checks and qualification scope.
- [Licensing and source reuse](docs/LICENSING.md): imported components and notices.
- [Agent instructions](AGENTS.md): project rules and task routing.
- [Working method](docs/WORKFLOW.md): roles, handoffs and verification.
- [Selected upstream methods](docs/WORKFLOW_SOURCES.md): sources and adaptations.
- [Proton research baseline](docs/research/PROTON.md): findings to revalidate before implementation.

## Agents and skills

`AGENTS.md` contains public project instructions. If present, `AGENTS.local.md`
adds private operator preferences and workstation restrictions; it is Git-ignored
and explicitly read by the primary and delegated agents. A fresh checkout works
without it. Keep its contents out of public documentation and commits.

Six project agents are defined in `.codex/agents/`: `calendar_specifier`,
`calendar_planner`, `calendar_proton_researcher`, `calendar_developer`,
`calendar_tester`, and `calendar_reviewer`.

Five project skills are installed in `.agents/skills/`: `$calendar-specify`,
`$calendar-plan`, `$calendar-proton-research`, `$calendar-implement`, and
`$calendar-verify`. The primary agent coordinates the work and owns integration.

Open **this Calendar directory** as the working project in Codex for native
discovery of these files. Starting from its parent directory does not imply that
child-project skills and agent configuration are discovered. Existing sessions
may need to reload the project or start a fresh task. Respect Codex's project
trust prompt; do not change global trust or permission settings to bypass it.

From a parent-directory task, the usable fallback is to explicitly read
`Calendar/AGENTS.md` and the relevant skill. A subagent can receive the selected
role's instructions in its task message when named-agent selection is unavailable.
Do not describe this fallback as proof of native agent discovery.

Example next request: "Poursuivons les spécifications de Calendar avec
$calendar-specify."

The Android configuration application, pure synchronization engine and Proton
authentication integration are implemented. The connection UI uses a real Proton
Core adapter and discovers calendar names and colors through the retained session.
Selected calendars can import supported simple events into Android in read-only
mode, with durable offline processing and background refresh. Calendar change
polling handles individual updates and explicit deletion notices. Recurrence,
reminders, participants, automatic recovery after a feed reset and bidirectional
editing remain unavailable. See the [import boundary](docs/READ_ONLY_IMPORT.md).
Upstream workflow frameworks are selectively adapted; no installer/hook is required.

## Build

Use a compatible JDK (17 or newer) and Android SDK platform 36. Configure the SDK
in ignored `local.properties` or `ANDROID_HOME`, then run from the project root:

```sh
./gradlew :sync:test :auth:test :proton-auth:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Use `gradlew.bat` on Windows. Initial setup downloads dependencies. The debug APK
is generated under `app/build/outputs/apk/debug/`; do not commit build outputs.
See [validation](docs/VALIDATION.md) for the scope of these checks.

## Workflow validation

With Python 3.11+, install the validation-only dependency locally if needed:

```powershell
python -m pip install --target .local/workflow-python -r tools/requirements.txt
python tools/validate_workflow.py
```

This checks local skill/agent metadata, references and source records. It does not
prove that the desktop app loaded the agents or that an Android app works.
The helper dependency stays in ignored `.local/`; it is not an app dependency.

Upstream notices are retained in [docs/licenses](docs/licenses).
The Android application's own license remains to be decided.
