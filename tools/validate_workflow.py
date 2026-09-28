"""Validate Calendar's local agent/skill packaging; no network or app execution."""

from __future__ import annotations

import json
from pathlib import Path
import re
import sys
import tomllib
from urllib.parse import unquote, urlsplit

# Prefer an already installed parser; use the documented local tool dependency
# when the workspace runtime does not bundle PyYAML.
try:
    import yaml
except ModuleNotFoundError:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / ".local/workflow-python"))
    try:
        import yaml
    except ModuleNotFoundError:
        sys.exit("Missing PyYAML. Run: python -m pip install --target .local/workflow-python -r tools/requirements.txt")

if not hasattr(yaml, "safe_load") or not hasattr(yaml, "YAMLError"):
    sys.exit("PyYAML is not readable or another 'yaml' module shadows it. Check the local dependency and filesystem permissions.")


def validate(root: Path) -> list[str]:
    errors: list[str] = []

    def require(condition: bool, message: str) -> None:
        if not condition:
            errors.append(message)

    def read_yaml(path: Path) -> dict:
        value = yaml.safe_load(path.read_text(encoding="utf-8"))
        if not isinstance(value, dict):
            raise ValueError(f"Expected a mapping: {path}")
        return value

    config = tomllib.loads((root / ".codex/config.toml").read_text(encoding="utf-8"))
    require(config.get("agents", {}).get("enabled") is True, "Agents are not enabled")
    require(config["agents"].get("max_concurrent_threads_per_session") == 3,
            "Expected the documented limit of three children")

    agent_files = sorted((root / ".codex/agents").glob("*.toml"))
    names: set[str] = set()
    for path in agent_files:
        agent = tomllib.loads(path.read_text(encoding="utf-8"))
        for key in ("name", "description", "developer_instructions"):
            require(isinstance(agent.get(key), str) and bool(agent[key].strip()),
                    f"{path.name}: missing {key}")
        name = agent.get("name", "")
        require(name == path.stem, f"{path.name}: name differs from filename")
        require(name not in names, f"Duplicate agent: {name}")
        names.add(name)
        for ref in re.findall(r"\.agents/skills/[a-z-]+/SKILL\.md",
                              agent.get("developer_instructions", "")):
            require((root / ref).is_file(), f"{path.name}: missing skill {ref}")
        require("model" not in agent and "model_reasoning_effort" not in agent,
                f"{path.name}: unexpected model override")
    require(names == {"calendar_specifier", "calendar_planner",
                      "calendar_proton_researcher", "calendar_developer",
                      "calendar_tester", "calendar_reviewer"},
            "Agent inventory differs from the documented six roles")

    skill_paths = sorted((root / ".agents/skills").glob("*/SKILL.md"))
    skill_names: set[str] = set()
    for path in skill_paths:
        text = path.read_text(encoding="utf-8")
        match = re.match(r"\A---\n(.*?)\n---\n", text, re.S)
        require(match is not None, f"{path}: missing YAML frontmatter")
        if match is None:
            continue
        meta = yaml.safe_load(match.group(1))
        if not isinstance(meta, dict):
            errors.append(f"{path}: frontmatter must be a mapping")
            continue
        name = meta.get("name", "")
        require(isinstance(name, str) and bool(re.fullmatch(r"[a-z0-9-]{1,64}", name)),
                f"{path}: invalid skill name")
        require(name == path.parent.name, f"{path}: skill name/directory mismatch")
        require(name not in skill_names, f"Duplicate skill: {name}")
        skill_names.add(name)
        require(isinstance(meta.get("description"), str) and bool(meta["description"].strip()),
                f"{path}: missing description")
        ui = read_yaml(path.parent / "agents/openai.yaml").get("interface", {})
        require(isinstance(ui, dict), f"{path}: invalid UI interface")
        if isinstance(ui, dict):
            require(bool(ui.get("display_name")), f"{path}: missing UI name")
            require(25 <= len(ui.get("short_description", "")) <= 64,
                    f"{path}: short description must contain 25-64 characters")
            require(f"${name}" in ui.get("default_prompt", ""),
                    f"{path}: default prompt must name the skill")
        require("[TODO:" not in text, f"{path}: unfinished scaffold")
    require(skill_names == {"calendar-specify", "calendar-plan",
                            "calendar-proton-research", "calendar-implement",
                            "calendar-verify"}, "Skill inventory differs from documentation")

    # Check authored Markdown links, including the skill-to-project references.
    # Template placeholders are prose, not executable metadata.
    for path in root.rglob("*.md"):
        if any(part in {".git", ".local", ".research"} for part in path.relative_to(root).parts):
            continue
        for target in re.findall(r"\[[^\]\n]*\]\(([^)\n]+)\)", path.read_text(encoding="utf-8")):
            target = target.strip().strip("<>")
            if urlsplit(target).scheme or target.startswith("#"):
                continue
            local_path = unquote(target.split("#", 1)[0])
            require((path.parent / local_path).exists(),
                    f"{path.relative_to(root)}: broken link {target}")

    sources = json.loads((root / "docs/workflow-sources.json").read_text(encoding="utf-8"))
    require(len(sources.get("sources", [])) == 4, "Expected four pinned upstream sources")
    for source in sources.get("sources", []):
        require(bool(re.fullmatch(r"[a-f0-9]{40}", source.get("commit", ""))),
                f"{source.get('repository')}: unpinned revision")
        require(bool(source.get("reviewed_paths")), "Source has no reviewed paths")
        require((root / source["local_license"]).is_file(), "Missing upstream license")
    return errors


if __name__ == "__main__":
    project_root = Path(sys.argv[1]).resolve() if len(sys.argv) == 2 else Path(__file__).resolve().parents[1]
    try:
        failures = validate(project_root)
    except (OSError, ValueError, TypeError, KeyError, yaml.YAMLError) as exc:
        print(f"FAIL: {exc}")
        sys.exit(1)
    if failures:
        print("\n".join(f"FAIL: {failure}" for failure in failures))
        sys.exit(1)
    print("PASS: 6 agents, 5 skills, local links, UI metadata and 4 pinned source records")
