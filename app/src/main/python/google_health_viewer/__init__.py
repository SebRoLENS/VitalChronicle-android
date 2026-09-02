"""VitalChronicle: a local-first Google Health dashboard."""

from . import analysis as _analysis
from .heart_rate_core import install_shared_heart_rate_core as _install_shared_heart_rate_core

__version__ = "1.2.1"

# Keep desktop and Android on exactly the same heart-rate semantics. Importing
# the package installs the shared five-minute averaging/parser into analysis.py;
# CI refreshes these files from the canonical desktop repository before release.
_install_shared_heart_rate_core(_analysis)
