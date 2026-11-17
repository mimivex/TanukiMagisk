#!/bin/sh
# Builds module-template.zip from this directory and prints its SHA-256.
# The archive is written next to this script. Run it after every edit;
# commit the regenerated zip so the repo always carries a flashable copy.
set -e
cd "$(dirname "$0")"
rm -f module-template.zip
zip -X -r module-template.zip . \
  -x 'module-template.zip' -x 'pack.sh' -x '*/.git*' -x '*/.gitkeep'
sha256sum module-template.zip
