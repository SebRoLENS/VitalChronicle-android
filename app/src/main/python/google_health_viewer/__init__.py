"""VitalChronicle: a local-first Google Health dashboard."""

from . import analysis as _analysis
from . import agent_hard_query_reliability_patch as _hard_query
from . import agent_tool_factory_reliability_patch as _reliability
from . import agent_tool_factory_semantic_guard as _semantic
from .agent_factory_request_preserve_patch import (
    install_factory_request_preserve_patch as _install_factory_request_preserve_patch,
)
from .heart_rate_core import install_shared_heart_rate_core as _install_shared_heart_rate_core
from .agent_hard_query_reliability_patch import (
    install_hard_query_reliability_patch as _install_hard_query_reliability_patch,
)
from .agent_tool_factory_compat_patch import (
    install_tool_factory_compat_patch as _install_tool_factory_compat_patch,
)
from .agent_tool_factory_english_patch import (
    install_tool_factory_english_patch as _install_tool_factory_english_patch,
)
from .agent_tool_factory_reliability_patch import (
    install_agent_tool_factory_reliability_patch as _install_agent_tool_factory_reliability_patch,
)
from .agent_tool_factory_schema_guard import (
    install_schema_aware_tool_factory as _install_schema_aware_tool_factory,
)
from .agent_tool_factory_semantic_compat_patch import (
    install_semantic_tool_factory_compat_patch as _install_semantic_tool_factory_compat_patch,
)
from .agent_tool_factory_semantic_guard import (
    install_semantic_tool_factory_guard as _install_semantic_tool_factory_guard,
)

__version__ = "1.2.1"

# Keep desktop and Android on exactly the same heart-rate semantics. Importing
# the package installs the shared five-minute averaging/parser into analysis.py;
# CI refreshes these files from the canonical desktop repository before release.
_install_shared_heart_rate_core(_analysis)

# These shared installers also contain lazy hooks for the desktop Qt runtime.
# Android implements those policies in PersonalAgentController, so mark only the
# desktop-runtime portions as already handled while installing the exact same
# deterministic tools, schema compiler and semantic guards.
_reliability._RUNTIME_INSTALLED = True
_semantic._RUNTIME_INSTALLED = True
_hard_query._RUNTIME_INSTALLED = True
_install_agent_tool_factory_reliability_patch()
_install_schema_aware_tool_factory()
_install_tool_factory_compat_patch()
_install_semantic_tool_factory_guard()
_install_semantic_tool_factory_compat_patch()
_install_hard_query_reliability_patch()
_install_factory_request_preserve_patch()
_install_tool_factory_english_patch()
