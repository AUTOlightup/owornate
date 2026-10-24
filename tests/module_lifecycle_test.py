"""Compatibility entry point for the manager-owned lifecycle simulation.

The former Monet-only script is now a fixed wrapper around runtime-manage.sh.
The suite exercises that wrapper, all three features, and the legacy migration.
"""
from pathlib import Path
import runpy

runpy.run_path(str(Path(__file__).with_name('runtime_lifecycle_test.py')), run_name='__main__')
