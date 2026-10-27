#!/bin/sh
set -eu

source_jar=/opt/easyiga/connector/connector-gitlab-rest-2.3.0-easyiga-connector.jar
target_dir=/midpoint-home/connid-connectors
target_jar="$target_dir/connector-gitlab-rest-2.3.0-easyiga.jar"
temporary_jar="$target_jar.tmp"

mkdir -p "$target_dir"
rm -f "$temporary_jar"

if [ -f "$target_jar" ] && cmp -s "$source_jar" "$target_jar"; then
    already_installed=true
else
    already_installed=false
    cp "$source_jar" "$temporary_jar"
    chmod 0644 "$temporary_jar"
fi

# Only this project's versioned GitLab connector files are eligible for cleanup.
find "$target_dir" -maxdepth 1 -type f \
    -name 'connector-gitlab-rest-*-easyiga.jar' \
    ! -name "$(basename "$target_jar")" \
    -delete

if [ "$already_installed" = true ]; then
    echo "easyIGA GitLab connector is already installed; stale fork versions were removed"
else
    mv -f "$temporary_jar" "$target_jar"
    echo "Installed easyIGA GitLab connector: $(sha256sum "$target_jar" | cut -d ' ' -f 1)"
fi
