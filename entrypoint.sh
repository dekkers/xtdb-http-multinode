#!/bin/bash
set -e

# Make env variable comparison case insensitive
shopt -s nocasematch

# Check if the mounted docker volume only contains the three directories listed
# below. If this is the case, we are mounting a volume that was previously
# created by the original XTDB container and we can automatically migrate this
# to the first node of the multinode container. By default the the node will
# have the name _dev, but this can be changed using $XTDB_MIGRATION_NODE_NAME.
xtdb_node_directories="docs
indices
tx-log"

XTDB_DATA_DIR="${XTDB_DATA_DIR:-/var/lib/xtdb}"
XTDB_MIGRATION_NODE_NAME="${XTDB_MIGRATION_NODE_NAME:-_dev}"

data_dir_contents=$(ls "$XTDB_DATA_DIR")

if [ "$data_dir_contents" = "$xtdb_node_directories" ]; then
    mkdir "$XTDB_DATA_DIR/$XTDB_MIGRATION_NODE_NAME"
    mv "$XTDB_DATA_DIR/docs" "$XTDB_DATA_DIR/$XTDB_MIGRATION_NODE_NAME/documents"
    mv "$XTDB_DATA_DIR/indices" "$XTDB_DATA_DIR/$XTDB_MIGRATION_NODE_NAME/indexes"
    mv "$XTDB_DATA_DIR/tx-log" "$XTDB_DATA_DIR/$XTDB_MIGRATION_NODE_NAME/tx-log"
    echo "Migrated old xtdb volume to new node $XTDB_MIGRATION_NODE_NAME"
fi

if [ "$XTDB_DISABLE_AUTO_UPGRADE" != "1" ] && [ "$XTDB_DISABLE_AUTO_UPGRADE" != "true" ] && [ "$data_dir_contents" ] ; then
    for node in "$XTDB_DATA_DIR"/*;do
        # We check the exit code explicitly
        set +e
        index_version=$(ldb --db="$node/indexes" get --hex 0x06)
        exitcode=$?
        set -e
        if [ $exitcode -eq 0 ] && (( index_version < 22 )) ; then
          echo "$node has index version $index_version which is earlier than 22, deleting the index"
          rm -f "$node"/indexes/*
        fi
    done
fi

exec "$@"
