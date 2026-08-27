#!/bin/bash
# Prints every line
set -x
# Stops the scripts on failure
set -e

# Build
./gradlew clean release -x test

# Set the file to be copied and the service to be restarted
BASE_PATH="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
SOURCE_FILE="$BASE_PATH/build/libs/Taskboard-0.0.1-SNAPSHOT.jar"
SERVICE_NAME="tasks"
ANSIBLE_FOLDER=~/src/itayp_dev/ansible

cd $ANSIBLE_FOLDER
ansible-playbook deploy-app.yml -e ansible_user=deploy -e service=$SERVICE_NAME -e jar=$SOURCE_FILE
