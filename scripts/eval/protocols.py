"""Compatibility re-export: the corpus manifest in corpus.py is the single source of truth."""
import os
import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from corpus import PROT  # noqa: F401
