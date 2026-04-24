#!/bin/bash
# Prints every line
set -x
# Stops the scripts on failure
set -e

echo `pwd`
. ./constants.sh

# Set the file to be copied and the service to be restarted
SOURCE_FILE="./build/libs/tasker-0.0.1-SNAPSHOT.jar"
SERVICE_NAME="tasks"
SERVICE_BINARY="tasks.jar"

# Create a new folder on the remote server with a timestamp
NEW_FOLDER="/opt/$SERVICE_NAME/$(date +%Y%m%d%H%M%S)"

# Create the folder
ssh -i "$REMOTE_KEY" "$REMOTE_USER@$REMOTE_HOST" -f "mkdir -p $NEW_FOLDER"

# Copy the file from the local machine to the new folder on the remote server
scp -i "$REMOTE_KEY" "$SOURCE_FILE" "$REMOTE_USER@$REMOTE_HOST:$NEW_FOLDER/$SERVICE_BINARY"

# Connect to the remote server using SSH with the private key
ssh -i "$REMOTE_KEY" "$REMOTE_USER@$REMOTE_HOST" -T <<REMOTE_COMMANDS
    # Stop the existing service
    systemctl stop "$SERVICE_NAME"

    # Update the symlink to point to the new folder
    ln -sf "$NEW_FOLDER/$SERVICE_BINARY" "/opt/$SERVICE_NAME/current/$SERVICE_BINARY"

    # Start the service
    systemctl start "$SERVICE_NAME"
REMOTE_COMMANDS