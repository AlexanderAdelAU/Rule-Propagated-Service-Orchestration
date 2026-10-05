#!/bin/sh
cd "$(dirname "$0")" || exit 1
if [ "$#" -lt 1 ]; then
    echo 'Usage: sh launch.sh p1|p2|p3|p4|p5|p6|monitor [v001]'
    exit 1
fi
java -jar btsn-infrastructure.jar "$@" &
infrastructure_pid=$!
business_pid=
if [ "$1" != monitor ]; then
    java -jar btsn-business-services.jar "$@" &
    business_pid=$!
fi
trap 'kill "$infrastructure_pid" ${business_pid:+"$business_pid"} 2>/dev/null' INT TERM EXIT
wait "$infrastructure_pid"
if [ -n "$business_pid" ]; then wait "$business_pid"; fi
