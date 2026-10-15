"""Integration tests: run the viewer pipeline on all test/data examples.

Test categories:
A. Generated JSON → viewer tests (roundabout generates JSON, viewer renders HTML)
B. Clean pipeline tests (full pipeline via generate_viewer.py, needs certoraRun + Java + jar)
C. Fault injection tests (compilation fixer validation)
"""

import glob
import json
import os
import subprocess
import sys

import json5
import pytest

from tests.integration.conftest import (
    ALL_EXACT_FIXTURES,
    REPO_ROOT,
    VIEWER_SCRIPT,
    derive_project_root,
    discover_conf_files,
    discover_sol_files,
    generate_all_roundabout_jsons,
    requires_full_pipeline,
    validate_viewer_html,
)


def _collect_logs(project_root):
    """Read any roundabout.log files under project_root for error diagnostics."""
    logs = []
    for log_path in glob.glob(os.path.join(project_root, "**", "roundabout.log"), recursive=True):
        try:
            with open(log_path) as f:
                content = f.read()
            if content.strip():
                logs.append(f"--- {log_path} ---\n{content[-2000:]}")
        except Exception:
            pass
    return "\n".join(logs) if logs else "(no roundabout.log found)"


def _assert_pipeline_ok(result, name, project_root):
    """Assert pipeline succeeded, including log content on failure."""
    if result.returncode != 0:
        logs = _collect_logs(project_root)
        pytest.fail(
            f"Pipeline failed for {name}:\n"
            f"stdout: {result.stdout}\n"
            f"stderr: {result.stderr}\n"
            f"roundabout logs:\n{logs}"
        )

# =========================================================================
# Parametrize IDs
# =========================================================================

_CONF_FILES = discover_conf_files()
_SOL_FILES = discover_sol_files()

_conf_ids = [name for name, _ in _CONF_FILES]
_sol_ids = [name for name, _ in _SOL_FILES]


# =========================================================================
# A. Generated JSON → viewer tests
#    Runs roundabout.py to generate JSONs, then validates the viewer on each.
# =========================================================================


@requires_full_pipeline
def test_viewer_from_generated_json(tmp_path):
    """Generate roundabout JSONs for all conf files, then validate viewer HTML for each."""
    generated = generate_all_roundabout_jsons()
    assert len(generated) > 0, "No roundabout JSONs were generated — check certoraRun + java + jar"

    for name, json_path, conf_path in generated:
        project_root = derive_project_root(conf_path)
        output_html = str(tmp_path / f"{name}_viewer.html")

        cmd = [
            sys.executable, VIEWER_SCRIPT,
            "--project-root", project_root,
            "--json-input", json_path,
            "--output", output_html,
            "--conf-file", conf_path,
        ]
        result = subprocess.run(cmd, capture_output=True, text=True)
        _assert_pipeline_ok(result, f"{name}/json_viewer", project_root)

        data = validate_viewer_html(output_html, require_non_neither=name not in ALL_EXACT_FIXTURES)
        print(f"  {name}: {len(data['contexts'])} contexts, validated OK")


# =========================================================================
# B. Clean pipeline tests (full pipeline)
# =========================================================================


@requires_full_pipeline
@pytest.mark.parametrize("name,conf_path", _CONF_FILES, ids=_conf_ids)
def test_viewer_from_conf(name, conf_path, tmp_path):
    """Run the full pipeline (certoraRun → roundabout Java → viewer) on each .conf file."""
    project_root = derive_project_root(conf_path)
    output_html = str(tmp_path / f"{name}_viewer.html")

    cmd = [
        sys.executable, VIEWER_SCRIPT,
        "--project-root", project_root,
        "--input-file", conf_path,
        "--output", output_html,
    ]
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
    _assert_pipeline_ok(result, name, project_root)

    data = validate_viewer_html(output_html, require_non_neither=name not in ALL_EXACT_FIXTURES)
    print(f"  {name}: {len(data['contexts'])} contexts, validated OK")


@requires_full_pipeline
@pytest.mark.parametrize("name,sol_path", _SOL_FILES, ids=_sol_ids)
def test_viewer_from_sol(name, sol_path, tmp_path):
    """Run the full pipeline on each .sol file directly (auto-generated conf)."""
    # Use the sol file's directory as project root
    project_root = os.path.dirname(sol_path)
    sol_relative = os.path.basename(sol_path)
    output_html = str(tmp_path / f"{name}_viewer.html")

    cmd = [
        sys.executable, VIEWER_SCRIPT,
        "--project-root", project_root,
        "--input-file", sol_relative,
        "--output", output_html,
    ]
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
    _assert_pipeline_ok(result, name, project_root)

    # Relaxed validation for single-file mode — may have fewer annotations
    data = validate_viewer_html(output_html, min_size=3000, require_non_neither=False)
    print(f"  {name}: {len(data['contexts'])} contexts, validated OK")


# =========================================================================
# C. Fault injection tests (compilation fixer validation)
# =========================================================================


def _make_faulted_conf(conf_path, fault_fn, tmp_path):
    """Read a conf file, apply a fault function, write to tmp_path. Returns new conf path."""
    with open(conf_path) as f:
        conf_data = json5.load(f)
    fault_fn(conf_data)
    faulted_conf = str(tmp_path / "faulted.conf")
    with open(faulted_conf, "w") as f:
        json.dump(conf_data, f, indent=4)
    return faulted_conf



@requires_full_pipeline
@pytest.mark.parametrize("name,conf_path", _CONF_FILES, ids=_conf_ids)
def test_fault_nonexistent_solc(name, conf_path, tmp_path):
    """Inject a nonexistent solc binary — fixer should fall back to default."""
    def inject(conf_data):
        conf_data["solc"] = "solc99.99"

    faulted_conf = _make_faulted_conf(conf_path, inject, tmp_path)
    project_root = derive_project_root(conf_path)
    output_html = str(tmp_path / f"{name}_fault_solc.html")

    cmd = [
        sys.executable, VIEWER_SCRIPT,
        "--project-root", project_root,
        "--input-file", faulted_conf,
        "--output", output_html,
    ]
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
    _assert_pipeline_ok(result, f"{name}/fault_solc", project_root)

    data = validate_viewer_html(output_html, require_non_neither=name not in ALL_EXACT_FIXTURES)
    print(f"  {name}: fixer recovered from nonexistent solc, {len(data['contexts'])} contexts")
