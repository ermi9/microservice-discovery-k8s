#!/bin/bash
# configure-cluster.sh
#
# Adjusts the partition count and replica count for the discovery service
# without rebuilding any images. Spring Boot reads PARTITION_COUNT as the
# partition.count property via environment variable binding.
#
# Usage:
#   ./configure-cluster.sh [partition-count] [replica-count]
#
# Examples:
#   ./configure-cluster.sh          # defaults: 3 partitions, 3 replicas
#   ./configure-cluster.sh 5 5      # 5 partitions, 5 replicas
#   ./configure-cluster.sh 3 1      # single replica for local testing

set -e

PARTITION_COUNT=${1:-3}
REPLICA_COUNT=${2:-3}

if ! [[ "$PARTITION_COUNT" =~ ^[1-9][0-9]*$ ]] || ! [[ "$REPLICA_COUNT" =~ ^[1-9][0-9]*$ ]]; then
    echo "Error: partition-count and replica-count must be positive integers"
    echo "Usage: $0 [partition-count] [replica-count]"
    exit 1
fi

if [ "$PARTITION_COUNT" -gt "$REPLICA_COUNT" ]; then
    echo "Warning: more partitions ($PARTITION_COUNT) than replicas ($REPLICA_COUNT)."
    echo "Some partitions will have no leader until more replicas start."
fi

echo ""
echo "Configuring EDA Discovery Service"
echo "  Partitions : $PARTITION_COUNT"
echo "  Replicas   : $REPLICA_COUNT"
echo ""

echo "[1/3] Setting PARTITION_COUNT=$PARTITION_COUNT on the StatefulSet..."
kubectl set env statefulset/discovery-service PARTITION_COUNT="$PARTITION_COUNT"

echo "[2/3] Scaling to $REPLICA_COUNT replicas..."
kubectl scale statefulset discovery-service --replicas="$REPLICA_COUNT"

echo "[3/3] Triggering rolling restart to apply the new env var..."
kubectl rollout restart statefulset/discovery-service

echo ""
echo "Waiting for rollout to complete..."
kubectl rollout status statefulset/discovery-service --timeout=120s

echo ""
echo "Done. Current pod state:"
kubectl get pods -l app=discovery-service
