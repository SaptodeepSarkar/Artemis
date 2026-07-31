#!/usr/bin/env python3
"""Artemis Web Dashboard — entry point."""
import sys
import os
# Add parent to path so imports work
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from server import run

if __name__ == "__main__":
    run()
